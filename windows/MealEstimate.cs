using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Pause;

internal sealed record EstimatedFood(string Name, double? Grams, double? Per100);

internal sealed record MealEstimate(string Source, EstimatedFood[] Foods)
{
    private static readonly HttpClient Http = new(new HttpClientHandler { AllowAutoRedirect = false })
    {
        Timeout = TimeSpan.FromSeconds(25), MaxResponseContentBufferSize = 262144
    };
    private const string Amount = @"(\d+(?:[.,]\d+)?)\s*(kg|grammes?|g)\b";
    private static readonly Regex Prefix = new("^" + Amount + @"\s*(.+)$", RegexOptions.IgnoreCase);
    private static readonly Regex Suffix = new(@"^(.+?)\s*[:]?\s+" + Amount + "$", RegexOptions.IgnoreCase);
    private static readonly Regex Separator = new(@"\s+(?:et|avec)\s+|[;\n+]|(?<!\d),|,(?!\d)", RegexOptions.IgnoreCase);
    private static readonly Regex Article = new(@"^(?:(?:(?:un\s+)?peu\s+de\s+|(?:de la|des|du|de|une|un|la|le|les)\s+|(?:de\s+)?[dl]['’]\s*))+", RegexOptions.IgnoreCase);
    private static readonly Regex Barcode = new(@"^Code-barres (\d{8}|\d{12,14})(?:\r?\n(.+))?$", RegexOptions.IgnoreCase);
    private static string CacheFile(MealDraft draft) => Path.Combine(JournalStore.DirectoryPath, "estimates", draft.Id + ".json");
    private static string Fingerprint(MealDraft draft) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("ciqual-v2\n" + draft.Description)));
    private sealed record Cached(string Fingerprint, MealEstimate Estimate);

    internal static MealEstimate? Read(MealDraft draft)
    {
        try
        {
            var cached = JsonSerializer.Deserialize<Cached>(File.ReadAllText(CacheFile(draft)));
            return cached?.Fingerprint == Fingerprint(draft) ? cached.Estimate : null;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException) { return null; }
    }

    internal void Remember(MealDraft draft)
    {
        string path = CacheFile(draft);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        string temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllText(temp, JsonSerializer.Serialize(new Cached(Fingerprint(draft), this)));
            File.Move(temp, path, overwrite: true);
        }
        finally { File.Delete(temp); }
    }

    internal static void Forget(MealDraft draft)
    {
        try { File.Delete(CacheFile(draft)); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
    }

    internal static async Task<MealEstimate?> MakeAsync(MealDraft draft, CancellationToken cancellation)
    {
        string description = draft.Description;
        Match scan = Barcode.Match(description);
        if (scan.Success)
        {
            EstimatedFood? product = null;
            try { product = await LookupBarcodeAsync(scan.Groups[1].Value, scan.Groups[2].Value, cancellation); }
            catch (Exception error) when (!cancellation.IsCancellationRequested && error is HttpRequestException or JsonException or OperationCanceledException) { }
            if (product is not null) return new("Open Food Facts", [product]);
            description = scan.Groups[2].Value.Trim();
            if (description.Length == 0) return null;
        }
        EstimatedFood[] local = ParseLocal(description);
        return local.Any(food => food.Per100.HasValue) ? new("Ciqual", local) : null;
    }

    internal static MealEstimate Manual(MealDraft draft)
    {
        Match scan = Barcode.Match(draft.Description);
        string description = scan.Success ? scan.Groups[2].Value.Trim() : draft.Description;
        EstimatedFood[] foods = ParseLocal(description);
        return new("Saisie", foods.Length == 0 ? [new(description.Length is > 0 and <= 90 ? description : "", null, null)] : foods);
    }

    internal static EstimatedFood[] ParseLocal(string description)
    {
        string[] lines = description.Replace('\u00a0', ' ').Replace('\u202f', ' ').Split('\n', StringSplitOptions.TrimEntries);
        // Android appends edits as “aliment : quantité” after the original description.
        string first = lines[0];
        var overrides = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
        foreach (string line in lines.Skip(1))
        {
            Match match = Regex.Match(line, "^(.+?)\\s*:\\s*" + Amount + "$", RegexOptions.IgnoreCase);
            if (!match.Success || ReadGrams(match.Groups[2].Value, match.Groups[3].Value) is not double grams) return [];
            overrides[FoodName(match.Groups[1].Value)] = grams;
        }
        string[] parts = Separator.Split(first);
        if (parts.Length > 20) return [];
        var foods = new List<EstimatedFood>();
        foreach (string part in parts)
        {
            string name = part.Trim();
            if (name.Length == 0) continue;
            double? grams = null;
            Match match = Prefix.Match(name);
            if (match.Success)
            {
                grams = ReadGrams(match.Groups[1].Value, match.Groups[2].Value);
                name = match.Groups[3].Value;
            }
            else if ((match = Suffix.Match(name)).Success)
            {
                grams = ReadGrams(match.Groups[2].Value, match.Groups[3].Value);
                name = match.Groups[1].Value;
            }
            name = FoodName(name);
            if (name.Length is < 2 or > 90) return [];
            if (overrides.TryGetValue(name, out double corrected)) grams = corrected;
            FoodReference.Item? item = FoodReference.Exact(name);
            foods.Add(new(item?.Name ?? name, grams, item?.Per100));
        }
        return foods.ToArray();
    }

    private static string FoodName(string name) => Article.Replace(name.Trim(), "").Trim();

    private static double? ReadGrams(string text, string unit)
    {
        if (!double.TryParse(text.Replace(',', '.'), CultureInfo.InvariantCulture, out double value)) return null;
        if (unit.Equals("kg", StringComparison.OrdinalIgnoreCase)) value *= 1000;
        return value is >= .0001 and <= 10000 ? value : null;
    }

    private static async Task<EstimatedFood?> LookupBarcodeAsync(string barcode, string knownName, CancellationToken cancellation)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get,
            $"https://world.openfoodfacts.org/api/v2/product/{barcode}.json?fields=product_name_fr,product_name,nutriments");
        request.Headers.UserAgent.ParseAdd("Pause-Windows/1.5 (personal journal)");
        using HttpResponseMessage response = await Http.SendAsync(request, cancellation);
        if (response.StatusCode == System.Net.HttpStatusCode.NotFound) return null;
        response.EnsureSuccessStatusCode();
        using JsonDocument data = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellation));
        if (!data.RootElement.TryGetProperty("product", out JsonElement product)
            || !product.TryGetProperty("nutriments", out var nutrients)
            || !nutrients.TryGetProperty("energy-kcal_100g", out var energy)) return null;
        double kcal = energy.ValueKind == JsonValueKind.Number ? energy.GetDouble()
            : energy.ValueKind == JsonValueKind.String && double.TryParse(energy.GetString(), CultureInfo.InvariantCulture, out double parsed)
                ? parsed : double.NaN;
        if (!(kcal is >= 0 and <= 1000)) return null;
        string name = new[] { "product_name_fr", "product_name" }.Select(field =>
            product.TryGetProperty(field, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString()?.Trim() : null)
            .FirstOrDefault(name => !string.IsNullOrWhiteSpace(name)) ?? knownName.Trim();
        if (name.Length == 0) name = "Produit " + barcode;
        return new(name.Length <= 90 ? name : name[..90], null, kcal);
    }
}
