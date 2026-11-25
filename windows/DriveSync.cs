using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pause;

internal sealed class DriveSync(JournalStore store)
{
    private const int CompactAfter = 40, DeletePerSync = 20;
    private readonly DriveConnection connection = new();
    private (string Account, DriveCache Files)? cache;
    internal bool Connected => connection.Connected;
    internal bool Busy { get; private set; }
    internal string Status { get; private set; } = "Google Drive n’est pas connecté.";
    internal IReadOnlyList<DriveConflict> Conflicts { get; private set; } = [];
    internal bool ReceivedDraft { get; private set; }

    private sealed record Baseline(long Generation, string[] Batches, Dictionary<string, JsonNode> Entries)
    {
        internal static Baseline Empty => new(0, [], new(StringComparer.Ordinal));
    }

    internal async Task ConnectAsync(string? path, CancellationToken cancellation)
    {
        if (Busy) return;
        Busy = true;
        try
        {
            await connection.ConnectAsync(path, cancellation);
            cache = null;
            Status = "Google Drive connecté.";
        }
        finally { Busy = false; }
    }

    internal void Disconnect()
    {
        if (Busy) return;
        if (connection.Account is string account) DriveCache.Delete(account);
        connection.Disconnect();
        cache = null;
        Conflicts = [];
        Status = "Google Drive déconnecté de ce PC.";
    }

    internal Task SyncAsync(CancellationToken cancellation, DriveConflict? resolution = null, JsonNode? choice = null) =>
        RunAsync(cancellation, (drive, account) => ExchangeAsync(drive, account, resolution, choice, cancellation));

    internal Task ReplaceAsync(CancellationToken cancellation) => RunAsync(cancellation, async (drive, account) =>
    {
        Baseline baseline;
        try { baseline = await ReadBaselineAsync(account, cancellation); }
        catch (Exception error) when (error is JsonException or InvalidDataException)
        {
            File.Delete(BaselinePath(account));
            baseline = Baseline.Empty;
        }
        JournalState local = store.Snapshot();
        await drive.ReplaceAsync(local.Id, baseline.Generation, baseline.Batches, local.Entries, cancellation);
        await ExchangeAsync(drive, account, null, null, cancellation);
    });

    private async Task ExchangeAsync(DriveFiles drive, string account, DriveConflict? resolution, JsonNode? choice, CancellationToken cancellation)
    {
        Baseline saved = await ReadBaselineAsync(account, cancellation);
        DriveCache files = CacheFor(account);
        DriveRemote remote = await drive.ReadAsync(files, cancellation);
        Baseline baseline = Verify(saved, remote);
        JournalState local = store.Snapshot();
        var plan = remote.Journal.Plan(baseline.Entries, local.Entries);
        if (resolution is not null)
        {
            DriveConflict? now = plan.Conflicts.FirstOrDefault(item => item.Key == resolution.Key);
            if (now is not null && JsonNode.DeepEquals(now.Local, resolution.Local) && JsonNode.DeepEquals(now.Cloud, resolution.Cloud)
                && now.Alternatives.Count == resolution.Alternatives.Count
                && now.Alternatives.Zip(resolution.Alternatives).All(pair => JsonNode.DeepEquals(pair.First, pair.Second)))
                plan = remote.Journal.Plan(baseline.Entries, local.Entries, new Dictionary<string, JsonNode?> { [now.Key] = choice });
        }
        if (plan.Upload.Count > 0)
        {
            string batch = await drive.AppendAsync(local.Id, remote, plan.Upload, cancellation);
            remote = await drive.ReadAsync(files, cancellation);
            baseline = Verify(saved, remote);
            if (!remote.Journal.HasBatch(batch))
            {
                Status = "Google Drive enregistre tes changements. La vérification reprendra automatiquement.";
                return;
            }
        }

        List<DriveConflict> conflicts = remote.Journal.Plan(baseline.Entries, local.Entries).Conflicts;
        if (resolution is not null && plan.Upload.Any(edit => edit.Key == resolution.Key && edit.Resolve)
            && !remote.Journal.HasConflict(resolution.Key)) conflicts.RemoveAll(item => item.Key == resolution.Key);
        var (edits, entries) = remote.Journal.Receive(baseline.Entries, local.Entries, conflicts);
        if (edits.Count > 0) store.ApplyDriveChanges(edits);
        ReceivedDraft = edits.Any(edit => edit.Key.StartsWith("draft/", StringComparison.Ordinal) && edit.After is not null);
        if (remote.Generation != saved.Generation || !remote.Pending.SequenceEqual(saved.Batches) || !SameMaps(entries, saved.Entries))
            await SaveBaselineAsync(account, new(remote.Generation, remote.Pending, entries), cancellation);
        Conflicts = conflicts;
        Status = conflicts.Count == 0 ? $"Google Drive · synchronisé à {DateTime.Now:HH:mm}"
            : $"Google Drive · {conflicts.Count} entrée(s) à vérifier";
        await TidyAsync(drive, local.Id, remote, files, cancellation);
    }

