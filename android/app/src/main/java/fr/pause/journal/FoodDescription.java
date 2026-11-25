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
    private static final Pattern LITTLE = Pattern.compile("^((?:un\\s+)?(?:petit\\s+)?peu|beaucoup)\\s+(?:de\\s+|d['’]\\s*)", FLAGS);
    private static final Pattern ARTICLE = Pattern.compile("^(?:(?:des|du|de|une|un|la|les|le|quelques)\\s+|[dl]['’]\\s*)+", FLAGS);
    private static final String SIZES = "(petite?s?|grande?s?|gros(?:se)?s?|bonne?s?|bons?)";
    private static final Pattern SIZED = Pattern.compile("^" + SIZES + "\\s+(.+)$", FLAGS);
    private static final Pattern RAW = Pattern.compile("\\b(?:crue?s?|secs?|seches?|bebe|poudre)\\b");
    private static final Pattern CONTAINER = Pattern.compile("^(?:" + SIZES + "\\s+)?"
            + "(verres?|tasses?|bols?|parts?|tranches?|portions?|assiettes?|morceaux?|poign[ée]es?|pots?|canettes?|bouteilles?|carr[ée]s?"
            + "|rondelles?|pinc[ée]es?|sachets?|paquets?|barquettes?|briques?|louches?|gousses?"
            + "|cuill(?:[èe]res?|[ée]r[ée]es?)(?:\\s+[àa]\\s+(?:soupe|caf[ée]))?|c\\.?\\s*[àa]\\.?\\s*[sc]\\.?)\\s+(?:de\\s+|du\\s+|des\\s+|d['’]\\s*)", FLAGS);
    private static final List<String> NUMBERS = Arrays.asList("un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze");
    private static final List<String> IGNORED = Arrays.asList("a", "au", "aux", "d", "de", "des", "du", "en", "et", "l", "la", "le", "les", "ou");
    private static final List<String> EXTRAS = Arrays.asList("bio", "chaud", "chaude", "fait", "faite", "frais", "fraiche", "froid", "froide", "maison");

    record Reference(String name, double per100, List<String> keys, List<String> stems, Double unit) {}
    record Serving(String container, List<String> foods, double grams) {}
    record Table(List<Reference> references, List<Serving> servings) {}
    record Portion(String name, Double grams, boolean usual, Reference reference, Reference guess, List<Reference> options) {}

    private FoodDescription() {}

    static List<Portion> parse(String phrase, Table table) {
        List<Portion> portions = new ArrayList<>();
        String text = Normalizer.normalize(phrase, Normalizer.Form.NFC).replace(' ', ' ').replace(' ', ' ').replace("\r", "");
        for (String line : text.split("\\n")) {
            String whole = clean(line);
            Portion correction = CORRECTION.matcher(whole).matches() ? portion(whole, table) : null;
            if (correction != null && correction.grams() != null && portions.stream().anyMatch(item -> sameName(item, correction))) {
                portions.replaceAll(item -> sameName(item, correction)
                        ? new Portion(item.name(), correction.grams(), false, item.reference(), item.guess(), item.options()) : item);
                continue;
            }
            for (String piece : SEPARATOR.split(line)) for (String part : parts(clean(piece), table)) {
                Portion portion = portion(part, table);
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

    private static List<String> parts(String piece, Table table) {
        Portion portion = portion(piece, table);
        if (portion == null || !portion.usual() || !piece.endsWith(portion.name())) return Arrays.asList(piece);
        List<String> names = split(table.references(), portion.name());
        if (names.size() < 2) return Arrays.asList(piece);
        names.set(0, piece.substring(0, piece.length() - portion.name().length()) + names.get(0));
        return names;
    }

    private static List<String> split(List<Reference> table, String name) {
        String query = normalize(name), first = stem(query).split(" ")[0] + " ";
        List<String> names = exact(table, query).isEmpty() && options(table, query).stream().noneMatch(item ->
                item.stems().stream().anyMatch(key -> (key + " ").startsWith(first))) ? segments(table, Arrays.asList(name.split(" "))) : null;
        return names == null ? new ArrayList<>() : names;
    }

    private static List<String> segments(List<Reference> table, List<String> words) {
        int start = 0;
        while (start < words.size() && (IGNORED.contains(normalize(words.get(start))) || EXTRAS.contains(normalize(words.get(start))))) start++;
        List<String> names = new ArrayList<>();
        if (start == words.size()) return names;
        for (int end = words.size(); end > start; end--) {
            String head = String.join(" ", words.subList(start, end));
            List<String> rest = exact(table, normalize(head)).isEmpty() ? null : segments(table, words.subList(end, words.size()));
            if (rest == null) continue;
            names.add(head);
            names.addAll(rest);
            return names;
        }
        return null;
    }

    private static Portion portion(String piece, Table table) {
        Double grams = null, count = null;
        double factor = 1;
        boolean measured = false;
        String name = piece;
        Matcher little = LITTLE.matcher(piece);
        if (little.find()) {
            factor = little.group(1).toLowerCase(Locale.ROOT).startsWith("b") ? 1.5 : .5;
            name = piece.substring(little.end());
        }
        Matcher prefix = PREFIX.matcher(name);
        if (prefix.matches()) {
            measured = true;
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
                measured = true;
            } else if (suffix.matches()) {
                double each = suffix.group(2) != null && count != null ? count : 1;
                grams = grams(each * number(suffix.group(3)), suffix.group(4));
                name = suffix.group(1);
                measured = true;
            }
        }
        name = ARTICLE.matcher(name).replaceFirst("");
        String container = null;
        Matcher held = CONTAINER.matcher(name), sized = SIZED.matcher(name);
        if (held.find()) {
            container = container(held.group(2));
            factor *= size(held.group(1));
            name = ARTICLE.matcher(name.substring(held.end())).replaceFirst("");
        } else if (sized.matches() && options(table.references(), normalize(name)).isEmpty()) {
            factor *= size(sized.group(1));
            name = ARTICLE.matcher(sized.group(2)).replaceFirst("");
        }
        name = (name.length() > 90 ? name.substring(0, 90) : name).trim();
        String said = normalize(name);
        if (said.length() < 2) return null;
        String query = options(table.references(), said).isEmpty() ? Arrays.stream(said.split(" "))
                .filter(word -> !EXTRAS.contains(word)).collect(Collectors.joining(" ")) : said;
        List<Reference> exact = exact(table.references(), query), options = options(table.references(), query);
        Reference reference = exact.size() == 1 ? exact.get(0) : null;
        Reference guess = reference != null ? reference : options.stream()
                .filter(item -> !raw(item.keys().get(0)) || raw(query)).findFirst().orElse(options.isEmpty() ? null : options.get(0));
        if (measured) return new Portion(name, grams, false, reference, guess, options);
        List<Double> weights = exact.stream().map(Reference::unit).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Double unit = weights.size() == 1 ? weights.get(0) : exact.isEmpty() && guess != null ? guess.unit() : null;
        double weight = container == null && count != null && unit != null ? count * unit
                : serving(table.servings(), container, query, guess) * (count != null && (container != null || count <= 3) ? count : 1);
        return new Portion(name, grams(Math.floor(weight * factor * 10 + .5) / 10, "g"), true, reference, guess, options);
    }

    private static double serving(List<Serving> servings, String container, String query, Reference reference) {
        List<String> texts = new ArrayList<>(Arrays.asList(query, stem(query)));
        if (reference != null) texts.addAll(Arrays.asList(reference.keys().get(0), reference.stems().get(0)));
        for (Serving serving : servings)
            if (serving.container().equals(container) && fits(serving, texts)) return serving.grams();
        for (Serving serving : servings) {
            if (!serving.container().equals("*") || !fits(serving, texts)) continue;
            return serving.foods().isEmpty() && reference != null && reference.unit() != null ? reference.unit() : serving.grams();
        }
        return 100;
    }

    private static boolean fits(Serving serving, List<String> texts) {
        return serving.foods().isEmpty() || serving.foods().stream().anyMatch(food ->
                texts.stream().anyMatch(text -> (text + " ").startsWith(food + " ")));
    }

    private static String container(String text) {
        String name = stem(normalize(text));
        if (name.startsWith("cuill") || name.startsWith("c a "))
            return name.endsWith(" cafe") || name.equals("c a c") ? "cuillere a cafe" : "cuillere a soupe";
        return name;
    }

    private static double size(String adjective) {
        if (adjective == null) return 1;
        return switch (Character.toLowerCase(adjective.charAt(0))) { case 'p' -> .7; case 'g' -> 1.3; default -> 1; };
    }

    private static boolean raw(String name) { return RAW.matcher(name).find() && !name.contains("cuit"); }

    private static List<Reference> options(List<Reference> table, String query) {
        return Stream.concat(exact(table, query).stream(), candidates(table, query)).distinct().limit(8).collect(Collectors.toList());
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

    static Table load(InputStream ciqual, InputStream portions) throws IOException {
        List<Reference> references = new ArrayList<>();
        for (String[] columns : rows(ciqual)) {
            List<String> keys = new ArrayList<>();
            keys.add(normalize(columns[0]));
            for (String alias : columns[2].split("\\|")) if (!alias.isEmpty()) keys.add(normalize(alias));
            references.add(new Reference(columns[0], Double.parseDouble(columns[1]), keys,
                    keys.stream().map(FoodDescription::stem).collect(Collectors.toList()),
                    columns.length > 3 ? Double.valueOf(columns[3]) : null));
        }
        List<Serving> servings = new ArrayList<>();
        for (String[] columns : rows(portions))
            servings.add(new Serving(columns[0], columns[1].isEmpty() ? new ArrayList<>() : Arrays.asList(columns[1].split("\\|")),
                    Double.parseDouble(columns[2])));
        return new Table(references, servings);
    }

    private static List<String[]> rows(InputStream source) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                String[] columns = line.split("\\t", -1);
                if (!line.startsWith("#") && columns.length >= 3) rows.add(columns);
            }
        }
        return rows;
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
