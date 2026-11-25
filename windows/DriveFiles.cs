using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Pause;

internal sealed record DriveRemote(DriveJournal Journal, long Generation, long Reset, string[] Pending, string[] Obsolete, bool Settled);

internal sealed class DriveFiles(string access)
{
    private const int MaxBytes = 8_388_608;
    private const string Files = "https://www.googleapis.com/drive/v3/files";
    private const string Upload = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id";
    private static readonly Regex BatchName = new("^fil-des-jours-changes-v1-([0-9a-f]{32})\\.json$", RegexOptions.CultureInvariant);
    private static readonly Regex SnapshotName = new("^fil-des-jours-snapshot-v1-([0-9]{1,12})-[0-9a-f]{32}\\.json$", RegexOptions.CultureInvariant);
    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    private sealed record Stored(string Id, string? Batch, long Generation, DateTimeOffset Created);

    internal async Task<string> AccountAsync(CancellationToken cancellation)
    {
        JsonObject about = await RequestAsync(HttpMethod.Get,
            "https://www.googleapis.com/drive/v3/about?fields=user%28permissionId%29", null, cancellation);
        string? identity = about["user"]?["permissionId"]?.GetValue<string>();
        if (string.IsNullOrWhiteSpace(identity)) throw new InvalidDataException("Compte Google Drive introuvable.");
        return Digest(identity);
    }

    internal async Task<DriveRemote> ReadAsync(DriveCache cache, CancellationToken cancellation)
    {
        Stored[] files = await ListAsync(cancellation);
        Stored? origin = files.Where(file => file.Batch is null).OrderByDescending(file => file.Generation)
            .ThenBy(file => file.Created).ThenBy(file => file.Id, StringComparer.Ordinal).FirstOrDefault();
        DriveSnapshot start = DriveSnapshot.Empty;
        if (origin is not null)
        {
            start = DriveJournal.ParseSnapshot(await ContentAsync(origin, cache, cancellation));
            if (start.Generation != origin.Generation) throw new InvalidDataException("Nom du fichier Drive invalide.");
        }
        var journal = new DriveJournal(start);
        Stored[] pending = files.Where(file => file.Batch is not null && !journal.HasBatch(file.Batch))
            .OrderBy(file => file.Created).ThenBy(file => file.Id, StringComparer.Ordinal).ToArray();
        foreach (Stored file in pending)
        {
            var batch = DriveJournal.Parse(await ContentAsync(file, cache, cancellation));
            if (file.Batch != batch.Id) throw new InvalidDataException("Nom du fichier Drive invalide.");
            journal.Replay(batch.Id, batch.Edits);
        }
        cache.Keep(pending.Append(origin).OfType<Stored>().Select(file => file.Id));
        cache.Save();
        bool settled = origin is null || files.Max(file => file.Created) - origin.Created >= TimeSpan.FromDays(1);
        string[] obsolete = origin is null || !settled ? [] : files.Except(pending).Where(file => file != origin).Select(file => file.Id).ToArray();
        return new(journal, start.Generation, start.Reset, pending.Select(file => file.Batch!).Distinct().Order(StringComparer.Ordinal).ToArray(), obsolete, settled);
    }

    internal async Task<string> AppendAsync(string writer, DriveRemote remote, IReadOnlyList<DriveEdit> edits, CancellationToken cancellation)
    {
        string changes = JsonSerializer.Serialize(edits, Json);
        string id = Digest($"{writer}\n{remote.Generation}\n{string.Join(',', remote.Pending)}\n{changes}")[..32];
        await CreateAsync($"fil-des-jours-changes-v1-{id}.json", JsonSerializer.SerializeToUtf8Bytes(new { v = 1, id, edits }, Json), cancellation);
        return id;
    }

    internal async Task CompactAsync(string writer, DriveRemote remote, DriveCache cache, CancellationToken cancellation)
    {
        JsonObject content = DriveJournal.Encode(remote.Journal.Snapshot(remote.Generation + 1, remote.Reset));
        string file = await CreateSnapshotAsync(writer, remote.Generation + 1, content, cancellation);
        cache.Add(file, content);
    }

    internal async Task ReplaceAsync(string writer, long known, IEnumerable<string> batches,
        IReadOnlyDictionary<string, JsonNode> entries, CancellationToken cancellation)
    {
        Stored[] files = await ListAsync(cancellation);
        long generation = files.Select(file => file.Generation).Append(known).Max() + Random.Shared.Next(1_000, 1_000_000);
        string[] covered = files.Select(file => file.Batch).OfType<string>().Concat(batches).Distinct().Order(StringComparer.Ordinal).ToArray();
        var snapshot = new DriveSnapshot(generation, generation, covered, new(entries, StringComparer.Ordinal), new(StringComparer.Ordinal));
        await CreateSnapshotAsync(writer, generation, DriveJournal.Encode(snapshot), cancellation);
    }

