using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Pause;

internal sealed record EstimatedFood(string Name, double? Grams, double? Per100, string? Source = null, bool Usual = false);

internal sealed record MealEstimate(string Source, EstimatedFood[] Foods)
{
    private static readonly HttpClient Http = new(new HttpClientHandler { AllowAutoRedirect = false })
    {
        Timeout = TimeSpan.FromSeconds(25), MaxResponseContentBufferSize = 262144
    };
    internal const double UnknownPer100 = 150;
    private const RegexOptions Flags = RegexOptions.IgnoreCase | RegexOptions.CultureInvariant;
    private const string Digits = @"[0-9]+\s*/\s*[0-9]+|[0-9]+(?:[.,][0-9]+)?|[½¼¾]";
    private const string Words = @"(?:une?\s+)?demie?[\s-]+|(?:une?|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze)\s+";
    private const string Unit = @"(kilogrammes?|kilos?|kg|grammes?|gr|g|litres?|l|dl|cl|ml)(?![\p{L}'’])\.?";
    private static readonly Regex Prefix = new("^((?:" + Digits + @")\s*|" + Words + ")" + Unit + @"\s*(.+)$", Flags);
    private static readonly Regex Suffix = new(@"^(.+?)(?:\s*:\s*|\s+(de\s+)?)(" + Digits + @")\s*" + Unit + "$", Flags);
    private static readonly Regex Count = new("^(?:(" + Digits + @")(?:\s*([x×])(?=[\s0-9])\s*|\s+)|(" + Words + "))(.+)$", Flags);
    private static readonly Regex Correction = new(@"^.+?\s*:\s*(?:" + Digits + @")\s*" + Unit + "$", Flags);
    private static readonly Regex Separator = new(@"\s+(?:et|avec|puis)\s+|[;+&•]|(?<![0-9]),|,(?![0-9])", Flags);
    private static readonly Regex Told = new(@"^(?:(?:hier|aujourd['’]hui|(?:[àa]\s+|(?:hier|ce|le)\s+)?(?:matin|midi|soir)|au\s+(?:petit[\s-]+d[ée]jeuner|d[ée]jeuner|d[îi]ner|go[ûu]ter))(?:[\s,:]+|$))?(?:(?:j['’\s]?\s*ai\s+)?(?:mang[ée]+|bu|pris|grignot[ée]+)\s+)?", Flags);
    private static readonly Regex Little = new(@"^((?:un\s+)?(?:petit\s+)?peu|beaucoup)\s+(?:de\s+|d['’]\s*)", Flags);
    private static readonly Regex Article = new(@"^(?:(?:des|du|de|une|un|la|les|le|quelques)\s+|[dl]['’]\s*)+", Flags);
    private const string Sizes = "(petite?s?|grande?s?|gros(?:se)?s?|bonne?s?|bons?)";
    private static readonly Regex Sized = new("^" + Sizes + @"\s+(.+)$", Flags);
    private static readonly Regex Container = new("^(?:" + Sizes + @"\s+)?"
        + "(verres?|tasses?|bols?|parts?|tranches?|portions?|assiettes?|morceaux?|poign[ée]es?|pots?|canettes?|bouteilles?|carr[ée]s?"
        + "|rondelles?|pinc[ée]es?|sachets?|paquets?|barquettes?|briques?|louches?|gousses?"
        + @"|cuill(?:[èe]res?|[ée]r[ée]es?)(?:\s+[àa]\s+(?:soupe|caf[ée]))?|c\.?\s*[àa]\.?\s*(?:soupe|caf[ée]|[sc]\.?))\s+(?:de\s+|du\s+|des\s+|d['’]\s*)", Flags);
    private static readonly string[] Numbers = ["un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze"];
    private static readonly Regex Barcode = new(@"^Code-barres (\d{8}|\d{12,14})(?:\r?\n(.+))?$", RegexOptions.IgnoreCase);
    private static string CacheFile(MealDraft draft) => Path.Combine(JournalStore.DirectoryPath, "estimates", draft.Id + ".json");
    private static string Fingerprint(MealDraft draft) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("ciqual-v5\n" + draft.Description)));
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
            if (product is not null)
                return new("Open Food Facts", [product with { Grams = FoodReference.Portion(null, product.Name, FoodReference.Match(product.Name).Guess), Usual = true }]);
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
        var portions = new List<(string Name, double? Grams, bool Usual)>();
        string text = description.Normalize(NormalizationForm.FormC).Replace('\u00a0', ' ').Replace('\u202f', ' ').Replace("\r", "");
        foreach (string line in text.Split('\n'))
        {
            string whole = Clean(line);
            if (Correction.IsMatch(whole) && ParsePortion(whole) is { Grams: not null } correction
                && portions.Any(item => SameName(item.Name, correction.Name)))
            {
                portions = portions.Select(item => SameName(item.Name, correction.Name)
                    ? (item.Name, correction.Grams, false) : item).ToList();
                continue;
            }
            foreach (string piece in Separator.Split(line).SelectMany(piece => Parts(Told.Replace(Clean(piece), "", 1))))
                if (ParsePortion(piece) is { } portion) portions.Add(portion);
        }
        if (portions.Count > 20) return [];
        return portions.Select(portion =>
        {
            FoodReference.Result match = FoodReference.Match(portion.Name);
            return new EstimatedFood(Shorten(match.Reference?.Name ?? portion.Name), portion.Grams, match.Guess?.Per100,
                match.Guess is null ? null : "Ciqual", portion.Usual);
        }).ToArray();
    }

    private static string[] Parts(string piece)
    {
        if (ParsePortion(piece) is not { } portion) return [piece];
        int at = piece.LastIndexOf(portion.Name, StringComparison.Ordinal);
        if (at < 0) return [piece];
        string[] names = FoodReference.Identify(portion.Name);
        if (names.Length > 1 && !portion.Usual) return [piece];
        return [piece[..at] + names[0] + piece[(at + portion.Name.Length)..], .. names[1..]];
    }

    private static (string Name, double? Grams, bool Usual)? ParsePortion(string piece)
    {
        double? grams = null, count = null;
        Match little = Little.Match(piece);
        double factor = !little.Success ? 1 : little.Groups[1].Value.StartsWith("b", StringComparison.OrdinalIgnoreCase) ? 1.5 : .5;
        bool measured = false;
        string name = piece[little.Length..];
        Match prefix = Prefix.Match(name);
        if (prefix.Success)
        {
            measured = true;
            grams = ReadGrams(Number(prefix.Groups[1].Value), prefix.Groups[2].Value);
            name = prefix.Groups[3].Value;
        }
        else
        {
            bool times = false;
            Match counted = Count.Match(name);
            if (counted.Success)
            {
                count = Number(counted.Groups[1].Success ? counted.Groups[1].Value : counted.Groups[3].Value);
                times = counted.Groups[2].Success;
                name = counted.Groups[4].Value;
            }
            Match repeated = Prefix.Match(name), suffix = Suffix.Match(name);
            if (times && repeated.Success)
            {
                grams = ReadGrams(count!.Value * Number(repeated.Groups[1].Value), repeated.Groups[2].Value);
                name = repeated.Groups[3].Value;
                measured = true;
            }
            else if (suffix.Success)
            {
                double each = suffix.Groups[2].Success && count is double items ? items : 1;
                grams = ReadGrams(each * Number(suffix.Groups[3].Value), suffix.Groups[4].Value);
                name = suffix.Groups[1].Value;
                measured = true;
            }
        }
        name = Article.Replace(name, "", 1);
        string? container = null;
        Match held = Container.Match(name), sized = Sized.Match(name);
        if (held.Success)
        {
            container = FoodReference.Container(held.Groups[2].Value);
            factor *= Size(held.Groups[1].Value);
            name = Article.Replace(name[held.Length..], "", 1);
        }
        else if (sized.Success && !FoodReference.Known(name))
        {
            factor *= Size(sized.Groups[1].Value);
            name = Article.Replace(sized.Groups[2].Value, "", 1);
        }
        name = Shorten(name).Trim();
        if (FoodReference.Normalize(name).Length < 2) return null;
        if (measured) return (name, grams, false);
        FoodReference.Result match = FoodReference.Match(name);
        double weight = container is null && count.HasValue && match.Unit is double unit ? count.Value * unit
            : FoodReference.Portion(container, name, match.Guess) * (container is not null || count is <= 3 ? count ?? 1 : 1);
        return (name, ReadGrams(Math.Floor(weight * factor * 10 + .5) / 10, "g"), true);
    }

    private static double Size(string adjective) => adjective.Length == 0 || char.ToLowerInvariant(adjective[0]) == 'b' ? 1
        : char.ToLowerInvariant(adjective[0]) == 'p' ? .7 : 1.3;

    private static bool SameName(string first, string second) => FoodReference.Normalize(first) == FoodReference.Normalize(second);

    private static string Shorten(string name) => name.Length <= 90 ? name : name[..90];

    private static string Clean(string piece) => Regex.Replace(Regex.Replace(Regex.Replace(piece, @"[()\[\]]", " "), @"\s+", " "),
        @"^[\s*–—-]+|[\s.!?…:]+$", "");

    private static double Number(string value)
    {
        string text = Regex.Replace(value, @"[\s-]+$", "").ToLowerInvariant();
        if (text.Contains("demi", StringComparison.Ordinal)) return .5;
        if (text == "une") return 1;
        if (Array.IndexOf(Numbers, text) is >= 0 and int word) return word + 1;
        if (text.Length == 1 && "¼½¾".IndexOf(text[0]) is >= 0 and int quarters) return (quarters + 1) / 4.0;
        string[] parts = text.Replace(',', '.').Split('/');
        double amount = double.Parse(parts[0], CultureInfo.InvariantCulture);
        return parts.Length == 1 ? amount : amount / double.Parse(parts[1], CultureInfo.InvariantCulture);
    }

    private static double? ReadGrams(double value, string unit)
    {
        string name = unit.ToLowerInvariant();
        double grams = value * (name[0] is 'k' or 'l' ? 1000 : name == "dl" ? 100 : name == "cl" ? 10 : 1);
        return grams is >= .0001 and <= 10000 ? grams : null;
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
