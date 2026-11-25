using System.Globalization;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pause;

internal sealed record Goal(DateOnly Start, int Duration)
{
    internal const int MaxDuration = 366;
    internal DateOnly End => Start.AddDays(Duration - 1);
}

internal sealed record Meal(string Key, string Name, double Kcal, string Source, double? Amount, double? Per100);

internal sealed record MealDraft(string Id, DateOnly Date, string Description);

internal sealed record SavedReport(string Id, string Title, string Text, DateOnly Start, DateOnly End, DateTimeOffset Created);

internal sealed record DayRow(DateOnly Date, string? Status, double? Weight, int Meals, double? Kcal);

internal sealed record JournalReport(Goal? Goal, int Due, int Kept, int NotKept, DayRow[] Rows)
{
    internal static JournalReport Build(JournalState state, DateOnly first, DateOnly last, DateOnly now)
    {
        Goal? goal = state.Goal;
        var rows = new List<DayRow>();
        int due = 0, kept = 0, notKept = 0;
        var meals = state.Entries.Where(entry => entry.Key.StartsWith("meal/", StringComparison.Ordinal))
            .ToLookup(entry => entry.Key.Substring(5, 10), entry => entry.Value["kcal"]!.GetValue<double>());
        for (DateOnly date = first; date <= last; date = date.AddDays(1))
        {
            string key = JournalState.Iso(date);
            string? status = state.Entries.GetValueOrDefault("pause/" + key)?.GetValue<string>();
            double? weight = state.Entries.GetValueOrDefault("weight/" + key)?.GetValue<double>();
            double[] kcal = meals[key].ToArray();
            rows.Add(new DayRow(date, status, weight, kcal.Length, kcal.Length == 0 ? null : kcal.Sum()));
            if (goal is null || date < goal.Start || date > goal.End || date > now) continue;
            due++;
            if (status == "tenu") kept++;
            if (status == JournalState.NotKeptStatus) notKept++;
        }
        return new JournalReport(goal, due, kept, notKept, rows.ToArray());
    }
}

internal sealed record JournalState(string Id, SortedDictionary<string, JsonNode> Entries, DateTimeOffset? LastSync)
{
    internal const string NotKeptStatus = "consommation";

    internal static string Iso(DateOnly day) => day.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    internal static DateOnly ParseDate(string? value) =>
        DateOnly.TryParseExact(value, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out DateOnly date)
        && date.Year is >= 1900 and <= 9998 ? date : throw new InvalidDataException("Date invalide.");

    internal Goal? Goal => Entries.TryGetValue("goal", out JsonNode? value)
        ? new Goal(ParseDate(value["start"]!.GetValue<string>()), value["duration"]!.GetValue<int>()) : null;
    internal double? WeightTarget => Entries.GetValueOrDefault("target/weight")?.GetValue<double>();
    internal double? CaloriesTarget => Entries.GetValueOrDefault("target/calories")?.GetValue<double>();

    internal int Streak(DateOnly today)
    {
        if (Goal is not Goal goal || today < goal.Start) return 0;
        DateOnly day = today > goal.End ? goal.End : today;
        if (day == today && StatusOn(day) is null) day = day.AddDays(-1);
        int count = 0;
        while (day >= goal.Start && StatusOn(day) == "tenu")
        {
            count++;
            day = day.AddDays(-1);
        }
        return count;
    }

    private string? StatusOn(DateOnly day) => Entries.GetValueOrDefault("pause/" + Iso(day))?.GetValue<string>();

    internal (int Due, int Kept, int NotKept) PauseProgress(Goal goal, DateOnly today)
    {
        int due = Math.Clamp(today.DayNumber - goal.Start.DayNumber + 1, 0, goal.Duration);
        int kept = 0, notKept = 0;
        for (int i = 0; i < due; i++)
        {
            string? status = StatusOn(goal.Start.AddDays(i));
            if (status == "tenu") kept++;
            if (status == NotKeptStatus) notKept++;
        }
        return (due, kept, notKept);
    }

    internal Meal[] Meals(DateOnly date)
    {
        string prefix = "meal/" + Iso(date) + "/";
        return Entries.Where(entry => entry.Key.StartsWith(prefix, StringComparison.Ordinal))
            .Select(entry =>
            {
                JsonNode value = entry.Value;
                return new Meal(entry.Key, value["name"]!.GetValue<string>(), value["kcal"]!.GetValue<double>(),
                    value["source"]!.GetValue<string>(), value["amount"]?.GetValue<double>(),
                    value["kcalPer100"]?.GetValue<double>());
            }).ToArray();
    }

