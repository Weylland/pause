using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pause;

internal sealed class DriveSync(JournalStore store)
{
    private readonly DriveConnection connection = new();
    private readonly Dictionary<string, (string Id, DriveEdit[] Edits)> cache = new(StringComparer.Ordinal);
    internal bool Connected => connection.Connected;
    internal bool Busy { get; private set; }
    internal string Status { get; private set; } = "Google Drive n’est pas connecté.";
    internal IReadOnlyList<DriveConflict> Conflicts { get; private set; } = [];
    internal bool ReceivedDraft { get; private set; }

    private sealed record Baseline(string Revision, Dictionary<string, JsonNode> Entries);

    internal async Task ConnectAsync(string? path, CancellationToken cancellation)
    {
        if (Busy) return;
        Busy = true;
        try
        {
            await connection.ConnectAsync(path, cancellation);
            cache.Clear();
            Status = "Google Drive connecté.";
        }
        finally { Busy = false; }
    }

    internal void Disconnect()
    {
        if (Busy) return;
        connection.Disconnect();
        cache.Clear();
        Conflicts = [];
        Status = "Google Drive déconnecté de ce PC.";
    }

    internal async Task SyncAsync(CancellationToken cancellation, DriveConflict? resolution = null, JsonNode? choice = null)
    {
        if (!Connected || Busy) return;
        Busy = true;
        ReceivedDraft = false;
        try
        {
            var (drive, account) = await connection.FilesAsync(cancellation);
            string path = Path.Combine(JournalStore.DirectoryPath, "drive-base-" + account + ".json");
            Baseline baseline = File.Exists(path)
                ? JsonSerializer.Deserialize<Baseline>(await File.ReadAllTextAsync(path, cancellation)) ?? throw new InvalidDataException("Dernière synchro Drive illisible.")
                : new("", new(StringComparer.Ordinal));
            var (cloud, revision) = await drive.ReadAsync(cache, cancellation);
            VerifyHistory(baseline.Revision, revision);
            JournalState local = store.Snapshot();
            var plan = cloud.Plan(baseline.Entries, local.Entries);
            if (resolution is not null)
            {
                DriveConflict? now = plan.Conflicts.FirstOrDefault(item => item.Key == resolution.Key);
                if (now is not null && JsonNode.DeepEquals(now.Local, resolution.Local) && JsonNode.DeepEquals(now.Cloud, resolution.Cloud)
                    && now.Alternatives.Count == resolution.Alternatives.Count
                    && now.Alternatives.Zip(resolution.Alternatives).All(pair => JsonNode.DeepEquals(pair.First, pair.Second)))
                    plan = cloud.Plan(baseline.Entries, local.Entries, new Dictionary<string, JsonNode?> { [now.Key] = choice });
            }
            if (plan.Upload.Count > 0)
            {
                string batch = await drive.AppendAsync(local.Id, revision, plan.Upload, cancellation);
                (cloud, revision) = await drive.ReadAsync(cache, cancellation);
                VerifyHistory(baseline.Revision, revision);
                if (!cloud.HasBatch(batch))
                {
                    Status = "Google Drive enregistre tes changements. La vérification reprendra automatiquement.";
                    return;
                }
            }

            List<DriveConflict> conflicts = cloud.Plan(baseline.Entries, local.Entries).Conflicts;
            if (resolution is not null && plan.Upload.Any(edit => edit.Key == resolution.Key && edit.Resolve)
                && !cloud.HasConflict(resolution.Key)) conflicts.RemoveAll(item => item.Key == resolution.Key);
            var (edits, nextBaseline) = cloud.Receive(baseline.Entries, local.Entries, conflicts);
            if (edits.Count > 0) store.ApplyDriveChanges(edits);
            ReceivedDraft = edits.Any(edit => edit.Key.StartsWith("draft/", StringComparison.Ordinal) && edit.After is not null);
            if (revision != baseline.Revision || !SameMaps(nextBaseline, baseline.Entries))
            {
                string temporary = path + ".tmp";
                await File.WriteAllTextAsync(temporary, JsonSerializer.Serialize(new Baseline(revision, nextBaseline)), cancellation);
                File.Move(temporary, path, overwrite: true);
            }
            Conflicts = conflicts;
            Status = conflicts.Count == 0 ? $"Google Drive · synchronisé à {DateTime.Now:HH:mm}"
                : $"Google Drive · {conflicts.Count} entrée(s) à vérifier";
        }
        catch (Exception error) when (error is IOException or HttpRequestException or JsonException or InvalidOperationException or UnauthorizedAccessException)
        {
            Status = error is HttpRequestException ? "Google Drive indisponible. Tes saisies restent sur ce PC." : error.Message;
        }
        catch (OperationCanceledException) when (!cancellation.IsCancellationRequested)
        {
            Status = "Google Drive n’a pas répondu. Nouvelle tentative automatique.";
        }
        finally { Busy = false; }
    }

    private static void VerifyHistory(string previous, string current)
    {
        var ids = current.Split(',', StringSplitOptions.RemoveEmptyEntries).ToHashSet(StringComparer.Ordinal);
        if (previous.Split(',', StringSplitOptions.RemoveEmptyEntries).Any(id => !ids.Contains(id)))
            throw new InvalidDataException("Des fichiers de synchro Drive manquent. Ton journal local est conservé.");
    }

    private static bool SameMaps(Dictionary<string, JsonNode> first, Dictionary<string, JsonNode> second) =>
        first.Count == second.Count && first.All(item => JsonNode.DeepEquals(item.Value, second.GetValueOrDefault(item.Key)));
}