    internal async Task DeleteAsync(string id, CancellationToken cancellation)
    {
        using var request = new HttpRequestMessage(HttpMethod.Delete, Files + "/" + Uri.EscapeDataString(id));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", access);
        using HttpResponseMessage response = await DriveConnection.Http.SendAsync(request, cancellation);
        if (!response.IsSuccessStatusCode && response.StatusCode != HttpStatusCode.NotFound)
            throw new IOException("Google Drive n’a pas effacé un ancien fichier.");
    }

    private async Task<Stored[]> ListAsync(CancellationToken cancellation)
    {
        var files = new List<Stored>();
        string? page = null;
        do
        {
            string url = Files + "?spaces=appDataFolder&pageSize=1000&fields=nextPageToken%2Cfiles%28id%2Cname%2CcreatedTime%29"
                + "&q=" + Uri.EscapeDataString("trashed = false");
            if (page is not null) url += "&pageToken=" + Uri.EscapeDataString(page);
            JsonObject response = await RequestAsync(HttpMethod.Get, url, null, cancellation);
            if (response["files"] is not JsonArray rows) throw new InvalidDataException("Liste des fichiers Drive illisible.");
            foreach (JsonNode? item in rows)
            {
                string? id = item?["id"]?.GetValue<string>(), name = item?["name"]?.GetValue<string>();
                if (id is null || name is null || !DateTimeOffset.TryParse(item?["createdTime"]?.GetValue<string>(), out DateTimeOffset created))
                    throw new InvalidDataException("Fichier d’historique Drive illisible.");
                Match batch = BatchName.Match(name), snapshot = SnapshotName.Match(name);
                if (!batch.Success && !snapshot.Success)
                    throw new InvalidDataException("La synchro Drive vient d’une version plus récente. Mets à jour Fil des jours.");
                files.Add(new(id, batch.Success ? batch.Groups[1].Value : null, snapshot.Success ? long.Parse(snapshot.Groups[1].Value) : 0, created));
            }
            if (files.Count > 10_000) throw new InvalidDataException("Historique Drive trop volumineux.");
            page = response["nextPageToken"]?.GetValue<string>();
        } while (!string.IsNullOrEmpty(page));
        return files.ToArray();
    }

    private async Task<JsonObject> ContentAsync(Stored file, DriveCache cache, CancellationToken cancellation)
    {
        if (cache.Get(file.Id) is JsonObject known) return known;
        JsonObject content = await RequestAsync(HttpMethod.Get, Files + "/" + Uri.EscapeDataString(file.Id) + "?alt=media", null, cancellation);
        cache.Add(file.Id, content);
        return content;
    }

    private async Task<string> CreateSnapshotAsync(string writer, long generation, JsonObject content, CancellationToken cancellation)
    {
        byte[] bytes = JsonSerializer.SerializeToUtf8Bytes(content);
        string id = Digest(writer + "\n" + Convert.ToHexString(SHA256.HashData(bytes)))[..32];
        return await CreateAsync($"fil-des-jours-snapshot-v1-{generation}-{id}.json", bytes, cancellation);
    }

    private async Task<string> CreateAsync(string name, byte[] bytes, CancellationToken cancellation)
    {
        if (bytes.Length > MaxBytes) throw new InvalidDataException("Trop de changements à envoyer à Drive à la fois.");
        string metadata = JsonSerializer.Serialize(new { name, parents = new[] { "appDataFolder" }, mimeType = "application/json" }, Json);
        string boundary = Guid.NewGuid().ToString("N");
        using var content = new ByteArrayContent(Encoding.UTF8.GetBytes($"--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{metadata}\r\n--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
            .Concat(bytes).Concat(Encoding.ASCII.GetBytes($"\r\n--{boundary}--\r\n")).ToArray());
        content.Headers.ContentType = new MediaTypeHeaderValue("multipart/related");
        content.Headers.ContentType.Parameters.Add(new NameValueHeaderValue("boundary", boundary));
        JsonObject created = await RequestAsync(HttpMethod.Post, Upload, content, cancellation);
        return created["id"]?.GetValue<string>() ?? throw new InvalidDataException("Réponse Drive illisible.");
    }

    private async Task<JsonObject> RequestAsync(HttpMethod method, string url, HttpContent? payload, CancellationToken cancellation)
    {
        using var request = new HttpRequestMessage(method, url) { Content = payload };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", access);
        using HttpResponseMessage response = await DriveConnection.Http.SendAsync(request, cancellation);
        if (!response.IsSuccessStatusCode)
            throw new IOException($"Google Drive est indisponible ({(int)response.StatusCode}). Tes saisies restent sur cet appareil.");
        if (response.Content.Headers.ContentLength > MaxBytes)
            throw new InvalidDataException("Réponse Drive trop volumineuse.");
        return JsonNode.Parse(await response.Content.ReadAsStringAsync(cancellation))?.AsObject()
            ?? throw new InvalidDataException("Réponse Drive illisible.");
    }

    private static string Digest(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
}