    internal SavedReport? Report(string id) => Entries.TryGetValue("report/" + id, out JsonNode? value)
        ? ReadReport(id, value) : null;

    internal SavedReport[] Reports() => Entries.Where(entry => entry.Key.StartsWith("report/", StringComparison.Ordinal))
        .Select(entry => ReadReport(entry.Key[7..], entry.Value))
        .OrderByDescending(report => report.Created).ThenBy(report => report.Id, StringComparer.Ordinal).ToArray();

    internal MealDraft[] MealDrafts() => Entries.Where(entry => JournalStore.IsDraftKey(entry.Key))
        .Select(entry => new MealDraft(entry.Key[6..], ParseDate(entry.Value["date"]!.GetValue<string>()),
            entry.Value["description"]!.GetValue<string>()))
        .OrderByDescending(draft => draft.Date).ThenBy(draft => draft.Id, StringComparer.Ordinal).ToArray();

    private static SavedReport ReadReport(string id, JsonNode value) => new(
        id, value["title"]!.GetValue<string>(), value["text"]!.GetValue<string>(),
        ParseDate(value["start"]!.GetValue<string>()), ParseDate(value["end"]!.GetValue<string>()),
        value["created"]!.GetValue<DateTimeOffset>());
}

internal sealed record Change(string Key, JsonNode? Before, JsonNode? After);

internal sealed record MergeResult(JournalState? State, IReadOnlyList<Change> Conflicts, bool DifferentJournal);

