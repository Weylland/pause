using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pause;

internal sealed class DriveCache
{
    private readonly string path;
    private readonly Dictionary<string, JsonObject> contents = new(StringComparer.Ordinal);
    private bool changed;

    internal DriveCache(string account)
    {
        path = PathFor(account);
        if (!File.Exists(path)) return;
        try
        {
            foreach (var (id, content) in JsonNode.Parse(File.ReadAllText(path))!.AsObject()) contents[id] = content!.AsObject();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException or InvalidOperationException or NullReferenceException)
        {
            contents.Clear();
        }
    }

    internal JsonObject? Get(string id) => contents.GetValueOrDefault(id);

    internal void Add(string id, JsonObject content)
    {
        contents[id] = content;
        changed = true;
    }

    internal void Keep(IEnumerable<string> ids)
    {
        var used = ids.ToHashSet(StringComparer.Ordinal);
        foreach (string id in contents.Keys.Where(id => !used.Contains(id)).ToArray()) changed |= contents.Remove(id);
    }

    internal void Save()
    {
        if (!changed) return;
        try
        {
            string temporary = path + ".tmp";
            File.WriteAllText(temporary, new JsonObject(contents.Select(item => KeyValuePair.Create(item.Key, (JsonNode?)item.Value.DeepClone()))).ToJsonString());
            File.Move(temporary, path, overwrite: true);
            changed = false;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
    }

    internal static void Delete(string account)
    {
        try { File.Delete(PathFor(account)); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
    }

    private static string PathFor(string account) => Path.Combine(JournalStore.DirectoryPath, "drive-cache-" + account + ".json");
}
