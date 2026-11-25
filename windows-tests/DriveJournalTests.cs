using System.IO;
using System.Text.Json.Nodes;
using Xunit;

namespace Pause.Tests;

public class DriveJournalTests
{
    [Fact]
    public void SharedHistory()
    {
        JsonObject shared = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "drive.json")))!.AsObject();
        var journal = new DriveJournal(DriveJournal.ParseSnapshot(shared["snapshot"]!.AsObject()));
        foreach (JsonNode? item in shared["batches"]!.AsArray())
        {
            var batch = DriveJournal.Parse(item!.AsObject());
            journal.Replay(batch.Id, batch.Edits);
        }
        DriveSnapshot expected = DriveJournal.ParseSnapshot(shared["expected"]!.AsObject());
        JsonObject actual = DriveJournal.Encode(journal.Snapshot(5, 0));
        Assert.True(JsonNode.DeepEquals(DriveJournal.Encode(expected), actual), actual.ToJsonString());
        Assert.True(JsonNode.DeepEquals(actual, DriveJournal.Encode(DriveJournal.ParseSnapshot(JsonNode.Parse(actual.ToJsonString())!.AsObject()))));

        var (upload, conflicts) = journal.Plan(Entries(shared["baseline"]!), Entries(shared["local"]!));
        Assert.Equal(Keys(shared["upload"]!), upload.Select(edit => edit.Key));
        Assert.Equal(Keys(shared["conflicts"]!), conflicts.Select(conflict => conflict.Key));
    }

    private static Dictionary<string, JsonNode> Entries(JsonNode values) =>
        values.AsObject().ToDictionary(item => item.Key, item => JournalStore.Normalize(item.Key, item.Value)!);

    private static IEnumerable<string> Keys(JsonNode values) => values.AsArray().Select(key => key!.GetValue<string>());
}