internal sealed class JournalStore
{
    internal static string DirectoryPath => Path.GetFullPath(Environment.GetEnvironmentVariable("PAUSE_DATA_DIR")
        ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Pause"));
    internal static string FilePath => Path.Combine(DirectoryPath, "journal.json");

    internal JournalStore()
    {
        WithWriteLock(() =>
        {
            if (File.Exists(FilePath)) return Read();
            if (File.Exists(FilePath + ".bak"))
                throw new IOException("Le fichier du journal est manquant. Une sauvegarde se trouve dans le dossier Pause.");
            var created = new JournalState(Guid.NewGuid().ToString("D"), new(StringComparer.Ordinal), null);
            Save(created);
            return created;
        });
    }

    internal JournalState Snapshot() => Read();

    internal void Update(params (string Key, JsonNode? Value)[] edits)
    {
        WithWriteLock(() =>
        {
            SaveEdits(Read(), edits);
            return true;
        });
    }

    internal bool TryUpdate(IReadOnlyList<Change> edits) =>
        WithWriteLock(() =>
        {
            JournalState state = Read();
            if (edits.Any(edit => !JsonNode.DeepEquals(state.Entries.GetValueOrDefault(edit.Key), edit.Before))) return false;
            SaveEdits(state, edits.Select(edit => (edit.Key, edit.After)));
            return true;
        });

    internal void ApplyDriveChanges(IReadOnlyList<Change> edits) => WithWriteLock(() =>
    {
        JournalState state = Read();
        SaveEdits(state, edits.Where(edit => JsonNode.DeepEquals(state.Entries.GetValueOrDefault(edit.Key), edit.Before))
            .Select(edit => (edit.Key, edit.After)));
        return true;
    });

    internal double SaveEstimate(MealDraft draft, IReadOnlyList<EstimatedFood> foods, string source)
    {
        string key = "draft/" + draft.Id;
        JsonNode? before = Snapshot().Entries.GetValueOrDefault(key);
        if (before is null || before["description"]?.GetValue<string>() != draft.Description
            || before["date"]?.GetValue<string>() != JournalState.Iso(draft.Date))
            throw new InvalidDataException("Repas en attente introuvable ou déjà traité.");
        if (draft.Date > DateOnly.FromDateTime(DateTime.Today)) throw new InvalidDataException("Date du repas invalide.");
        if (foods.Count is < 1 or > 20) throw new InvalidDataException("Indique entre 1 et 20 aliments.");
        var changes = new List<Change> { new(key, before, null) };
        double total = 0;
        foreach (EstimatedFood food in foods)
        {
            if (food.Grams is not double grams || grams is not (>= .0001 and <= 10000)
                || food.Per100 is not double per100 || per100 is not (>= 0 and <= 1000))
                throw new InvalidDataException("Complète la quantité et les calories pour 100 de chaque aliment.");
            double kcal = grams * per100 / 100;
            string mealKey = $"meal/{JournalState.Iso(draft.Date)}/{Guid.NewGuid():D}";
            var value = new JsonObject { ["name"] = food.Name.Trim(), ["source"] = food.Source ?? source,
                ["kcal"] = kcal, ["amount"] = grams, ["kcalPer100"] = per100 };
            changes.Add(new Change(mealKey, null, Normalize(mealKey, value)));
            total += kcal;
        }
        if (!TryUpdate(changes)) throw new InvalidDataException("Ce repas a changé pendant la saisie. Relis les repas en attente.");
        MealEstimate.Forget(draft);
        return Math.Round(total, 1);
    }

    internal MergeResult Merge(string? journalId, IReadOnlyList<Change> changes) =>
        WithWriteLock<MergeResult>(() =>
        {
            JournalState state = Read();
            if (journalId is not null && state.Id != journalId) return new(null, [], true);
            var conflicts = new List<Change>();
            var next = new SortedDictionary<string, JsonNode>(state.Entries, StringComparer.Ordinal);
            foreach (Change change in changes)
            {
                JsonNode? current = state.Entries.GetValueOrDefault(change.Key);
                if (JsonNode.DeepEquals(current, change.After)) continue;
                if (JsonNode.DeepEquals(current, change.Before)) Assign(next, change.Key, change.After);
                else conflicts.Add(new Change(change.Key, current, change.After));
            }
            if (conflicts.Count > 0) return new(null, conflicts, false);
            var merged = state with { Entries = next, LastSync = DateTimeOffset.UtcNow };
            Save(merged);
            return new(merged, [], false);
        });

    private static void SaveEdits(JournalState state, IEnumerable<(string Key, JsonNode? Value)> edits)
    {
        var next = new SortedDictionary<string, JsonNode>(state.Entries, StringComparer.Ordinal);
        foreach (var (key, value) in edits) Assign(next, key, Normalize(key, value));
        if (next.Count == state.Entries.Count
            && next.All(entry => JsonNode.DeepEquals(entry.Value, state.Entries.GetValueOrDefault(entry.Key)))) return;
        Save(state with { Entries = next });
    }

    private static T WithWriteLock<T>(Func<T> operation)
    {
        string id = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(FilePath.ToUpperInvariant())));
        using var mutex = new Mutex(false, @"Local\Pause.Journal." + id);
        try
        {
            if (!mutex.WaitOne(TimeSpan.FromSeconds(5)))
                throw new IOException("Le journal est occupé. Réessaie dans quelques instants.");
        }
        catch (AbandonedMutexException)
        {
            // L'écriture précédente utilisait un fichier temporaire ou un remplacement atomique.
        }
        try
        {
            return operation();
        }
        finally
        {
            mutex.ReleaseMutex();
        }
    }

    private static void Assign(IDictionary<string, JsonNode> map, string key, JsonNode? value)
    {
        if (value is null) map.Remove(key);
        else map[key] = value;
    }

    internal static bool IsFailure(Exception error) => error is IOException or InvalidDataException or UnauthorizedAccessException;

    internal static JournalState Read()
    {
        try { return Load(); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or ArgumentException or FormatException or NullReferenceException)
        {
            throw new InvalidDataException("Le fichier du journal est illisible. Une sauvegarde se trouve dans le dossier Pause.", error);
        }
    }

    private static JournalState Load()
    {
        using var file = new FileStream(FilePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        if (file.Length > SyncServer.MaxBytes) throw new InvalidDataException("Le journal est trop volumineux.");
        var root = JsonNode.Parse(file)?.AsObject() ?? throw new InvalidDataException("Journal illisible.");
        if (root["v"]?.GetValue<int>() != 1 || !Guid.TryParse(root["id"]?.GetValue<string>(), out _))
            throw new InvalidDataException("Format du journal incompatible.");
        DateTimeOffset? sync = root["lastSync"]?.GetValue<DateTimeOffset>();
        var entries = new SortedDictionary<string, JsonNode>(StringComparer.Ordinal);
        foreach (var entry in root["entries"]!.AsObject())
        {
            entries.Add(entry.Key, Normalize(entry.Key, entry.Value) ?? throw new InvalidDataException("Entrée vide."));
        }
        return new JournalState(root["id"]!.GetValue<string>(), entries, sync);
    }

    private static void Save(JournalState state)
    {
        Directory.CreateDirectory(DirectoryPath);
        var encoded = JsonSerializer.SerializeToUtf8Bytes(new { v = 1, id = state.Id, entries = state.Entries, lastSync = state.LastSync });
        if (encoded.Length > SyncServer.MaxBytes) throw new InvalidDataException("Le journal est trop volumineux.");
        string temporary = FilePath + ".tmp";
        using (var file = new FileStream(temporary, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            file.Write(encoded);
            file.Flush(flushToDisk: true);
        }
        if (File.Exists(FilePath)) File.Replace(temporary, FilePath, FilePath + ".bak");
        else File.Move(temporary, FilePath);
    }

    internal static JsonNode? Normalize(string key, JsonNode? value)
    {
        if (JournalTracker.IsKey(key)) return JournalTracker.Normalize(key, value);
        if (key is "target/weight" or "target/calories")
            return value is null ? null : JsonValue.Create(Number(value, double.Epsilon, key == "target/weight" ? 1000 : 100000));
        if (key == "goal")
        {
            if (value is null) return null;
            DateOnly start = JournalState.ParseDate(value["start"]?.GetValue<string>());
            int duration = value["duration"]?.GetValue<int>() ?? 0;
            if (duration is < 1 or > Goal.MaxDuration) throw new InvalidDataException("Durée invalide.");
            return new JsonObject { ["start"] = JournalState.Iso(start), ["duration"] = duration };
        }

        string[] parts = key.Split('/');
        if (parts.Length < 2) throw new InvalidDataException("Entrée inconnue.");
        if (parts.Length == 2 && parts[0] == "draft" && Guid.TryParseExact(parts[1], "D", out _))
        {
            if (value is null) return null;
            DateOnly date = JournalState.ParseDate(value["date"]?.GetValue<string>());
            string description = value["description"]?.GetValue<string>()?.Trim() ?? "";
            if (description.Length is < 1 or > 700) throw new InvalidDataException("Description de repas invalide.");
            return new JsonObject { ["date"] = JournalState.Iso(date), ["description"] = description };
        }
        if (parts.Length == 2 && parts[0] == "report" && Guid.TryParseExact(parts[1], "D", out _))
        {
            if (value is null) return null;
            string title = value["title"]?.GetValue<string>()?.Trim() ?? "";
            string text = value["text"]?.GetValue<string>()?.Trim() ?? "";
            DateOnly start = JournalState.ParseDate(value["start"]?.GetValue<string>());
            DateOnly end = JournalState.ParseDate(value["end"]?.GetValue<string>());
            DateTimeOffset? created = value["created"]?.GetValue<DateTimeOffset>();
            if (title.Length is < 1 or > 120 || text.Length is < 1 or > 12000 || start > end || created is null)
                throw new InvalidDataException("Bilan invalide.");
            return new JsonObject
            {
                ["title"] = title, ["text"] = text, ["start"] = JournalState.Iso(start), ["end"] = JournalState.Iso(end),
                ["created"] = JsonValue.Create(created.Value.ToUniversalTime())
            };
        }
        JournalState.ParseDate(parts[1]);
        if (parts.Length == 2 && parts[0] == "pause")
        {
            if (value is null) return null;
            string status = value.GetValue<string>();
            if (status is not ("tenu" or JournalState.NotKeptStatus)) throw new InvalidDataException("État invalide.");
            return JsonValue.Create(status);
        }
        if (parts.Length == 2 && parts[0] == "weight")
        {
            if (value is null) return null;
            return JsonValue.Create(Number(value, double.Epsilon, 1000));
        }
        if (parts.Length == 3 && parts[0] == "meal" && Guid.TryParseExact(parts[2], "D", out _))
        {
            if (value is null) return null;
            string name = value["name"]?.GetValue<string>() ?? "";
            string source = value["source"]?.GetValue<string>() ?? "";
            if (string.IsNullOrWhiteSpace(name) || name.Length > 90 || source.Length > 32)
                throw new InvalidDataException("Aliment invalide.");
            JsonNode? amount = value["amount"], per100 = value["kcalPer100"];
            if ((amount is null) != (per100 is null)) throw new InvalidDataException("Portion incomplète.");
            var meal = new JsonObject
            {
                ["name"] = name,
                ["kcal"] = Number(value["kcal"], 0, 100000),
                ["source"] = source
            };
            if (amount is not null)
            {
                meal["amount"] = Number(amount, double.Epsilon, 10000);
                meal["kcalPer100"] = Number(per100, 0, 1000);
            }
            return meal;
        }
        throw new InvalidDataException("Entrée inconnue.");
    }

    internal static bool IsExtendedKey(string key) =>
        key.StartsWith("target/", StringComparison.Ordinal) || key.StartsWith("report/", StringComparison.Ordinal);
    internal static bool IsDraftKey(string key) => key.StartsWith("draft/", StringComparison.Ordinal);

    private static double Number(JsonNode? value, double minimum, double maximum)
    {
        double number = value?.GetValue<double>() ?? double.NaN;
        if (!double.IsFinite(number) || number < minimum || number > maximum)
            throw new InvalidDataException("Valeur numérique invalide.");
        return number;
    }
}
