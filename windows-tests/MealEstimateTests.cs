using System.Globalization;
using System.IO;
using Xunit;

namespace Pause.Tests;

public class MealEstimateTests
{
    public static TheoryData<string, string> Meals()
    {
        var meals = new TheoryData<string, string>();
        foreach (string line in File.ReadLines(Path.Combine(AppContext.BaseDirectory, "repas.tsv")))
        {
            string[] columns = line.Split('\t', 2);
            meals.Add(columns[0], columns[1]);
        }
        return meals;
    }

    [Theory]
    [MemberData(nameof(Meals))]
    public void SharedMeals(string phrase, string expected)
    {
        EstimatedFood[] foods = MealEstimate.ParseLocal(phrase.Replace("\\n", "\n"));
        string[] portions = expected.Split('\t');
        Assert.Equal(string.Join('\t', portions.Select(Simplify)), string.Join('\t', foods.Select(Describe)));
    }

    private static string Simplify(string portion)
    {
        string[] parts = portion.Split('|');
        return parts[2].StartsWith('=') ? $"{parts[2][1..]}|{parts[1]}|=" : portion;
    }

    private static string Describe(EstimatedFood food)
    {
        string grams = food.Grams is double value ? value.ToString("0.####", CultureInfo.InvariantCulture) + (food.Usual ? "*" : "") : "?";
        if (food.Per100.HasValue) return $"{food.Name}|{grams}|=";
        FoodReference.Item[] options = FoodReference.Match(food.Name).Options;
        return $"{food.Name}|{grams}|{(options.Length == 0 ? "-" : "?" + options[0].Name)}";
    }
}
