package fr.pause.journal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class FoodDescriptionTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).resolve("../..").normalize();

    @Test
    public void sharedMeals() throws Exception {
        FoodDescription.Table table = FoodDescription.load(Files.newInputStream(ROOT.resolve("nutrition/ciqual.tsv")),
                Files.newInputStream(ROOT.resolve("nutrition/portions.tsv")));
        for (String line : Files.readAllLines(ROOT.resolve("tests/repas.tsv"))) {
            String[] columns = line.split("\t", 2);
            String actual = FoodDescription.parse(columns[0].replace("\\n", "\n"), table).stream()
                    .map(FoodDescriptionTest::describe).collect(Collectors.joining("\t"));
            assertEquals(columns[0], columns[1], actual);
        }
    }

    private static String describe(FoodDescription.Portion portion) {
        String grams = portion.grams() == null ? "?"
                : java.math.BigDecimal.valueOf(portion.grams()).stripTrailingZeros().toPlainString() + (portion.usual() ? "*" : "");
        String reference = portion.reference() != null ? "=" + portion.reference().name()
                : portion.guess() == null ? "-" : "~" + portion.guess().name();
        return portion.name() + "|" + grams + "|" + reference;
    }
}
