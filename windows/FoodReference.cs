using System.Globalization;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace Pause;

internal static class FoodReference
{
    internal sealed record Item(string Name, double Per100, string[] Keys);
    private static readonly Lazy<Item[]> Catalog = new(Load);

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
            if (fields.Length != 3 || !double.TryParse(fields[1], NumberStyles.Float, CultureInfo.InvariantCulture, out double kcal)) continue;
            string[] keys = new[] { fields[0] }.Concat(fields[2].Split('|', StringSplitOptions.RemoveEmptyEntries))
                .Select(Normalize).ToArray();
            items.Add(new Item(fields[0], kcal, keys));
        }
        return items.ToArray();
    }

    internal static Item[] Search(string name)
    {
        string query = Normalize(name);
        if (query.Length < 2) return [];
        Item[] exact = Catalog.Value.Where(item => item.Keys.Contains(query)).ToArray();
        if (exact.Length > 0) return exact.Take(8).ToArray();
        string[] words = query.Split(' ');
        return Catalog.Value.Where(item => words.All(word => (" " + item.Keys[0] + " ").Contains(" " + word + " ", StringComparison.Ordinal)))
            .OrderBy(item => item.Name.Length).Take(8).ToArray();
    }

    private static string Normalize(string value)
    {
        string plain = string.Concat(value.Normalize(NormalizationForm.FormD)
            .Where(character => CharUnicodeInfo.GetUnicodeCategory(character) != UnicodeCategory.NonSpacingMark));
        return Regex.Replace(plain.ToLowerInvariant().Replace("œ", "oe"), "[^a-z0-9]+", " ").Trim();
    }
}
