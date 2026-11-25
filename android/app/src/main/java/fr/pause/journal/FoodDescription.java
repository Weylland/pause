package fr.pause.journal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class FoodDescription {
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String DIGITS = "[0-9]+\\s*/\\s*[0-9]+|[0-9]+(?:[.,][0-9]+)?|[½¼¾]";
    private static final String WORDS = "(?:une?\\s+)?demie?[\\s-]+|(?:une?|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze)\\s+";
    private static final String UNIT = "(kilogrammes?|kilos?|kg|grammes?|gr|g|litres?|l|dl|cl|ml)(?![\\p{L}'’])\\.?";
    private static final Pattern PREFIX = Pattern.compile("^((?:" + DIGITS + ")\\s*|" + WORDS + ")" + UNIT + "\\s*(.+)$", FLAGS);
    private static final Pattern SUFFIX = Pattern.compile("^(.+?)(?:\\s*:\\s*|\\s+(de\\s+)?)(" + DIGITS + ")\\s*" + UNIT + "$", FLAGS);
    private static final Pattern COUNT = Pattern.compile("^(?:(" + DIGITS + ")(?:\\s*([x×])(?=[\\s0-9])\\s*|\\s+)|(" + WORDS + "))(.+)$", FLAGS);
    private static final Pattern CORRECTION = Pattern.compile("^.+?\\s*:\\s*(?:" + DIGITS + ")\\s*" + UNIT + "$", FLAGS);
    private static final Pattern SEPARATOR = Pattern.compile("\\s+(?:et|avec|puis)\\s+|[;+&•]|(?<![0-9]),|,(?![0-9])", FLAGS);
    private static final Pattern LITTLE = Pattern.compile("^(?:(?:un\\s+)?(?:petit\\s+)?peu|beaucoup)\\s+(?:de\\s+|d['’]\\s*)", FLAGS);
    private static final Pattern ARTICLE = Pattern.compile("^(?:(?:des|du|de|une|un|la|les|le|quelques)\\s+|[dl]['’]\\s*)+", FLAGS);
    private static final Pattern CONTAINER = Pattern.compile("^(?:(?:petite?s?|grande?s?|gros(?:se)?s?|bonne?s?|bons?)\\s+)?"
            + "(?:verres?|tasses?|bols?|parts?|tranches?|portions?|assiettes?|morceaux?|poign[ée]es?|pots?|canettes?|bouteilles?|carr[ée]s?"
            + "|rondelles?|pinc[ée]es?|sachets?|paquets?|barquettes?|briques?|louches?|gousses?"
            + "|cuill(?:[èe]res?|[ée]r[ée]es?)(?:\\s+[àa]\\s+(?:soupe|caf[ée]))?|c\\.?\\s*[àa]\\.?\\s*[sc]\\.?)\\s+(?:de\\s+|du\\s+|des\\s+|d['’]\\s*)", FLAGS);
    private static final List<String> NUMBERS = Arrays.asList("un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze");
    private static final List<String> IGNORED = Arrays.asList("a", "au", "aux", "d", "de", "des", "du", "en", "et", "l", "la", "le", "les", "ou");

    record Reference(String name, double per100, List<String> keys, List<String> stems, Double unit) {}
    record Portion(String name, Double grams, boolean usual, Reference reference, List<Reference> options) {}

    private FoodDescription() {}

    static List<Portion> parse(String phrase, List<Reference> table) {
        List<Portion> portions = new ArrayList<>();
        String text = Normalizer.normalize(phrase, Normalizer.Form.NFC).replace(' ', ' ').replace(' ', ' ').replace("\r", "");
        for (String line : text.split("\\n")) {
            String whole = clean(line);
            Portion correction = CORRECTION.matcher(whole).matches() ? portion(whole, table) : null;
            if (correction != null && correction.grams() != null && portions.stream().anyMatch(item -> sameName(item, correction))) {
                portions.replaceAll(item -> sameName(item, correction)
                        ? new Portion(item.name(), correction.grams(), false, item.reference(), item.options()) : item);
                continue;
            }
            for (String piece : SEPARATOR.split(line)) {
                Portion portion = portion(clean(piece), table);
                if (portion != null) portions.add(portion);
            }
        }
        return portions;
    }

    private static boolean sameName(Portion first, Portion second) {
        return normalize(first.name()).equals(normalize(second.name()));
    }

    private static String clean(String piece) {
        return piece.replaceAll("[()\\[\\]]", " ").replaceAll("\\s+", " ").replaceAll("^[\\s*–—-]+|[\\s.!?…:]+$", "");
    }

    private static Portion portion(String piece, List<Reference> table) {
        Double grams = null, count = null;
        String name = LITTLE.matcher(piece).replaceFirst("");
        Matcher prefix = PREFIX.matcher(name);
        if (prefix.matches()) {
            grams = grams(number(prefix.group(1)), prefix.group(2));
            name = prefix.group(3);
        } else {
            boolean times = false;
            Matcher counted = COUNT.matcher(name);
            if (counted.matches()) {
                count = number(counted.group(1) != null ? counted.group(1) : counted.group(3));
                times = counted.group(2) != null;
                name = counted.group(4);
            }
            Matcher repeated = PREFIX.matcher(name), suffix = SUFFIX.matcher(name);
            if (times && repeated.matches()) {
                grams = grams(count * number(repeated.group(1)), repeated.group(2));
                name = repeated.group(3);
                count = null;
            } else if (suffix.matches()) {
                double each = suffix.group(2) != null && count != null ? count : 1;
                grams = grams(each * number(suffix.group(3)), suffix.group(4));
                name = suffix.group(1);
                count = null;
            }
        }
        name = ARTICLE.matcher(name).replaceFirst("");
        Matcher container = CONTAINER.matcher(name);
        if (container.find()) {
            count = null;
            name = ARTICLE.matcher(container.replaceFirst("")).replaceFirst("");
        }
        name = (name.length() > 90 ? name.substring(0, 90) : name).trim();
        String query = normalize(name);
        if (query.length() < 2) return null;
        List<Reference> exact = exact(table, query);
        boolean usual = false;
        if (count != null) {
            List<Double> weights = exact.stream().map(Reference::unit).filter(Objects::nonNull).distinct().collect(Collectors.toList());
            if (weights.size() == 1) grams = grams(count * weights.get(0), "g");
            usual = grams != null;
        }
        List<Reference> options = Stream.concat(exact.stream(), candidates(table, query)).distinct().limit(8).collect(Collectors.toList());
        return new Portion(name, grams, usual, exact.size() == 1 ? exact.get(0) : null, options);
    }

    private static List<Reference> exact(List<Reference> table, String query) {
        List<Reference> exact = table.stream().filter(item -> item.keys().contains(query)).collect(Collectors.toList());
        if (!exact.isEmpty()) return exact;
        String stem = stem(query);
        return table.stream().filter(item -> item.stems().contains(stem)).collect(Collectors.toList());
    }

    private static Stream<Reference> candidates(List<Reference> table, String query) {
        List<String> words = Arrays.stream(query.split(" ")).filter(word -> !IGNORED.contains(word))
                .map(FoodDescription::stem).collect(Collectors.toList());
        if (words.isEmpty()) return Stream.empty();
        return table.stream().filter(item -> item.stems().stream().anyMatch(key ->
                        words.stream().allMatch(word -> (" " + key + " ").contains(" " + word + " "))))
                .sorted(Comparator.<Reference, Boolean>comparing(item -> !(item.stems().get(0) + " ").startsWith(words.get(0) + " "))
                        .thenComparing(item -> !item.name().contains("aliment moyen"))
                        .thenComparingInt(item -> item.name().length()));
    }

    private static double number(String value) {
        String text = value.replaceAll("[\\s-]+$", "").toLowerCase(Locale.ROOT);
        if (text.contains("demi")) return .5;
        if (text.equals("une")) return 1;
        if (NUMBERS.contains(text)) return NUMBERS.indexOf(text) + 1;
        int quarters = "¼½¾".indexOf(text);
        if (quarters >= 0) return (quarters + 1) / 4.0;
        String[] parts = text.replace(',', '.').split("/");
        double amount = Double.parseDouble(parts[0].trim());
        return parts.length == 1 ? amount : amount / Double.parseDouble(parts[1].trim());
    }

    private static Double grams(double value, String unit) {
        String name = unit.toLowerCase(Locale.ROOT);
        double quantity = value * (name.startsWith("k") || name.startsWith("l") ? 1000 : name.equals("dl") ? 100 : name.equals("cl") ? 10 : 1);
        return quantity >= .0001 && quantity <= 10000 ? quantity : null;
    }

    static List<Reference> load(InputStream source) throws IOException {
        List<Reference> table = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("#")) continue;
                String[] columns = line.split("\\t", -1);
                if (columns.length < 3) continue;
                List<String> keys = new ArrayList<>();
                keys.add(normalize(columns[0]));
                for (String alias : columns[2].split("\\|")) if (!alias.isEmpty()) keys.add(normalize(alias));
                table.add(new Reference(columns[0], Double.parseDouble(columns[1]), keys,
                        keys.stream().map(FoodDescription::stem).collect(Collectors.toList()),
                        columns.length > 3 ? Double.valueOf(columns[3]) : null));
            }
        }
        return table;
    }

    private static String stem(String normalized) {
        return Arrays.stream(normalized.split(" ")).map(word -> word.length() > 3 && (word.endsWith("s") || word.endsWith("x"))
                ? word.substring(0, word.length() - 1) : word).collect(Collectors.joining(" "));
    }

    static String normalize(String value) {
        return Normalizer.normalize(Objects.requireNonNull(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replace("œ", "oe").replaceAll("[^a-z0-9]+", " ").trim();
    }
}
