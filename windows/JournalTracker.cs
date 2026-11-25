using System.IO;
using System.Text.Json.Nodes;

namespace Pause;

internal sealed record JournalTracker(string Id, string Name, bool Pause, DateOnly Start, int Duration, int RestDays)
{
    internal enum Day { Done, Missed, Rest }
    internal sealed record Progress(int Done, int Rests, int Streak);
    internal const int MaxName = 60;
    internal const string Prefix = "track/";
    internal string Key => Prefix + Id;
    internal string DayKey(DateOnly date) => Key + "/" + JournalState.Iso(date);
    internal DateOnly? End => Duration == 0 ? null : Start.AddDays(Duration - 1);
    internal bool Includes(DateOnly date) => date >= Start && (End is not DateOnly end || date <= end);
    internal string Positive => Pause ? "Tenue" : "Fait";
    internal string Negative => Pause ? "Non tenue" : "Non fait";
    internal string DayLabel(Day? status) => status switch { Day.Done => Positive, Day.Missed => Negative, Day.Rest => "Repos", _ => "Sans réponse" };

    internal JsonObject ToJson() => new()
    {
        ["name"] = Name, ["kind"] = Pause ? "pause" : "habit", ["start"] = JournalState.Iso(Start), ["duration"] = Duration,
        ["restDays"] = RestDays
    };

    private static JournalTracker Parse(string id, JsonNode value)
    {
        string name = value["name"]?.GetValue<string>().Trim() ?? "";
        string? kind = value["kind"]?.GetValue<string>();
        DateOnly start = JournalState.ParseDate(value["start"]?.GetValue<string>());
        int duration = value["duration"]?.GetValue<int>() ?? -1;
        int rests = value["restDays"]?.GetValue<int>() ?? 0;
        if (name.Length is < 1 or > MaxName || kind is not ("pause" or "habit") || duration is < 0 or > Goal.MaxDuration
            || rests is < 0 or > Goal.MaxDuration || duration > 0 && rests > duration)
            throw new InvalidDataException("Suivi invalide.");
        return new JournalTracker(id, name, kind == "pause", start, duration, rests);
    }

    internal static bool IsKey(string key) => key.StartsWith(Prefix, StringComparison.Ordinal);

    internal static JsonNode? Normalize(string key, JsonNode? value)
    {
        string[] parts = key.Split('/');
        if (parts.Length is < 2 or > 3 || !Guid.TryParseExact(parts[1], "D", out Guid id) || id.ToString("D") != parts[1])
            throw new InvalidDataException("Identifiant de suivi invalide.");
        if (parts.Length == 2) return value is null ? null : Parse(parts[1], value).ToJson();
        JournalState.ParseDate(parts[2]);
        return DayValue(ParseDay(value));
    }

    private static Day? ParseDay(JsonNode? value) => value?.GetValueKind() switch
    {
        null => null,
        System.Text.Json.JsonValueKind.True => Day.Done,
        System.Text.Json.JsonValueKind.False => Day.Missed,
        System.Text.Json.JsonValueKind.String when value.GetValue<string>() == "rest" => Day.Rest,
        _ => throw new InvalidDataException("Réponse de suivi invalide.")
    };

    private static JsonNode? DayValue(Day? status) => status switch
    {
        Day.Done => JsonValue.Create(true), Day.Missed => JsonValue.Create(false), Day.Rest => JsonValue.Create("rest"), _ => null
    };

    internal static JournalTracker[] List(JournalState state) => state.Entries
        .Where(entry => IsKey(entry.Key) && entry.Value is JsonObject)
        .Select(entry => Parse(entry.Key[Prefix.Length..], entry.Value))
        .OrderBy(tracker => tracker.Name, StringComparer.CurrentCultureIgnoreCase).ThenBy(tracker => tracker.Id, StringComparer.Ordinal).ToArray();

    internal static JournalTracker? Find(JournalState state, string id) =>
        state.Entries.TryGetValue(Prefix + id, out JsonNode? value) ? Parse(id, value) : null;

    internal Day? Status(JournalState state, DateOnly date) => ParseDay(state.Entries.GetValueOrDefault(DayKey(date)));

    internal IEnumerable<(DateOnly Date, Day Status)> Days(JournalState state, DateOnly today)
    {
        string prefix = Key + "/";
        foreach (var (key, value) in state.Entries)
        {
            if (!key.StartsWith(prefix, StringComparison.Ordinal)) continue;
            DateOnly date = JournalState.ParseDate(key[prefix.Length..]);
            if (date <= today && Includes(date) && ParseDay(value) is Day status) yield return (date, status);
        }
    }

    internal Progress GetProgress(JournalState state, DateOnly today)
    {
        var days = Days(state, today).ToArray();
        DateOnly[] rests = days.Where(entry => entry.Status == Day.Rest).Select(entry => entry.Date).ToArray();
        var allowed = rests.Take(RestDays).ToHashSet();
        DateOnly day = End is DateOnly end && end < today ? end : today;
        if (day == today && Status(state, day) is null) day = day.AddDays(-1);
        int streak = 0;
        while (Includes(day))
        {
            if (Status(state, day) == Day.Done) streak++;
            else if (!allowed.Contains(day)) break;
            day = day.AddDays(-1);
        }
        return new Progress(days.Count(entry => entry.Status == Day.Done), rests.Length, streak);
    }

    internal void Record(JournalStore store, JournalState snapshot, DateOnly date, Day? status)
    {
        if (!Includes(date) || date > DateOnly.FromDateTime(DateTime.Today))
            throw new InvalidDataException("Choisis un jour du suivi qui est déjà arrivé.");
        if (snapshot.Entries.GetValueOrDefault(Key) is not JsonNode before)
            throw new InvalidDataException("Suivi introuvable.");
        string entry = DayKey(date);
        if (!store.TryUpdate([new Change(Key, before, before), new Change(entry, snapshot.Entries.GetValueOrDefault(entry), DayValue(status))]))
            throw new InvalidDataException("Le suivi a changé. Réessaie après l’actualisation.");
    }
}
