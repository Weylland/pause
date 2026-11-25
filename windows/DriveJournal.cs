using System.IO;
using System.Text.Json.Nodes;

namespace Pause;

internal sealed record DriveEdit(string Key, JsonNode? Before, JsonNode? After, bool Resolve = false);
internal sealed record DriveConflict(string Key, JsonNode? Local, JsonNode? Cloud, IReadOnlyList<JsonNode?> Alternatives);
internal sealed record DriveSnapshot(long Generation, long Reset, string[] Batches, Dictionary<string, JsonNode> Entries,
    Dictionary<string, List<JsonNode?>> Alternatives)
{
    internal static DriveSnapshot Empty => new(0, 0, [], new(StringComparer.Ordinal), new(StringComparer.Ordinal));
}

internal sealed class DriveJournal(DriveSnapshot start)
{
    private readonly Dictionary<string, JsonNode> entries = new(start.Entries, StringComparer.Ordinal);
    private readonly Dictionary<string, List<JsonNode?>> alternatives =
        start.Alternatives.ToDictionary(item => item.Key, item => item.Value.ToList(), StringComparer.Ordinal);
    private readonly HashSet<string> replayed = new(start.Batches, StringComparer.Ordinal);
    internal bool HasBatch(string id) => replayed.Contains(id);
    internal bool HasConflict(string key) => alternatives.ContainsKey(key);

    internal void Replay(string id, IReadOnlyList<DriveEdit> edits)
    {
        if (!replayed.Add(id)) return;
        foreach (DriveEdit edit in edits)
        {
            JsonNode? current = entries.GetValueOrDefault(edit.Key);
            if (Same(current, edit.Before) && (edit.Resolve || !alternatives.ContainsKey(edit.Key)))
            {
                Assign(entries, edit.Key, edit.After);
                if (edit.Resolve) alternatives.Remove(edit.Key);
            }
            else if (!Same(current, edit.After))
            {
                if (!alternatives.TryGetValue(edit.Key, out List<JsonNode?>? values))
                    alternatives.Add(edit.Key, values = []);
                if (values.All(value => !Same(value, edit.After))) values.Add(edit.After);
            }
        }
    }

    internal (List<DriveEdit> Upload, List<DriveConflict> Conflicts) Plan(
        IReadOnlyDictionary<string, JsonNode> baseline, IReadOnlyDictionary<string, JsonNode> local,
        IReadOnlyDictionary<string, JsonNode?>? resolutions = null)
    {
        var changes = new List<DriveEdit>();
        var conflicts = new List<DriveConflict>();
        foreach (string key in Keys(baseline, local))
        {
            JsonNode? before = baseline.GetValueOrDefault(key), here = local.GetValueOrDefault(key), there = entries.GetValueOrDefault(key);
            bool localChanged = !Same(before, here), cloudChanged = !Same(before, there);
            if (alternatives.TryGetValue(key, out List<JsonNode?>? candidates) || localChanged && cloudChanged && !Same(here, there))
            {
                if (resolutions is not null && resolutions.TryGetValue(key, out JsonNode? value))
                    changes.Add(new DriveEdit(key, there, value, true));
                else conflicts.Add(new DriveConflict(key, here, there, candidates ?? []));
            }
            else if (localChanged && !Same(here, there)) changes.Add(new DriveEdit(key, there, here));
        }
        return (changes, conflicts);
    }

    internal (List<Change> Apply, Dictionary<string, JsonNode> Baseline) Receive(
        IReadOnlyDictionary<string, JsonNode> baseline, IReadOnlyDictionary<string, JsonNode> sent,
        IReadOnlyList<DriveConflict> conflicts)
    {
        var blocked = conflicts.Select(item => item.Key).Concat(alternatives.Keys).ToHashSet(StringComparer.Ordinal);
        var next = new Dictionary<string, JsonNode>(baseline, StringComparer.Ordinal);
        var apply = new List<Change>();
        foreach (string key in Keys(baseline, sent))
        {
            if (blocked.Contains(key)) continue;
            JsonNode? local = sent.GetValueOrDefault(key), cloud = entries.GetValueOrDefault(key);
            if (!Same(local, cloud)) apply.Add(new Change(key, local, cloud));
            Assign(next, key, cloud);
        }
        return (apply, next);
    }

