using System.Globalization;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace Pause;

internal static class FoodReference
{
    internal sealed record Item(string Name, double Per100, string[] Keys, string[] Stems, double? Unit);
    internal sealed record Result(Item? Reference, Item? Guess, Item[] Options, double? Unit);
    private sealed record Serving(string Container, string[] Foods, double Grams);
    private static readonly Lazy<Item[]> Catalog = new(Load);
    private static readonly Lazy<Serving[]> Servings = new(LoadServings);
    private static readonly HashSet<string> Ignored = ["a", "au", "aux", "d", "de", "des", "du", "en", "et", "l", "la", "le", "les", "ou"];
    private static readonly HashSet<string> Extras = ["bio", "chaud", "chaude", "fait", "faite", "frais", "fraiche", "froid", "froide", "maison"];

    private static IEnumerable<string[]> Rows(string resource)
    {
        using Stream stream = typeof(FoodReference).Assembly.GetManifestResourceStream(resource)
            ?? throw new IOException("Référence Ciqual indisponible.");
        using var reader = new StreamReader(stream, Encoding.UTF8);
        while (reader.ReadLine() is string line)
            if (!line.StartsWith('#')) yield return line.Split('\t');
    }

    private static Serving[] LoadServings() => Rows("Pause.portions.tsv")
        .Select(fields => new Serving(fields[0], fields[1].Split('|', StringSplitOptions.RemoveEmptyEntries), double.Parse(fields[2], CultureInfo.InvariantCulture)))
        .ToArray();

    private static Item[] Load()
    {
        var items = new List<Item>();
        foreach (string[] fields in Rows("Pause.ciqual.tsv"))
        {
            if (fields.Length < 3 || !double.TryParse(fields[1], NumberStyles.Float, CultureInfo.InvariantCulture, out double kcal)) continue;
            string[] keys = new[] { fields[0] }.Concat(fields[2].Split('|', StringSplitOptions.RemoveEmptyEntries))
                .Select(Normalize).ToArray();
            double? unit = fields.Length > 3 ? double.Parse(fields[3], CultureInfo.InvariantCulture) : null;
            items.Add(new Item(fields[0], kcal, keys, keys.Select(Stem).ToArray(), unit));
        }
        return items.ToArray();
    }

    internal static Result Match(string name)
    {
        string query = Normalize(name);
        if (query.Length < 2) return new(null, null, [], null);
        if (Options(query).Length == 0) query = string.Join(' ', query.Split(' ').Where(word => !Extras.Contains(word)));
        Item[] exact = Exact(query);
        Item[] options = Options(query);
        Item? reference = exact.Length == 1 ? exact[0] : null;
        Item? guess = reference ?? options.FirstOrDefault(item => !Raw(item.Keys[0]) || Raw(query)) ?? options.FirstOrDefault();
        double[] weights = exact.Where(item => item.Unit.HasValue).Select(item => item.Unit!.Value).Distinct().ToArray();
        return new(reference, guess, options, weights.Length == 1 ? weights[0] : exact.Length == 0 ? guess?.Unit : null);
    }

    internal static bool Known(string name) => Options(Normalize(name)).Length > 0;

    internal static string[] Split(string name)
    {
        string query = Normalize(name);
        string first = Stem(query).Split(' ')[0] + " ";
        return Exact(query).Length > 0 || Options(query).Any(item => item.Stems.Any(key => (key + " ").StartsWith(first, StringComparison.Ordinal)))
            ? [] : Segments(name.Split(' ')) ?? [];
    }

    private static string[]? Segments(string[] words)
    {
        words = words.SkipWhile(word => Ignored.Contains(Normalize(word)) || Extras.Contains(Normalize(word))).ToArray();
        if (words.Length == 0) return [];
        for (int kept = words.Length; kept > 0; kept--)
        {
            string head = string.Join(' ', words[..kept]);
            if (Exact(Normalize(head)).Length > 0 && Segments(words[kept..]) is string[] rest) return [head, .. rest];
        }
        return null;
    }

    internal static double Portion(string? container, string name, Item? reference)
    {
        string query = Normalize(name);
        string[] texts = new[] { query, reference?.Keys[0] ?? "" }.SelectMany(text => new[] { text, Stem(text) }).ToArray();
        bool Fits(Serving serving) => serving.Foods.Length == 0
            || serving.Foods.Any(food => texts.Any(text => (text + " ").StartsWith(food + " ", StringComparison.Ordinal)));
        Serving? held = container is null ? null : Servings.Value.FirstOrDefault(serving => serving.Container == container && Fits(serving));
        Serving usual = held ?? Servings.Value.First(serving => serving.Container == "*" && Fits(serving));
        return held is null && usual.Foods.Length == 0 && reference?.Unit is double unit ? unit : usual.Grams;
    }

    internal static string Container(string text)
    {
        string name = Stem(Normalize(text));
        if (name.StartsWith("cuill", StringComparison.Ordinal) || name.StartsWith("c a ", StringComparison.Ordinal))
            return name.EndsWith(" cafe", StringComparison.Ordinal) || name == "c a c" ? "cuillere a cafe" : "cuillere a soupe";
        return name;
    }

    private static Item[] Exact(string query)
    {
        Item[] exact = Catalog.Value.Where(item => item.Keys.Contains(query)).ToArray();
        return exact.Length > 0 ? exact : Catalog.Value.Where(item => item.Stems.Contains(Stem(query))).ToArray();
    }

    private static Item[] Options(string query) => Exact(query).Concat(Candidates(query)).Distinct().Take(8).ToArray();

    private static bool Raw(string name) => Regex.IsMatch(name, @"\b(?:crue?s?|secs?|seches?|bebe|poudre)\b", RegexOptions.CultureInvariant)
        && !name.Contains("cuit", StringComparison.Ordinal);

    private static IEnumerable<Item> Candidates(string query)
    {
        string[] words = query.Split(' ').Where(word => !Ignored.Contains(word)).Select(Stem).ToArray();
        if (words.Length == 0) return [];
        return Catalog.Value.Where(item => item.Stems.Any(key =>
                words.All(word => (" " + key + " ").Contains(" " + word + " ", StringComparison.Ordinal))))
            .OrderBy(item => !(item.Stems[0] + " ").StartsWith(words[0] + " ", StringComparison.Ordinal))
            .ThenBy(item => !item.Name.Contains("aliment moyen", StringComparison.Ordinal))
            .ThenBy(item => item.Name.Length);
    }

    private static string Stem(string normalized) => string.Join(' ', normalized.Split(' ')
        .Select(word => word.Length > 3 && word[^1] is ('s' or 'x') ? word[..^1] : word));

    internal static string Normalize(string value)
    {
        string plain = string.Concat(value.Normalize(NormalizationForm.FormD)
            .Where(character => CharUnicodeInfo.GetUnicodeCategory(character) != UnicodeCategory.NonSpacingMark));
        return Regex.Replace(plain.ToLowerInvariant().Replace("œ", "oe"), "[^a-z0-9]+", " ").Trim();
    }
}