    private async Task RunAsync(CancellationToken cancellation, Func<DriveFiles, string, Task> work)
    {
        if (!Connected || Busy) return;
        Busy = true;
        ReceivedDraft = false;
        try
        {
            var (drive, account) = await connection.FilesAsync(cancellation);
            await work(drive, account);
        }
        catch (OperationCanceledException) when (!cancellation.IsCancellationRequested)
        {
            Status = "Google Drive n’a pas répondu. Nouvelle tentative automatique.";
        }
        catch (Exception error) when (error is not OperationCanceledException)
        {
            Status = JournalStore.IsFailure(error) ? error.Message
                : error is HttpRequestException ? "Google Drive indisponible. Tes saisies restent sur ce PC."
                : "La synchronisation Drive a été interrompue. Tes saisies restent sur ce PC.";
        }
        finally { Busy = false; }
    }

    private static async Task TidyAsync(DriveFiles drive, string writer, DriveRemote remote, DriveCache files, CancellationToken cancellation)
    {
        try
        {
            foreach (string id in remote.Obsolete.Take(DeletePerSync)) await drive.DeleteAsync(id, cancellation);
            if (!remote.Settled || remote.Pending.Length < CompactAfter) return;
            await drive.CompactAsync(writer, remote, files, cancellation);
            files.Save();
        }
        catch (Exception error) when (error is IOException or HttpRequestException
            || error is OperationCanceledException && !cancellation.IsCancellationRequested) { }
    }

    private DriveCache CacheFor(string account)
    {
        if (cache?.Account != account) cache = (account, new DriveCache(account));
        return cache.Value.Files;
    }

    private static string BaselinePath(string account) => Path.Combine(JournalStore.DirectoryPath, "drive-base-" + account + ".json");

    private static async Task<Baseline> ReadBaselineAsync(string account, CancellationToken cancellation)
    {
        string path = BaselinePath(account);
        if (!File.Exists(path)) return Baseline.Empty;
        Baseline? saved = JsonSerializer.Deserialize<Baseline>(await File.ReadAllTextAsync(path, cancellation));
        return saved is { Batches: not null, Entries: not null } ? saved : throw new InvalidDataException("Dernière synchro Drive illisible.");
    }

    private static async Task SaveBaselineAsync(string account, Baseline baseline, CancellationToken cancellation)
    {
        string path = BaselinePath(account), temporary = path + ".tmp";
        await File.WriteAllTextAsync(temporary, JsonSerializer.Serialize(baseline), cancellation);
        File.Move(temporary, path, overwrite: true);
    }

    private static Baseline Verify(Baseline saved, DriveRemote remote)
    {
        if (remote.Reset > saved.Generation) return Baseline.Empty;
        if (remote.Generation < saved.Generation || !saved.Batches.All(remote.Journal.HasBatch))
            throw new InvalidDataException("Des fichiers de synchro Drive manquent. Ton journal local est conservé. Si cela dure, remplace le contenu de Drive depuis cet appareil.");
        return saved;
    }

    private static bool SameMaps(Dictionary<string, JsonNode> first, Dictionary<string, JsonNode> second) =>
        first.Count == second.Count && first.All(item => JsonNode.DeepEquals(item.Value, second.GetValueOrDefault(item.Key)));
}
