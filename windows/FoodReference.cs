using System.Globalization;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace Pause;

internal static class FoodReference
{
    internal sealed record Item(string Name, double Per100, string[] Keys, string[] Stems, double? Unit);
    internal sealed record Result(Item? Reference, Item[] Options, double? Unit);
    private static readonly Lazy<Item[]> Catalog = new(Load);
    private static readonly HashSet<string> Ignored = ["a", "au", "aux", "d", "de", "des", "du", "en", "et", "l", "la", "le", "les", "ou"];

    private static Item[] Load()
    {
        using Stream stream = typeof(FoodReference).Assembly.GetManifestResourceStream("Pause.ciqual.tsv")
            ?? throw new IOException("Référence Ciqual indisponible.");
        using var reader = new StreamReader(stream, Encoding.UTF8);
        var items = new List<Item>();
        while (reader.ReadLine() is string line)
        {
            if (line.StartsWith('#')) continue;
            string[] fields = line.Split('\t');
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
        if (query.Length < 2) return new(null, [], null);
        Item[] exact = Catalog.Value.Where(item => item.Keys.Contains(query)).ToArray();
        if (exact.Length == 0) exact = Catalog.Value.Where(item => item.Stems.Contains(Stem(query))).ToArray();
        double[] weights = exact.Where(item => item.Unit.HasValue).Select(item => item.Unit!.Value).Distinct().ToArray();
        return new(exact.Length == 1 ? exact[0] : null, exact.Concat(Candidates(query)).Distinct().Take(8).ToArray(),
            weights.Length == 1 ? weights[0] : null);
    }

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
