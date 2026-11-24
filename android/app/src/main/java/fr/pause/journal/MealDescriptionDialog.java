package fr.pause.journal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class MealDescriptionDialog {
    private final Activity activity;
    private final Journal journal;
    private final LocalDate date;
    private final Runnable refresh;

    MealDescriptionDialog(Activity activity, Journal journal, LocalDate date, Runnable refresh) {
        this.activity = activity;
        this.journal = journal;
        this.date = date;
        this.refresh = refresh;
    }

    void open() {
        LinearLayout form = form();
        text(form, "Écris ton repas ou utilise le micro de ton clavier. Exemple : 90 g de baguette et 10 g de beurre.");
        EditText input = new EditText(activity);
        input.setHint("Décris ton repas");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(700)});
        form.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Décrire un repas").setView(form)
                .setNegativeButton("Annuler", null).setPositiveButton("Estimer", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String description = input.getText().toString().trim();
            if (description.isEmpty()) {
                input.setError("Décris ton repas.");
                return;
            }
            List<FoodDescription.Portion> parts;
            try {
                parts = FoodDescription.parse(description, FoodDescription.load(activity));
            } catch (Exception error) {
                parts = FoodDescription.parse(description, java.util.Collections.emptyList());
            }
            if (parts.isEmpty() || parts.size() > 20) {
                input.setError("Indique entre 1 et 20 aliments.");
                return;
            }
            dialog.dismiss();
            review(description, parts);
        });
    }

    private void review(String description, List<FoodDescription.Portion> portions) {
        LinearLayout form = form();
        text(form, "Valeurs moyennes. Vérifie chaque quantité et référence avant d’enregistrer.");
        List<Row> rows = new ArrayList<>();
        for (FoodDescription.Portion portion : portions) rows.add(new Row(form, portion));
        TextView total = text(form, "");
        Runnable update = () -> {
            double sum = 0;
            for (Row row : rows) {
                Double grams = number(row.grams, 0, 10000, false), energy = number(row.energy, 0, 1000, true);
                if (grams == null || energy == null) {
                    total.setText("Total en attente de quantités ou de valeurs.");
                    return;
                }
                sum += grams * energy / 100;
            }
            total.setText(String.format(Locale.FRANCE, "Total estimé · %.0f kcal", sum));
        };
        for (Row row : rows) {
            watch(row.grams, update);
            watch(row.energy, update);
        }
        update.run();
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Vérifier le repas").setView(scroll)
                .setNegativeButton("Annuler", null).setNeutralButton("À estimer", null)
                .setPositiveButton("Enregistrer", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<Journal.Meal> meals = new ArrayList<>();
            for (Row row : rows) {
                Double grams = number(row.grams, 0, 10000, false), energy = number(row.energy, 0, 1000, true);
                if (grams == null || energy == null) {
                    if (grams == null) row.grams.setError("Indique le poids en grammes.");
                    if (energy == null) row.energy.setError("Choisis une référence ou indique les kcal pour 100 g.");
                    return;
                }
                boolean ciqual = row.reference != null && Math.abs(energy - row.reference.per100()) < .0001;
                String name = row.reference == null ? row.part.name() : row.reference.name();
                if (name.length() > 90) name = name.substring(0, 90);
                meals.add(new Journal.Meal(UUID.randomUUID().toString(), name, grams * energy / 100,
                        grams, energy, ciqual ? "Ciqual" : "Saisie"));
            }
            journal.addMeals(date, meals);
            dialog.dismiss();
            refresh.run();
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            StringBuilder draft = new StringBuilder(description);
            for (Row row : rows) {
                Double grams = number(row.grams, 0, 10000, false);
                if (grams != null && !grams.equals(row.part.grams()))
                    draft.append("\n").append(row.part.name()).append(" : ").append(format(grams)).append(" g");
            }
            String pending = draft.length() <= 700 ? draft.toString() : description;
            new AlertDialog.Builder(activity).setTitle("Estimer avec une IA")
                    .setMessage("Garde ce repas pour la prochaine synchro, puis demande à Claude de traiter les repas à estimer. Tu peux aussi copier une demande pour l’IA de ton choix.")
                    .setNegativeButton("Annuler", null)
                    .setNeutralButton("Copier la demande", (box, which) -> copyPrompt(pending))
                    .setPositiveButton("Mettre en attente", (box, which) -> {
                        journal.addDraft(date, pending);
                        dialog.dismiss();
                        refresh.run();
                    }).show();
        });
    }

    private void copyPrompt(String description) {
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("Estimation de repas",
                "Estime les calories de ce repas pour le " + date + ". Donne les kcal pour 100 g par aliment et les kcal de chaque portion. Demande les quantités ou précisions manquantes avant de donner un total.\n\nRepas : " + description));
        Toast.makeText(activity, "Demande copiée.", Toast.LENGTH_SHORT).show();
    }

    private final class Row {
        final FoodDescription.Portion part;
        final EditText grams, energy;
        FoodDescription.Reference reference;
        TextView note;

        Row(LinearLayout form, FoodDescription.Portion part) {
            this.part = part;
            reference = part.reference();
            TextView heading = text(form, "\n" + part.name());
            heading.setTextSize(17);
            heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            note = text(form, reference == null ? "Référence à choisir ou à renseigner." : "Ciqual · " + reference.name());
            grams = decimal(form, "Quantité (g)", part.grams());
            energy = decimal(form, "Calories pour 100 g", reference == null ? null : reference.per100());
            if (!part.options().isEmpty() && reference == null) {
                android.widget.Button choose = new android.widget.Button(activity);
                choose.setAllCaps(false);
                choose.setText("Choisir une référence");
                choose.setOnClickListener(v -> {
                    String[] choices = part.options().stream().map(FoodDescription.Reference::name).toArray(String[]::new);
                    new AlertDialog.Builder(activity).setTitle(part.name()).setItems(choices, (dialog, index) -> {
                        reference = part.options().get(index);
                        note.setText("Ciqual · " + reference.name());
                        energy.setText(format(reference.per100()));
                    }).show();
                });
                form.addView(choose);
            }
        }
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(22 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding / 2, padding, padding / 2);
        return form;
    }

    private TextView text(LinearLayout form, String label) {
        TextView view = new TextView(activity);
        view.setText(label);
        form.addView(view);
        return view;
    }

    private EditText decimal(LinearLayout form, String label, Double value) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setKeyListener(DigitsKeyListener.getInstance("0123456789,."));
        TextView caption = text(form, label);
        input.setId(android.view.View.generateViewId());
        caption.setLabelFor(input.getId());
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        if (value != null) input.setText(format(value));
        form.addView(input);
        return input;
    }

    private static void watch(EditText field, Runnable action) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { action.run(); }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private static String format(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static Double number(EditText input, double min, double max, boolean allowMin) {
        try {
            double value = Double.parseDouble(input.getText().toString().trim().replace(',', '.'));
            return Double.isFinite(value) && value <= max && (value > min || allowMin && value == min) ? value : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
