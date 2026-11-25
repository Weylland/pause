package fr.pause.journal;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

final class MealDescriptionDialog {
    private final Activity activity;
    private final Journal journal;
    private final LocalDate date;
    private final Runnable refresh;
    private final ChatGptPlan chatgpt;
    private final Executor worker;
    private final String provider;
    private Journal.MealDraft savedDraft;

    MealDescriptionDialog(Activity activity, Journal journal, LocalDate date, Runnable refresh, ChatGptPlan chatgpt, Executor worker, String provider) {
        this.activity = activity;
        this.journal = journal;
        this.date = date;
        this.refresh = refresh;
        this.chatgpt = chatgpt;
        this.worker = worker;
        this.provider = provider;
    }

    void open() { showDescription(""); }

    void openDraft(Journal.MealDraft draft) {
        savedDraft = draft;
        String text = draft.description();
        if (text.startsWith("Code-barres ")) {
            int newline = text.indexOf('\n');
            text = newline < 0 ? "" : text.substring(newline + 1);
        }
        showDescription(text);
    }

    private void showDescription(String initial) {
        LinearLayout form = form();
        text(form, "Écris ou dicte ce que tu as mangé. Sans quantité, une portion habituelle est comptée.");
        EditText input = new EditText(activity);
        input.setHint("Un bol de soupe et deux tartines");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(700)});
        input.setText(initial);
        PaperStyle.field(input);
        form.addView(input);
        TextView check = text(form, "Vérifier avant d’ajouter");
        check.setTextColor(PaperStyle.ACCENT);
        int space = Math.round(14 * activity.getResources().getDisplayMetrics().density);
        check.setPadding(0, space, 0, space);
        AlertDialog dialog = new PaperStyle.DialogBuilder(activity).setTitle("Décrire un repas").setView(form)
                .setNegativeButton("Annuler", null).setNeutralButton(provider.equals("claude") ? "Pour Claude" : "À la synchro", null)
                .setPositiveButton("Ajouter", null).create();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        input.requestFocus();
        input.setSelection(input.length());
        dialog.show();
        if (savedDraft != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(android.view.View.GONE);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String description = input.getText().toString().trim();
            if (description.isEmpty()) { input.setError("Décris ton repas."); return; }
            defer(dialog, description);
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> read(dialog, input, false));
        check.setOnClickListener(v -> read(dialog, input, true));
    }

    private void read(AlertDialog dialog, EditText input, boolean check) {
        String description = input.getText().toString().trim();
        if (description.isEmpty()) {
            input.setError("Décris ton repas.");
            return;
        }
        List<FoodDescription.Portion> parts;
        try {
            parts = FoodDescription.parse(description, FoodDescription.load(activity.getAssets().open("ciqual.tsv"), activity.getAssets().open("portions.tsv")));
        } catch (Exception error) {
            parts = FoodDescription.parse(description, new FoodDescription.Table(java.util.Collections.emptyList(), java.util.Collections.emptyList()));
        }
        if (parts.isEmpty() || parts.size() > 20) {
            input.setError("Indique entre 1 et 20 aliments.");
            return;
        }
        dialog.dismiss();
        estimate(description, parts, check);
    }

    private void estimate(String description, List<FoodDescription.Portion> portions, boolean check) {
        if (!provider.equals("chatgpt") || !chatgpt.connected() || portions.stream().allMatch(portion -> portion.guess() != null)) {
            propose(description, portions, java.util.Collections.emptyMap(), check);
            return;
        }
        AlertDialog loading = new PaperStyle.DialogBuilder(activity).setTitle("Estimation en cours")
                .setMessage("ChatGPT cherche les aliments absents de Ciqual.").setNegativeButton("Annuler", null).show();
        worker.execute(() -> {
            Map<Integer, Double> values;
            try { values = chatgpt.estimate(portions); }
            catch (Exception error) { values = java.util.Collections.emptyMap(); }
            Map<Integer, Double> result = values;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed() || !loading.isShowing()) return;
                loading.dismiss();
                if (result.isEmpty()) Toast.makeText(activity, "ChatGPT n’a pas proposé de valeur. Tu peux compléter le repas.", Toast.LENGTH_LONG).show();
                propose(description, portions, result, check);
            });
        });
    }

    private void propose(String description, List<FoodDescription.Portion> portions, Map<Integer, Double> estimates, boolean check) {
        List<Journal.Meal> meals = new ArrayList<>();
        for (int i = 0; i < portions.size(); i++) {
            FoodDescription.Portion part = portions.get(i);
            Double energy = part.guess() != null ? Double.valueOf(part.guess().per100()) : estimates.get(i);
            if (check || part.grams() == null || energy == null) {
                review(description, portions, estimates);
                return;
            }
            meals.add(meal(part, part.guess(), estimates.get(i), part.grams(), energy));
        }
        save(meals);
    }

    private static Journal.Meal meal(FoodDescription.Portion part, FoodDescription.Reference reference, Double estimate, double grams, double energy) {
        boolean ciqual = reference != null && Math.abs(energy - reference.per100()) < .0001;
        String name = reference == null || reference == part.guess() && part.reference() == null ? part.name() : reference.name();
        if (name.length() > 90) name = name.substring(0, 90);
        String source = ciqual ? "Ciqual" : estimate != null && Math.abs(energy - estimate) < .0001 ? "ChatGPT" : "Saisie";
        return new Journal.Meal(UUID.randomUUID().toString(), name, grams * energy / 100, grams, energy, source);
    }

    private void save(List<Journal.Meal> meals) {
        if (savedDraft == null) journal.addMeals(date, meals);
        else if (!journal.finishDraft(savedDraft, meals)) {
            Toast.makeText(activity, "Ce repas a déjà été modifié. Rouvre-le depuis le journal.", Toast.LENGTH_LONG).show();
            refresh.run();
            return;
        }
        double kcal = meals.stream().mapToDouble(Journal.Meal::kcal).sum();
        Toast.makeText(activity, String.format(Locale.FRANCE, "Ajouté · %.0f kcal. Touche un aliment pour le corriger.", kcal), Toast.LENGTH_LONG).show();
        refresh.run();
    }

    private void review(String description, List<FoodDescription.Portion> portions, Map<Integer, Double> estimates) {
        LinearLayout form = form();
        text(form, "Valeurs estimées. Change-les seulement si tu connais les vraies.");
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < portions.size(); i++) rows.add(new Row(form, portions.get(i), estimates.get(i)));
        TextView total = text(form, "");
        total.setTypeface(PaperStyle.title(activity));
        total.setTextSize(20);
        total.setTextColor(PaperStyle.ACCENT);
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
        AlertDialog dialog = new PaperStyle.DialogBuilder(activity).setTitle("Vérifier le repas").setView(scroll)
                .setNegativeButton("Annuler", null).setNeutralButton(provider.equals("claude") ? "Pour Claude" : "À la synchro", null)
                .setPositiveButton("Enregistrer", null).create();
        dialog.show();
        if (savedDraft != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(android.view.View.GONE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<Journal.Meal> meals = new ArrayList<>();
            for (Row row : rows) {
                Double grams = number(row.grams, 0, 10000, false), energy = number(row.energy, 0, 1000, true);
                if (grams == null || energy == null) {
                    if (grams == null) row.grams.setError("Indique le poids en grammes.");
                    if (energy == null) row.energy.setError("Choisis une référence ou indique les kcal pour 100 g.");
                    return;
                }
                meals.add(meal(row.part, row.reference, row.estimate, grams, energy));
            }
            dialog.dismiss();
            save(meals);
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            StringBuilder draft = new StringBuilder(description);
            for (Row row : rows) {
                Double grams = number(row.grams, 0, 10000, false);
                if (grams != null && !grams.equals(row.part.grams()))
                    draft.append("\n").append(row.part.name()).append(" : ").append(format(grams)).append(" g");
            }
            String pending = draft.length() <= 700 ? draft.toString() : description;
            defer(dialog, pending);
        });
    }

    private void defer(AlertDialog dialog, String description) {
        journal.addDraft(date, description);
        dialog.dismiss();
        refresh.run();
        Toast.makeText(activity, "Repas gardé pour la prochaine synchro.", Toast.LENGTH_SHORT).show();
    }

    private final class Row {
        final FoodDescription.Portion part;
        final EditText grams, energy;
        final Double estimate;
        FoodDescription.Reference reference;
        TextView note;

        Row(LinearLayout form, FoodDescription.Portion part, Double estimate) {
            this.part = part;
            this.estimate = estimate;
            reference = part.guess();
            TextView heading = text(form, "\n" + part.name());
            heading.setTextSize(17);
            heading.setTypeface(PaperStyle.title(activity));
            note = text(form, reference == null ? estimate == null ? "Référence à choisir ou à renseigner." : "Estimation ChatGPT"
                    : "Ciqual · " + reference.name());
            grams = decimal(form, part.usual() ? "Quantité estimée (g)" : "Quantité (g)", part.grams());
            energy = decimal(form, "Calories pour 100 g", reference == null ? estimate : Double.valueOf(reference.per100()));
            if (!part.options().isEmpty() && part.reference() == null) {
                android.widget.Button choose = new android.widget.Button(activity);
                choose.setAllCaps(false);
                choose.setTypeface(PaperStyle.title(activity));
                choose.setTextColor(PaperStyle.ACCENT);
                choose.setText(reference == null ? "Choisir une référence" : "Changer de référence");
                choose.setOnClickListener(v -> {
                    String[] choices = part.options().stream().map(FoodDescription.Reference::name).toArray(String[]::new);
                    new PaperStyle.DialogBuilder(activity).setTitle(part.name()).setItems(choices, (dialog, index) -> {
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
        view.setTextColor(PaperStyle.INK);
        view.setTypeface(PaperStyle.body(activity));
        view.setTextSize(15);
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
        PaperStyle.field(input);
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