    internal DriveSnapshot Snapshot(long generation, long reset) =>
        new(generation, reset, replayed.Order(StringComparer.Ordinal).ToArray(), entries, alternatives);

    internal static JsonObject Encode(DriveSnapshot snapshot) => new()
    {
        ["v"] = 1, ["generation"] = snapshot.Generation, ["reset"] = snapshot.Reset,
        ["batches"] = new JsonArray(snapshot.Batches.Select(id => (JsonNode?)id).ToArray()),
        ["entries"] = new JsonObject(snapshot.Entries.Select(item => KeyValuePair.Create(item.Key, (JsonNode?)item.Value.DeepClone()))),
        ["alternatives"] = new JsonObject(snapshot.Alternatives.Select(item =>
            KeyValuePair.Create(item.Key, (JsonNode?)new JsonArray(item.Value.Select(value => value?.DeepClone()).ToArray()))))
    };

    internal static DriveSnapshot ParseSnapshot(JsonObject content)
    {
        if (content["v"]?.GetValue<int>() != 1 || content["batches"] is not JsonArray batches
            || content["entries"] is not JsonObject values || content["alternatives"] is not JsonObject conflicts)
            throw new InvalidDataException("État Drive illisible.");
        return new(content["generation"]!.GetValue<long>(), content["reset"]!.GetValue<long>(),
            batches.Select(id => id!.GetValue<string>()).ToArray(),
            values.ToDictionary(item => item.Key, item => JournalStore.Normalize(item.Key, item.Value)
                ?? throw new InvalidDataException("État Drive illisible."), StringComparer.Ordinal),
            conflicts.ToDictionary(item => item.Key, item => item.Value!.AsArray()
                .Select(value => JournalStore.Normalize(item.Key, value)).ToList(), StringComparer.Ordinal));
    }

    internal static (string Id, DriveEdit[] Edits) Parse(JsonObject batch)
    {
        if (batch["v"]?.GetValue<int>() != 1 || batch["id"]?.GetValue<string>() is not string id
            || id.Length is not (32 or 64) || !id.All(Uri.IsHexDigit) || batch["edits"] is not JsonArray edits)
            throw new InvalidDataException("Historique Drive illisible.");
        var normalized = new List<DriveEdit>(edits.Count);
        var keys = new HashSet<string>(StringComparer.Ordinal);
        foreach (JsonNode? item in edits)
        {
            if (item is not JsonObject row || row["key"]?.GetValue<string>() is not string key || !keys.Add(key)
                || !row.ContainsKey("before") || !row.ContainsKey("after"))
                throw new InvalidDataException("Modification Drive illisible.");
            normalized.Add(new DriveEdit(key, JournalStore.Normalize(key, row["before"]), JournalStore.Normalize(key, row["after"]),
                row["resolve"]?.GetValue<bool>() ?? false));
        }
        return (id, normalized.ToArray());
    }

    private IOrderedEnumerable<string> Keys(IReadOnlyDictionary<string, JsonNode> baseline, IReadOnlyDictionary<string, JsonNode> local) =>
        entries.Keys.Concat(alternatives.Keys).Concat(baseline.Keys).Concat(local.Keys).Distinct(StringComparer.Ordinal).Order(StringComparer.Ordinal);

    private static bool Same(JsonNode? first, JsonNode? second) => JsonNode.DeepEquals(first, second);

    private static void Assign(IDictionary<string, JsonNode> map, string key, JsonNode? value)
    {
        if (value is null) map.Remove(key);
        else map[key] = value;
    }
}
