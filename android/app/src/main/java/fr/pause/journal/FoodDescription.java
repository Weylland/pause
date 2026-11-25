package fr.pause.journal;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FoodDescription {
    private static final String AMOUNT = "(\\d+(?:[.,]\\d+)?)\\s*(kg|grammes?|g)\\b";
    private static final Pattern PREFIX = Pattern.compile("^" + AMOUNT + "\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUFFIX = Pattern.compile("^(.+?)\\s+" + AMOUNT + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARATOR = Pattern.compile("\\s+(?:et|avec)\\s+|[;\\n+]|(?<!\\d),|,(?!\\d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTICLE = Pattern.compile("^(?:(?:un\\s+)?peu\\s+de\\s+|(?:de la|des|du|de|une|un|la|le|les)\\s+|(?:de\\s+)?[dl]['’]\\s*)+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CORRECTION = Pattern.compile("^(.+?)\\s*:\\s*" + AMOUNT + "$", Pattern.CASE_INSENSITIVE);

    record Reference(String name, double per100, List<String> keys) {}
    record Portion(String name, Double grams, Reference reference, List<Reference> options) {}

    private FoodDescription() {}

    static List<Portion> parse(String phrase, List<Reference> table) {
        List<Portion> portions = new ArrayList<>();
        String spaced = phrase.replace('\u00a0', ' ').replace('\u202f', ' ').trim();
        String[] lines = spaced.split("\\n");
        StringBuilder description = new StringBuilder(lines[0]);
        var overrides = new java.util.HashMap<String, Double>();
        for (int i = 1; i < lines.length; i++) {
            Matcher correction = CORRECTION.matcher(lines[i].trim());
            if (correction.matches()) overrides.put(normalize(foodName(correction.group(1))), grams(correction.group(2), correction.group(3)));
            else description.append('\n').append(lines[i]);
        }
        for (String piece : SEPARATOR.split(description.toString())) {
            String name = piece.trim();
            if (name.isEmpty()) continue;
            Double grams = null;
            Matcher leading = PREFIX.matcher(name), trailing = SUFFIX.matcher(name);
            if (leading.matches()) {
                grams = grams(leading.group(1), leading.group(2));
                name = leading.group(3);
            } else if (trailing.matches()) {
                grams = grams(trailing.group(2), trailing.group(3));
                name = trailing.group(1);
            }
            name = foodName(name);
            if (name.isEmpty()) continue;
            String query = normalize(name);
            if (overrides.get(query) != null) grams = overrides.get(query);
            List<Reference> exact = table.stream().filter(item -> item.keys().contains(query)).collect(java.util.stream.Collectors.toList());
            List<Reference> options = exact.isEmpty() ? candidates(table, query) : exact;
            portions.add(new Portion(name, grams, exact.size() == 1 ? exact.get(0) : null, options));
        }
        return portions;
    }

    private static String foodName(String name) { return ARTICLE.matcher(name.trim()).replaceFirst("").trim(); }

    private static List<Reference> candidates(List<Reference> table, String query) {
        if (query.length() < 2) return java.util.Collections.emptyList();
        String[] words = query.split(" ");
        return table.stream().filter(item -> Arrays.stream(words).allMatch(word ->
                        (" " + item.keys().get(0) + " ").contains(" " + word + " ")))
                .sorted(Comparator.comparingInt(item -> item.name().length()))
                .limit(8).collect(java.util.stream.Collectors.toList());
    }

    private static Double grams(String value, String units) {
        double quantity = Double.parseDouble(value.replace(',', '.')) * (units.equalsIgnoreCase("kg") ? 1000 : 1);
        return Double.isFinite(quantity) && quantity > 0 && quantity <= 10000 ? quantity : null;
    }

    static List<Reference> load(Context context) throws IOException {
        List<Reference> table = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("ciqual.tsv"), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("#")) continue;
                String[] columns = line.split("\\t", -1);
                if (columns.length != 3) continue;
                var keys = new ArrayList<String>();
                keys.add(normalize(columns[0]));
                for (String alias : columns[2].split("\\|")) if (!alias.isEmpty()) keys.add(normalize(alias));
                table.add(new Reference(columns[0], Double.parseDouble(columns[1]), keys));
            }
        }
        return table;
    }

    static String normalize(String value) {
        return Normalizer.normalize(Objects.requireNonNull(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replace("œ", "oe").replaceAll("[^a-z0-9]+", " ").trim();
    }
}
