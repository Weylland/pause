using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pause;

internal sealed class DriveFiles(string access)
{
    private const string Files = "https://www.googleapis.com/drive/v3/files";
    private const string Upload = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id";
    private const string Prefix = "fil-des-jours-changes-v1-";
    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    internal async Task<string> AccountAsync(CancellationToken cancellation)
    {
        JsonObject about = await RequestAsync(HttpMethod.Get,
            "https://www.googleapis.com/drive/v3/about?fields=user%28permissionId%29", null, cancellation);
        string? identity = about["user"]?["permissionId"]?.GetValue<string>();
        if (string.IsNullOrWhiteSpace(identity)) throw new InvalidDataException("Compte Google Drive introuvable.");
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(identity))).ToLowerInvariant();
    }

    internal async Task<(DriveJournal Journal, string Revision)> ReadAsync(
        Dictionary<string, (string Id, DriveEdit[] Edits)> cache, CancellationToken cancellation)
    {
        var files = new List<(string Id, string Name, DateTimeOffset Created)>();
        string? page = null;
        do
        {
            string url = Files + "?spaces=appDataFolder&pageSize=1000&fields=nextPageToken%2Cfiles%28id%2Cname%2CcreatedTime%29"
                + "&q=" + Uri.EscapeDataString($"name contains '{Prefix}'");
            if (page is not null) url += "&pageToken=" + Uri.EscapeDataString(page);
            JsonObject response = await RequestAsync(HttpMethod.Get, url, null, cancellation);
            if (response["files"] is not JsonArray rows) throw new InvalidDataException("Liste des fichiers Drive illisible.");
            foreach (JsonNode? item in rows)
            {
                string? id = item?["id"]?.GetValue<string>(), name = item?["name"]?.GetValue<string>();
                if (id is null || name is null || !name.StartsWith(Prefix, StringComparison.Ordinal)
                    || !DateTimeOffset.TryParse(item?["createdTime"]?.GetValue<string>(), out DateTimeOffset created))
                    throw new InvalidDataException("Fichier d’historique Drive illisible.");
                files.Add((id, name, created));
            }
            if (files.Count > 10_000) throw new InvalidDataException("Historique Drive trop volumineux.");
            page = response["nextPageToken"]?.GetValue<string>();
        } while (!string.IsNullOrEmpty(page));

        var journal = new DriveJournal();
        foreach (var file in files.OrderBy(item => item.Created).ThenBy(item => item.Id, StringComparer.Ordinal))
        {
            if (!cache.TryGetValue(file.Id, out var batch))
            {
                JsonObject content = await RequestAsync(HttpMethod.Get, Files + "/" + Uri.EscapeDataString(file.Id) + "?alt=media", null, cancellation);
                batch = DriveJournal.Parse(content);
                if (file.Name != Prefix + batch.Id + ".json") throw new InvalidDataException("Nom du fichier Drive invalide.");
                cache.Add(file.Id, batch);
            }
            journal.Replay(batch.Id, batch.Edits);
        }
        string revision = string.Join(',', files.Select(file => file.Id).Order(StringComparer.Ordinal));
        return (journal, revision);
    }

    internal async Task<string> AppendAsync(string writerId, string revision, IReadOnlyList<DriveEdit> edits, CancellationToken cancellation)
    {
        byte[] changes = JsonSerializer.SerializeToUtf8Bytes(edits, Json);
        string id = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(writerId + "\n" + revision + "\n" + Convert.ToHexString(changes)))).ToLowerInvariant();
        byte[] batch = JsonSerializer.SerializeToUtf8Bytes(new { v = 1, id, edits }, Json);
        if (batch.Length > SyncServer.MaxBytes) throw new InvalidDataException("Trop de changements à envoyer à Drive à la fois.");
        string metadata = JsonSerializer.Serialize(new { name = Prefix + id + ".json", parents = new[] { "appDataFolder" }, mimeType = "application/json" }, Json);
        string boundary = Guid.NewGuid().ToString("N");
        using var content = new ByteArrayContent(Encoding.UTF8.GetBytes($"--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{metadata}\r\n--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
            .Concat(batch).Concat(Encoding.ASCII.GetBytes($"\r\n--{boundary}--\r\n")).ToArray());
        content.Headers.ContentType = new MediaTypeHeaderValue("multipart/related");
        content.Headers.ContentType.Parameters.Add(new NameValueHeaderValue("boundary", boundary));
        await RequestAsync(HttpMethod.Post, Upload, content, cancellation);
        return id;
    }

    private async Task<JsonObject> RequestAsync(HttpMethod method, string url, HttpContent? payload, CancellationToken cancellation)
    {
        using var request = new HttpRequestMessage(method, url) { Content = payload };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", access);
        using HttpResponseMessage response = await DriveConnection.Http.SendAsync(request, cancellation);
        if (!response.IsSuccessStatusCode)
            throw new IOException($"Google Drive est indisponible ({(int)response.StatusCode}). Tes saisies restent sur cet appareil.");
        if (response.Content.Headers.ContentLength > SyncServer.MaxBytes)
            throw new InvalidDataException("Réponse Drive trop volumineuse.");
        return JsonNode.Parse(await response.Content.ReadAsStringAsync(cancellation))?.AsObject()
            ?? throw new InvalidDataException("Réponse Drive illisible.");
    }
}
