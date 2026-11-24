package fr.pause.journal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int PAPER = 0xFFF6F3EB;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int SURFACE = 0xFFFFFEFA;
    private static final int INK = 0xFF182F25;
    private static final int MUTED = 0xFF52675A;
    private static final int LINE = 0xFFDEE4D8;
    private static final int FOREST = 0xFF164332;
    private static final int ACCENT = 0xFF285C44;
    private static final int MINT = 0xFFE5EFE1;
    private static final int LIGHT_MINT = 0xFFE4EFE4;
    private static final int LIME = 0xFFDCEAAE;
    private static final int GREEN = 0xFFDCEBD8;
    private static final int GREEN_INK = 0xFF24563D;
    private static final int PEACH = 0xFFFAEADB;
    private static final int PEACH_INK = 0xFF714527;
    private static final int CAMERA = 10, GALLERY = 11, EXPORT = 12;
    private static final int NOTIFICATION_PERMISSION = 13;
    private static final int PAUSE = 0, WEIGHT = 1, FOOD = 2, PC = 3, AI = 4;
    private static final Locale FR = Locale.FRANCE;
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", FR);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", FR);
    private static final DateTimeFormatter HISTORY_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", FR);
    private static final DateTimeFormatter HEADER_DATE = DateTimeFormatter.ofPattern("d MMMM", FR);

    private Journal journal;
    private Bridge bridge;
    private LinearLayout root;
    private LinearLayout navigation;
    private ScrollView scroll;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expireShare = () -> stopShare(true);
    private int tab = PAUSE;
    private LocalDate currentDay, pauseDate, foodDate;
    private LocalDate streakStart;
    private int streakTier;
    private String photoPath;
    private boolean photoBarcode;
    private boolean photoPairing;
    private LocalDate photoDate;
    private boolean processing;
    private boolean visible;
    private SharedPreferences desktop;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        journal = new Journal(this);
        bridge = new Bridge(this, journal);
        desktop = getSharedPreferences("pause.desktop", MODE_PRIVATE);
        currentDay = pauseDate = foodDate = today();
        if (state != null) {
            tab = state.getInt("tab");
            pauseDate = LocalDate.parse(state.getString("pauseDate", today().toString()));
            foodDate = LocalDate.parse(state.getString("foodDate", today().toString()));
            currentDay = LocalDate.parse(state.getString("currentDay", today().toString()));
            photoPath = state.getString("photoPath");
            photoBarcode = state.getBoolean("photoBarcode");
            photoPairing = state.getBoolean("photoPairing");
            String date = state.getString("photoDate");
            if (date != null) photoDate = LocalDate.parse(date);
        }
        if (JournalReminder.OPEN_TODAY.equals(getIntent().getAction())) {
            tab = PAUSE;
            pauseDate = today();
        }
        else if (JournalReminder.OPEN_WEIGHT.equals(getIntent().getAction())) tab = WEIGHT;
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = column();
        root.setBackgroundColor(PAPER);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = row();
        View separator = new View(this);
        separator.setBackgroundColor(LINE);
        root.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
        navigation.setBackgroundColor(SURFACE);
        navigation.setPadding(dp(9), dp(6), dp(9), dp(6));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, 0);
                navigation.setPadding(dp(9), dp(6), dp(9), dp(6) + bars.bottom);
                return insets;
            });
        }
        setContentView(root);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("tab", tab);
        state.putString("pauseDate", pauseDate.toString());
        state.putString("foodDate", foodDate.toString());
        state.putString("currentDay", currentDay.toString());
        state.putString("photoPath", photoPath);
        state.putBoolean("photoBarcode", photoBarcode);
        state.putBoolean("photoPairing", photoPairing);
        if (photoDate != null) state.putString("photoDate", photoDate.toString());
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        JournalReminder.ensureScheduled(this);
        LocalDate now = today();
        if (foodDate.equals(currentDay)) foodDate = now;
        if (pauseDate.equals(currentDay) && journal.inPause(now)) pauseDate = now;
        currentDay = now;
        render(true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (JournalReminder.OPEN_TODAY.equals(intent.getAction())) {
            stopShare(false);
            tab = PAUSE;
            pauseDate = today();
            render(false);
        } else if (JournalReminder.OPEN_WEIGHT.equals(intent.getAction())) {
            stopShare(false);
            tab = WEIGHT;
            render(false);
        }
    }

    @Override
    protected void onStop() {
        visible = false;
        stopShare(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        bridge.stop();
        worker.shutdownNow();
        super.onDestroy();
    }

    private LocalDate today() {
        return LocalDate.now();
    }

    private void render(boolean keepScroll) {
        int position = keepScroll ? scroll.getScrollY() : 0;
        scroll.removeAllViews();
        LinearLayout page = column();
        page.setPadding(dp(19), dp(8), dp(19), dp(18));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        switch (tab) {
            case PAUSE -> showPause(page);
            case WEIGHT -> showWeight(page);
            case FOOD -> showFood(page);
            case PC -> showPc(page);
            case AI -> showAi(page);
        }
        scroll.post(() -> scroll.scrollTo(0, position));
        navigation.removeAllViews();
        String[] names = {"Pause", "Poids", "Calories", "PC", "Bilan IA"};
        for (int i = 0; i < names.length; i++) {
            int destination = i;
            boolean selected = tab == i;
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER);
            item.setSelected(selected);
            item.setFocusable(true);
            item.setContentDescription(names[i]);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26285C44), null, shape(WHITE, 10, 0)));
            LinearLayout iconWrap = row();
            iconWrap.setGravity(Gravity.CENTER);
            iconWrap.setBackground(shape(selected ? MINT : SURFACE, 14, 0));
            iconWrap.addView(new JournalIcon(this, destination, selected ? ACCENT : MUTED),
                    new LinearLayout.LayoutParams(dp(20), dp(20)));
            item.addView(iconWrap, new LinearLayout.LayoutParams(dp(43), dp(27)));
            TextView label = text(names[i], 11, selected ? ACCENT : MUTED);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(label);
            item.setOnClickListener(view -> {
                if (tab == destination) return;
                if (destination != AI) stopShare(false);
                tab = destination;
                render(false);
            });
            navigation.addView(item, new LinearLayout.LayoutParams(0, dp(50), 1));
        }
    }

    private void title(LinearLayout page, String title, String subtitle) {
        LinearLayout brand = row();
        brand.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView mark = new android.widget.ImageView(this);
        mark.setImageResource(fr.pause.journal.R.drawable.ic_pause);
        mark.setBackground(shape(FOREST, 8, 0));
        mark.setClipToOutline(true);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(mark, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView wordmark = heading("Pause", 16, INK);
        LinearLayout.LayoutParams wordmarkSize = new LinearLayout.LayoutParams(0, -2, 1);
        wordmarkSize.setMarginStart(dp(7));
        brand.addView(wordmark, wordmarkSize);
        if (tab == PAUSE) {
            JournalReminder.Settings settings = JournalReminder.settings(this);
            String label = settings.enabled() && JournalReminder.canNotify(this)
                    ? "Rappel · " + reminderTime(settings.hour(), settings.minute()) : "Rappel";
            TextView reminder = button(label, MINT, ACCENT, this::editReminder);
            reminder.setTextSize(13);
            brand.addView(reminder);
        } else {
            brand.addView(text(today().format(HEADER_DATE), 13, MUTED));
        }
        add(page, brand, 1);
        TextView name = text(title, 37, INK);
        name.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        name.setIncludeFontPadding(false);
        add(page, name, 12);
        if (!subtitle.isEmpty()) add(page, text(subtitle, 14, MUTED), 3);
    }

    private void showPause(LinearLayout page) {
        LocalDate now = today();
        title(page, "Ma pause", "");
        LocalDate start = journal.start();
        LinearLayout hero = panel(page, FOREST, 17);
        if (start == null) {
            streakStart = null;
            streakTier = 0;
            add(hero, heading("Préparer ma pause", 25, WHITE), 0);
            add(hero, text("Choisis tes dates et note comment s’est passée chaque journée.", 15, LIGHT_MINT), 7);
            add(hero, button("Démarrer une pause", LIME, FOREST, this::editGoal), 16);
            return;
        }
        LocalDate end = journal.end();
        int elapsed = journal.elapsed(now);
        int kept = journal.keptDays(now);
        String heroLabel = elapsed == 0 ? "Pause à venir" : now.isAfter(end) ? "Pause terminée" : "Jour " + elapsed + " sur " + journal.duration();
        LinearLayout heroHead = row();
        heroHead.setGravity(Gravity.CENTER_VERTICAL);
        heroHead.addView(text(heroLabel, 14, LIME), new LinearLayout.LayoutParams(0, -2, 1));
        int streak = journal.streak(now);
        int tier = StreakBadge.tier(streak);
        boolean reached = start.equals(streakStart) && tier > streakTier;
        streakStart = start;
        streakTier = tier;
        heroHead.addView(new StreakBadge(this, streak, reached));
        add(hero, heroHead, 0);
        add(hero, heading(kept + (kept == 1 ? " jour tenu" : " jours tenus"), 32, WHITE), 4);
        LinearLayout track = row();
        track.setBackground(shape(0xFF527862, 12, 0));
        track.setClipToOutline(true);
        if (kept > 0) {
            View fill = new View(this);
            fill.setBackgroundColor(LIME);
            track.addView(fill, new LinearLayout.LayoutParams(0, -1, kept));
        }
        if (journal.duration() > kept) track.addView(new View(this), new LinearLayout.LayoutParams(0, -1, journal.duration() - kept));
        LinearLayout.LayoutParams progress = new LinearLayout.LayoutParams(-1, dp(7));
        progress.topMargin = dp(13);
        hero.addView(track, progress);
        LinearLayout dates = row();
        dates.setGravity(Gravity.CENTER_VERTICAL);
        dates.addView(text("Du " + SHORT_DATE.format(start) + " au " + SHORT_DATE.format(end), 13, LIGHT_MINT),
                new LinearLayout.LayoutParams(0, -2, 1));
        if (journal.duration() < Journal.MAX_PAUSE_DURATION) {
            TextView extend = button("Prolonger", 0xFF375D45, LIGHT_MINT, this::extendGoal);
            extend.setContentDescription("Prolonger la pause");
            dates.addView(extend);
        }
        add(hero, dates, 8);

        if (!journal.inPause(pauseDate) && !now.isBefore(start)) pauseDate = now.isAfter(end) ? end : now;
        if (journal.inPause(pauseDate) && !pauseDate.isAfter(now)) {
            LocalDate day = pauseDate;
            String current = journal.status(day);
            LinearLayout check = panel(page, WHITE, 13);
            LinearLayout checkHead = row();
            checkHead.setGravity(Gravity.CENTER_VERTICAL);
            checkHead.addView(heading(day.equals(now) ? "Aujourd’hui" : displayDate(day), 21, INK),
                    new LinearLayout.LayoutParams(0, -2, 1));
            if (!day.equals(now) && journal.inPause(now)) {
                checkHead.addView(button("Aujourd’hui", MINT, ACCENT, () -> {
                    pauseDate = now;
                    render(false);
                }));
            }
            add(check, checkHead, 0);
            add(check, text("As-tu tenu ta pause ?", 14, MUTED), 3);
            LinearLayout actions = row();
            TextView yes = button("Tenu", current.equals("tenu") ? GREEN_INK : GREEN,
                    current.equals("tenu") ? WHITE : GREEN_INK, () -> mark(day, "tenu"));
            boolean notKept = current.equals(Journal.NOT_KEPT_STATUS);
            TextView no = button("Non tenu", notKept ? PEACH_INK : PEACH,
                    notKept ? WHITE : PEACH_INK, () -> mark(day, Journal.NOT_KEPT_STATUS));
            yes.setSelected(current.equals("tenu"));
            no.setSelected(notKept);
            weighted(actions, yes, 0);
            weighted(actions, no, 7);
            add(check, actions, 12);
            if (!current.isEmpty()) add(check, link("Effacer la réponse", () -> mark(day, "")), 3);
        }

        LinearLayout calendar = panel(page, WHITE, 13);
        add(calendar, heading("Calendrier", 21, INK), 0);
        LinearLayout legend = row();
        calendarLegend(legend, "✓ Tenu", GREEN, GREEN_INK);
        calendarLegend(legend, "× Non tenu", PEACH, PEACH_INK);
        calendarLegend(legend, "· À renseigner", PAPER, MUTED);
        add(calendar, legend, 8);
        LocalDate week = start.minusDays(start.getDayOfWeek().getValue() - 1L);
        LinearLayout weekdays = row();
        for (String label : new String[]{"L", "M", "M", "J", "V", "S", "D"}) {
            TextView cell = text(label, 12, MUTED);
            cell.setGravity(Gravity.CENTER);
            weekdays.addView(cell, new LinearLayout.LayoutParams(0, dp(26), 1));
        }
        add(calendar, weekdays, 8);
        while (!week.isAfter(end)) {
            LinearLayout days = row();
            for (int i = 0; i < 7; i++) {
                LocalDate date = week.plusDays(i);
                addCalendarDay(days, date, now);
            }
            add(calendar, days, 3);
            week = week.plusWeeks(1);
        }
        add(page, link("Modifier les dates de la pause", this::editGoal), 8);
    }

    private void calendarLegend(LinearLayout row, String label, int background, int color) {
        TextView tag = text(label, 12, color);
        tag.setBackground(shape(background, 8, 0));
        tag.setPadding(dp(7), dp(3), dp(7), dp(3));
        LinearLayout.LayoutParams chip = new LinearLayout.LayoutParams(-2, -2);
        chip.setMarginEnd(dp(5));
        row.addView(tag, chip);
    }

    private void addCalendarDay(LinearLayout days, LocalDate date, LocalDate now) {
        int height = Math.max(dp(42), Math.round(dp(37) * getResources().getConfiguration().fontScale));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, 1);
        params.setMarginStart(dp(2));
        params.setMarginEnd(dp(2));
        if (!journal.inPause(date)) {
            days.addView(new View(this), params);
            return;
        }
        String status = journal.status(date);
        boolean future = date.isAfter(now);
        boolean notKept = status.equals(Journal.NOT_KEPT_STATUS);
        String symbol = status.equals("tenu") ? "✓" : notKept ? "×" : future ? "" : "·";
        int fill = status.equals("tenu") ? GREEN : notKept ? PEACH : future ? SURFACE : PAPER;
        int color = status.equals("tenu") ? GREEN_INK : notKept ? PEACH_INK : MUTED;
        TextView cell = text(date.getDayOfMonth() + (symbol.isEmpty() ? "" : "\n" + symbol), 13, color);
        cell.setGravity(Gravity.CENTER);
        cell.setBackground(shape(fill, 10, date.equals(pauseDate) ? ACCENT : future ? LINE : 0));
        cell.setContentDescription(displayDate(date) + ", " + (future ? "à venir"
                : status.equals("tenu") ? "tenu" : notKept ? "non tenu" : "à renseigner"));
        if (!future) cell.setOnClickListener(v -> {
            pauseDate = date;
            render(false);
        });
        cell.setSelected(date.equals(pauseDate));
        days.addView(cell, params);
    }

    private void mark(LocalDate date, String status) {
        if (journal.inPause(date) && !date.isAfter(today())) {
            journal.setStatus(date, status);
            render(true);
        }
    }

    private void editGoal() {
        LinearLayout form = form();
        LocalDate[] start = {journal.start() == null ? today() : journal.start()};
        TextView date = button(displayDate(start[0]), MINT, ACCENT, () -> {});
        date.setOnClickListener(view -> pickDate(start[0], false, chosen -> {
            start[0] = chosen;
            date.setText(displayDate(chosen));
        }));
        add(form, text("Date de début", 14, MUTED), 0);
        add(form, date, 4);
        EditText duration = field(form, "Durée en jours", Integer.toString(journal.duration()), InputType.TYPE_CLASS_NUMBER, 3);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Objectif de pause").setView(form)
                .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            double length = number(duration);
            if (!Double.isFinite(length) || length < 1 || length > Journal.MAX_PAUSE_DURATION || length != Math.floor(length)) {
                duration.setError("Choisis entre 1 et " + Journal.MAX_PAUSE_DURATION + " jours.");
                return;
            }
            dialog.dismiss();
            applyGoal(start[0], (int) length);
        });
    }

    private void extendGoal() {
        LocalDate start = journal.start();
        int duration = journal.duration();
        int remaining = Journal.MAX_PAUSE_DURATION - duration;
        if (start == null || remaining < 1) return;
        LinearLayout form = form();
        add(form, text("Fin actuelle · " + displayDate(journal.end()), 14, MUTED), 0);
        EditText extra = field(form, "Jours à ajouter", Integer.toString(Math.min(30, remaining)),
                InputType.TYPE_CLASS_NUMBER, 3);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Prolonger la pause").setView(form)
                .setNegativeButton("Annuler", null).setPositiveButton("Prolonger", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            double days = number(extra);
            if (!Double.isFinite(days) || days < 1 || days > remaining || days != Math.floor(days)) {
                extra.setError(remaining == 1 ? "Il reste 1 jour à ajouter." : "Choisis entre 1 et " + remaining + " jours.");
                return;
            }
            dialog.dismiss();
            if (!start.equals(journal.start()) || duration != journal.duration()) {
                toast("La pause a changé. Rouvre la prolongation.");
                return;
            }
            applyGoal(start, duration + (int) days);
        });
    }

    private void applyGoal(LocalDate start, int duration) {
        journal.setPause(start, duration);
        LocalDate now = today();
        LocalDate end = journal.end();
        pauseDate = now.isBefore(start) ? start : now.isAfter(end) ? end : now;
        render(false);
    }

    private static String reminderTime(int hour, int minute) {
        return String.format(FR, "%02d:%02d", hour, minute);
    }

    private void editReminder() {
        JournalReminder.Settings settings = JournalReminder.settings(this);
        LinearLayout form = form();
        CheckBox enabled = new CheckBox(this);
        enabled.setText("Activer le rappel quotidien");
        enabled.setChecked(settings.enabled());
        add(form, enabled, 0);
        CheckBox weight = new CheckBox(this);
        weight.setText("Me rappeler aussi la pesée");
        weight.setChecked(settings.weight());
        add(form, weight, 0);
        int[] time = {settings.hour(), settings.minute()};
        TextView hour = button(reminderTime(time[0], time[1]), MINT, ACCENT, () -> {});
        hour.setContentDescription("Heure du rappel");
        hour.setOnClickListener(v -> new TimePickerDialog(this, (picker, hours, minutes) -> {
            time[0] = hours;
            time[1] = minutes;
            hour.setText(reminderTime(hours, minutes));
        }, time[0], time[1], true).show());
        add(form, hour, 7);
        add(form, text("Rappel si ta journée reste à renseigner. Android peut décaler la notification.", 13, MUTED), 8);
        if (settings.enabled() && !JournalReminder.canNotify(this))
            add(form, text("Autorise les notifications de Pause dans les paramètres Android.", 13, PEACH_INK), 5);
        new AlertDialog.Builder(this).setTitle("Rappel du journal").setView(form)
                .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", (d, w) -> {
                    JournalReminder.configure(this, enabled.isChecked(), time[0], time[1], weight.isChecked());
                    render(true);
                    if (enabled.isChecked() && Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
                    else if (enabled.isChecked() && !JournalReminder.canNotify(this))
                        toast("Autorise les notifications de Pause dans les paramètres Android.");
                }).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION) {
            JournalReminder.schedule(this);
            render(true);
            if (!JournalReminder.canNotify(this)) toast("Le rappel attend l’autorisation des notifications.");
        }
    }

    private void editTarget(boolean weight) {
        LinearLayout form = form();
        EditText input = field(form, weight ? "Poids cible (kg)" : "Calories quotidiennes (kcal)",
                decimalInput(journal.target(weight)), decimalType(), 12);
        add(form, text("Laisse le champ vide pour retirer cet objectif.", 13, MUTED), 4);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(weight ? "Objectif de poids" : "Objectif calorique").setView(form)
                .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Double value = null;
            if (!input.getText().toString().trim().isEmpty()) {
                double number = number(input);
                if (!Double.isFinite(number) || number <= 0 || number > (weight ? 1000 : 100000)) {
                    input.setError("Indique une valeur valide.");
                    return;
                }
                value = number;
            }
            journal.setTarget(weight, value);
            dialog.dismiss();
            render(true);
        });
    }

    private void showWeight(LinearLayout page) {
        title(page, "Mon poids", "Mesures et évolution");
        List<Journal.Weight> history = journal.weights();
        LinearLayout chart = panel(page, WHITE, 17);
        if (history.isEmpty()) {
            add(chart, heading("Première pesée", 23, INK), 0);
            add(chart, text("Ajoute une mesure pour commencer à suivre ton évolution.", 14, MUTED), 6);
        } else {
            Journal.Weight recent = history.get(history.size() - 1);
            add(chart, text("Dernière mesure · " + HISTORY_DATE.format(recent.date()), 13, MUTED), 0);
            add(chart, heading(format(recent.kg(), 1) + " kg", 34, FOREST), 3);
            if (history.size() >= 2) {
                double difference = recent.kg() - history.get(0).kg();
                String delta = (difference > 0.0001 ? "+" : "") + format(difference, 1);
                add(chart, text(delta + " kg depuis le " + HISTORY_DATE.format(history.get(0).date()), 13, MUTED), 3);
                LinearLayout.LayoutParams graph = new LinearLayout.LayoutParams(-1, dp(135));
                graph.topMargin = dp(9);
                chart.addView(new WeightChart(this, history), graph);
                LinearLayout range = row();
                TextView end = text(SHORT_DATE.format(recent.date()), 12, MUTED);
                end.setGravity(Gravity.END);
                range.addView(text(SHORT_DATE.format(history.get(0).date()), 12, MUTED),
                        new LinearLayout.LayoutParams(0, -2, 1));
                range.addView(end, new LinearLayout.LayoutParams(0, -2, 1));
                add(chart, range, 1);
            }
        }
        Double target = journal.target(true);
        if (target != null) add(chart, text("Poids cible · " + format(target, 2) + " kg", 13, ACCENT), 7);
        add(page, link(target == null ? "Définir un objectif de poids" : "Modifier l’objectif de poids", () -> editTarget(true)), 2);
        add(page, button("Ajouter une pesée", FOREST, WHITE, () -> editWeight(today())), 14);

        if (!history.isEmpty()) {
            LinearLayout rows = panel(page, WHITE, 17);
            add(rows, heading("Historique", 20, INK), 0);
            for (int i = history.size() - 1; i >= 0; i--) {
                Journal.Weight entry = history.get(i);
                add(rows, button(HISTORY_DATE.format(entry.date()) + "   ·   " + format(entry.kg(), 1) + " kg",
                        PAPER, INK, () -> editWeight(entry.date())), 6);
            }
        }
    }

    private void editWeight(LocalDate initial) {
        LocalDate[] selected = {initial};
        LinearLayout form = form();
        TextView date = button(displayDate(initial), MINT, ACCENT, () -> {});
        add(form, date, 0);
        EditText input = field(form, "Poids (kg)", decimalInput(journal.weightAt(initial)),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 7);
        date.setOnClickListener(view -> pickDate(selected[0], true, chosen -> {
            selected[0] = chosen;
            date.setText(displayDate(chosen));
            input.setText(decimalInput(journal.weightAt(chosen)));
        }));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Pesée").setView(form)
                .setNegativeButton("Annuler", null).setNeutralButton("Supprimer", null)
                .setPositiveButton("Enregistrer", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            if (journal.weightAt(selected[0]) == null) {
                input.setError("Aucune mesure pour cette date.");
                return;
            }
            confirmDeletion("Supprimer cette pesée ?", () -> {
                journal.saveWeight(selected[0], null);
                dialog.dismiss();
                render(true);
            });
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double kg = number(input);
            if (!Double.isFinite(kg) || kg <= 0 || kg > 1000) {
                input.setError("Indique un poids en kg.");
                return;
            }
            journal.saveWeight(selected[0], kg);
            dialog.dismiss();
            render(true);
        });
    }

    private void showFood(LinearLayout page) {
        title(page, "Mes calories", "Journal alimentaire");
        LinearLayout date = row();
        TextView back = button("‹", WHITE, ACCENT, () -> changeFoodDate(foodDate.minusDays(1)));
        back.setContentDescription("Jour précédent");
        date.addView(back, new LinearLayout.LayoutParams(dp(46), dp(48)));
        TextView current = button(foodDate.equals(today()) ? "Aujourd’hui" : displayDate(foodDate), WHITE, INK,
                () -> pickDate(foodDate, true, this::changeFoodDate));
        weighted(date, current, 6);
        TextView next = button("›", WHITE, ACCENT, () -> changeFoodDate(foodDate.plusDays(1)));
        next.setContentDescription("Jour suivant");
        next.setEnabled(foodDate.isBefore(today()));
        next.setAlpha(next.isEnabled() ? 1f : .45f);
        LinearLayout.LayoutParams nextSize = new LinearLayout.LayoutParams(dp(46), dp(48));
        nextSize.setMarginStart(dp(6));
        date.addView(next, nextSize);
        add(page, date, 16);

        List<Journal.Meal> meals = journal.meals(foodDate);
        LinearLayout total = panel(page, PEACH, 10);
        add(total, text("Total enregistré", 14, PEACH_INK), 0);
        if (meals.isEmpty()) {
            add(total, heading("Aucune saisie", 24, INK), 6);
        } else {
            double kcal = 0;
            for (Journal.Meal meal : meals) kcal += meal.kcal();
            add(total, heading(format(kcal, 1) + " kcal", 33, INK), 4);
            add(total, text(meals.size() + (meals.size() == 1 ? " aliment noté" : " aliments notés"), 13, PEACH_INK), 5);
        }
        Double target = journal.target(false);
        if (target != null) add(total, text("Objectif quotidien · " + format(target, 1) + " kcal", 13, PEACH_INK), 6);
        add(page, link(target == null ? "Définir un objectif calorique" : "Modifier l’objectif calorique", () -> editTarget(false)), 1);

        LocalDate day = foodDate;
        LinearLayout addFood = panel(page, WHITE, 13);
        add(addFood, heading("Ajouter un aliment", 21, INK), 0);
        if (processing) {
            add(addFood, text("Lecture en cours…", 14, MUTED), 10);
        } else {
            add(addFood, button("Scanner un code-barres", MINT, ACCENT, () -> choosePhoto(true, day)), 11);
            add(addFood, button("Décrire un repas", MINT, ACCENT,
                    () -> new MealDescriptionDialog(this, journal, day, () -> render(true)).open()), 7);
            add(addFood, button("Photographier l’étiquette", PAPER, INK, () -> choosePhoto(false, day)), 7);
            add(addFood, link("Saisir manuellement", () -> editMeal(day, null, null)), 3);
        }
        List<Journal.MealDraft> drafts = journal.drafts(day);
        if (!drafts.isEmpty()) {
            LinearLayout pending = panel(page, WHITE, 13);
            add(pending, heading("Repas à estimer", 21, INK), 0);
            add(pending, text("Synchronise avec Pause sur le PC pour retrouver ces entrées via Claude.", 13, MUTED), 5);
            for (Journal.MealDraft draft : drafts) {
                String label = draft.description().length() > 90 ? draft.description().substring(0, 87) + "…" : draft.description();
                add(pending, button(label, PAPER, INK, () -> new AlertDialog.Builder(this)
                        .setTitle("Repas à estimer").setMessage(draft.description())
                        .setNegativeButton("Fermer", null)
                        .setNeutralButton("Supprimer", (dialog, which) -> confirmDeletion("Supprimer ce repas en attente ?", () -> {
                            journal.deleteDraft(draft.id());
                            render(true);
                        })).show()), 7);
            }
        }
        List<Double> history = new java.util.ArrayList<>();
        List<String> dates = new java.util.ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate dateInHistory = day.minusDays(i);
            List<Journal.Meal> entries = journal.meals(dateInHistory);
            history.add(entries.isEmpty() ? null : entries.stream().mapToDouble(Journal.Meal::kcal).sum());
            dates.add(Integer.toString(dateInHistory.getDayOfMonth()));
        }
        if (history.stream().anyMatch(java.util.Objects::nonNull)) {
            LinearLayout chart = panel(page, WHITE, 13);
            add(chart, heading("Calories sur 7 jours", 21, INK), 0);
            DailyCaloriesChart view = new DailyCaloriesChart(this, dates, history, target);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(145));
            size.topMargin = dp(9);
            chart.addView(view, size);
            if (target != null) add(chart, text("Ligne pointillée · objectif quotidien", 12, MUTED), 4);
        }

        if (!meals.isEmpty()) {
            LinearLayout entries = panel(page, WHITE, 13);
            add(entries, heading("Aliments du jour", 21, INK), 0);
            for (Journal.Meal meal : meals) {
                TextView item = button(meal.name() + "  ·  " + format(meal.kcal(), 1) + " kcal", PAPER, INK,
                        () -> editMeal(day, meal, null));
                item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                add(entries, item, 7);
            }
        }
    }

    private void changeFoodDate(LocalDate date) {
        if (!date.isAfter(today())) {
            foodDate = date;
            render(false);
        }
    }

    private void showPc(LinearLayout page) {
        title(page, "Mon PC", "Synchronisation Wi-Fi");
        WindowsSync.Pairing pairing = desktopPairing();
        LinearLayout connection = panel(page, FOREST, 17);
        if (pairing == null) {
            add(connection, heading("Associer Pause sur Windows", 24, WHITE), 0);
            add(connection, text("Connecte les deux appareils au même réseau. Sur le PC, ouvre Pause puis « Associer Android ».",
                    14, LIGHT_MINT), 7);
            add(connection, button("Scanner le QR code", LIGHT_MINT, FOREST, this::pickPairingPhoto), 13);
            add(connection, button("Coller le code", 0xFF375D45, WHITE, this::enterPairing), 7);
            return;
        }
        add(connection, heading("PC associé", 25, WHITE), 0);
        add(connection, text(pairing.address(), 14, LIGHT_MINT), 4);
        long last = desktop.getLong("last", 0);
        if (last > 0) {
            String date = DateTimeFormatter.ofPattern("d MMMM 'à' HH:mm", FR)
                    .format(Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()));
            add(connection, text("Dernière synchro : " + date, 14, LIGHT_MINT), 4);
        }
        add(connection, button("Synchroniser maintenant", LIGHT_MINT, FOREST, () -> syncPc(pairing)), 13);
        LinearLayout settings = panel(page, WHITE, 13);
        add(settings, text("Garde Pause ouvert sur le PC pendant l’échange. Tu peux modifier ton journal hors ligne sur les deux appareils.",
                14, MUTED), 0);
        add(settings, link("Modifier l’adresse du PC", () -> changePcAddress(pairing)), 7);
        add(settings, link("Scanner un nouveau QR code", this::pickPairingPhoto), 0);
        add(settings, link("Coller un nouveau code", this::enterPairing), 0);
        add(settings, link("Dissocier ce PC", this::forgetPc), 0);
    }

    private WindowsSync.Pairing desktopPairing() {
        try {
            return WindowsSync.Pairing.parse(desktop.getString("code", ""));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private void pickPairingPhoto() {
        new AlertDialog.Builder(this).setTitle("QR code de Pause Windows")
                .setItems(new String[]{"Prendre une photo", "Choisir une image"}, (dialog, selected) -> {
                    photoPairing = true;
                    if (selected == 0) capture();
                    else chooseImage();
                }).setNegativeButton("Annuler", null).show();
    }

    private void enterPairing() {
        LinearLayout form = form();
        EditText code = field(form, "Code copié sur le PC", "", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 150);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Associer Pause Windows").setView(form)
                .setNegativeButton("Annuler", null).setPositiveButton("Associer", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            WindowsSync.Pairing pairing;
            try {
                pairing = WindowsSync.Pairing.parse(code.getText().toString());
            } catch (IllegalArgumentException error) {
                code.setError(error.getMessage());
                return;
            }
            dialog.dismiss();
            savePairing(pairing);
        });
    }

    private void analyzePairing(Uri uri, String path) {
        worker.execute(() -> {
            WindowsSync.Pairing pairing = null;
            try {
                pairing = WindowsSync.Pairing.parse(FoodRecognition.qr(this, uri));
            } catch (Exception ignored) {
                // L’association peut aussi se faire en collant le code.
            } finally {
                removePhoto(path);
            }
            WindowsSync.Pairing result = pairing;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result != null) savePairing(result);
                else new AlertDialog.Builder(this).setMessage("QR code illisible. Reprends une photo ou colle le code du PC.")
                        .setNegativeButton("Fermer", null).setPositiveButton("Coller le code", (d, w) -> enterPairing()).show();
            });
        });
    }

    private void savePairing(WindowsSync.Pairing pairing) {
        WindowsSync.Pairing previous = desktopPairing();
        SharedPreferences.Editor change = desktop.edit().putString("code", pairing.code());
        if (previous != null && !previous.pin().equals(pairing.pin())) change.remove("last");
        change.apply();
        tab = PC;
        render(false);
        syncPc(pairing);
    }

    private void changePcAddress(WindowsSync.Pairing pairing) {
        LinearLayout form = form();
        EditText address = field(form, "Adresse IPv4 affichée sur le PC", pairing.address(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 15);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Adresse du PC").setView(form)
                .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                WindowsSync.Pairing updated = pairing.withAddress(address.getText().toString().trim());
                desktop.edit().putString("code", updated.code()).apply();
                dialog.dismiss();
                render(true);
            } catch (IllegalArgumentException error) {
                address.setError(error.getMessage());
            }
        });
    }

    private void forgetPc() {
        new AlertDialog.Builder(this).setMessage("Dissocier ce PC ? Tes entrées restent sur le téléphone.")
                .setNegativeButton("Annuler", null).setPositiveButton("Dissocier", (dialog, which) -> {
                    desktop.edit().clear().apply();
                    journal.forgetDesktop();
                    render(false);
                }).show();
    }

    private void syncPc(WindowsSync.Pairing pairing) {
        Journal.SyncState state = journal.syncState(pairing.pin());
        try {
            syncPc(pairing, state, state.journalId(), WindowsSync.changes(state));
        } catch (Exception error) {
            pcError("Impossible de préparer le journal pour la synchro.");
        }
    }

    private void syncPc(WindowsSync.Pairing pairing, Journal.SyncState state, String id, JSONArray changes) {
        AlertDialog progress = new AlertDialog.Builder(this).setTitle("Synchronisation")
                .setMessage("Échange du journal avec le PC…").setCancelable(false).show();
        worker.execute(() -> {
            JSONObject reply = null;
            String message = null;
            try {
                reply = WindowsSync.send(pairing, id, changes);
            } catch (WindowsSync.SyncException error) {
                message = error.getMessage();
            } catch (Exception error) {
                message = "L’échange n’a pas abouti. Réessaie depuis l’onglet PC.";
            }
            JSONObject result = reply;
            String failure = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                if (result == null) {
                    pcError(failure);
                    return;
                }
                String code = result.optString("error");
                if (!code.isEmpty()) {
                    pcError(switch (code) {
                        case "pairing" -> "Le code a changé sur le PC. Scanne le nouveau QR code dans l’onglet PC.";
                        case "journal" -> "Le PC a ouvert un autre journal. Dissocie puis associe le PC pour les rapprocher.";
                        case "version" -> "Les versions de Pause sont incompatibles. Mets les deux applis à jour.";
                        case "size" -> "Le journal est trop volumineux pour la synchro.";
                        default -> "Le PC n’a pas pu enregistrer l’échange. Vérifie le journal Windows.";
                    });
                    return;
                }
                JSONArray conflicts = result.optJSONArray("conflicts");
                if (conflicts != null) {
                    resolvePc(pairing, state, result.optString("journalId", id), changes, conflicts);
                    return;
                }
                try {
                    journal.applyDesktop(pairing.pin(), state, result);
                    desktop.edit().putLong("last", System.currentTimeMillis()).apply();
                    render(true);
                    if (!result.optBoolean("extended")) {
                        toast("Journal synchronisé. Mets Pause Windows à jour pour les bilans et les objectifs.");
                    } else if (!result.optBoolean("drafts") && WindowsSync.keys(state.current()).stream().anyMatch(WindowsSync::draftKey)) {
                        toast("Journal synchronisé. Mets Pause Windows à jour pour retrouver les repas à estimer sur le PC.");
                    } else {
                        toast("Journal synchronisé.");
                    }
                } catch (Exception error) {
                    pcError("Impossible de lire le journal renvoyé par le PC.");
                }
            });
        });
    }

    private void resolvePc(WindowsSync.Pairing pairing, Journal.SyncState state, String id, JSONArray changes, JSONArray conflicts) {
        StringBuilder details = new StringBuilder("Ces entrées diffèrent. Choisis leur version. Les autres modifications seront réunies.\n");
        for (int i = 0; i < Math.min(7, conflicts.length()); i++) {
            JSONObject conflict = conflicts.optJSONObject(i);
            String key = conflict.optString("key");
            String[] parts = key.split("/");
            String title = parts.length < 2 ? "Objectif de pause" : switch (parts[0]) {
                case "pause" -> "Pause du " + parts[1];
                case "weight" -> "Poids du " + parts[1];
                case "target" -> parts[1].equals("weight") ? "Objectif de poids" : "Objectif calorique";
                case "report" -> "Bilan enregistré";
                case "draft" -> "Repas à estimer";
                default -> "Aliment du " + parts[1];
            };
            details.append("\n").append(title)
                    .append("\nPC : ").append(conflictValue(key, conflict.opt("pc")))
                    .append("\nTéléphone : ").append(conflictValue(key, conflict.opt("phone"))).append("\n");
        }
        if (conflicts.length() > 7) details.append("\nEt ").append(conflicts.length() - 7).append(" autre(s) différence(s).");
        new AlertDialog.Builder(this).setTitle("Versions différentes").setMessage(details)
                .setNeutralButton("Annuler", null)
                .setNegativeButton("Garder le PC", (d, w) -> resendPc(pairing, state, id, changes, conflicts, false))
                .setPositiveButton("Garder le téléphone", (d, w) -> resendPc(pairing, state, id, changes, conflicts, true)).show();
    }

    private String conflictValue(String key, Object value) {
        if (value == null || value == JSONObject.NULL) return "Aucune saisie";
        if (key.equals("goal")) {
            JSONObject goal = (JSONObject) value;
            return goal.optString("start") + " · " + goal.optInt("duration") + " jours";
        }
        if (key.startsWith("weight/")) return format(((Number) value).doubleValue(), 2) + " kg";
        if (key.startsWith("pause/")) return "tenu".equals(value) ? "Tenu" : "Non tenu";
        if (key.startsWith("target/")) return format(((Number) value).doubleValue(), 2) + (key.endsWith("/weight") ? " kg" : " kcal");
        if (key.startsWith("report/")) return ((JSONObject) value).optString("title");
        if (key.startsWith("draft/")) return ((JSONObject) value).optString("description");
        JSONObject meal = (JSONObject) value;
        return meal.optString("name") + " · " + format(meal.optDouble("kcal"), 1) + " kcal";
    }

    private void resendPc(WindowsSync.Pairing pairing, Journal.SyncState state, String id,
                          JSONArray changes, JSONArray conflicts, boolean keepPhone) {
        try {
            syncPc(pairing, state, id, WindowsSync.resolve(changes, conflicts, keepPhone));
        } catch (Exception error) {
            pcError("Impossible de préparer ces modifications.");
        }
    }

    private void pcError(String message) {
        new AlertDialog.Builder(this).setTitle("Synchro interrompue").setMessage(message)
                .setPositiveButton("Fermer", null).show();
    }

    private void showAi(LinearLayout page) {
        title(page, "Bilan IA", "Historique et partage");
        LinearLayout history = panel(page, WHITE, 17);
        add(history, heading("Bilans enregistrés", 22, INK), 0);
        List<Journal.SavedReport> all = journal.reports();
        if (all.isEmpty()) {
            add(history, text("Demande à ton IA d’enregistrer un bilan dans Pause sur le PC, puis synchronise.", 14, MUTED), 6);
        } else {
            add(history, text(all.size() + (all.size() == 1 ? " bilan" : " bilans"), 14, MUTED), 5);
            add(history, button("Ouvrir les bilans", MINT, ACCENT, () -> showReports(all)), 10);
        }
        LinearLayout live = panel(page, FOREST, 17);
        add(live, heading("Partager par USB", 24, WHITE), 0);
        add(live, text("Branche le téléphone à ton ordinateur, puis ouvre l’accès le temps de demander ton bilan à ton IA.",
                14, LIGHT_MINT), 7);
        if (bridge.active()) {
            add(live, button("Fermer l’accès", LIGHT_MINT, FOREST, () -> stopShare(true)), 13);
            add(live, text("Accès ouvert pour 10 minutes maximum. Garde cet écran ouvert.", 13, LIGHT_MINT), 7);
        } else {
            add(live, button("Ouvrir l’accès pour 10 min", LIGHT_MINT, FOREST, this::startShare), 13);
        }

        LinearLayout manual = panel(page, WHITE, 13);
        add(manual, heading("Première connexion", 20, INK), 0);
        add(manual, text("Associe le connecteur sur ton ordinateur avec le code de ce téléphone.", 14, MUTED), 5);
        add(manual, button("Afficher le code", MINT, ACCENT, this::showPairing), 10);

        LinearLayout export = panel(page, WHITE, 13);
        add(export, heading("Export JSON", 20, INK), 0);
        add(export, text("Enregistre un fichier de bilan à joindre à une conversation ou à lire avec le connecteur.", 14, MUTED), 5);
        add(export, button("Exporter le bilan", PAPER, INK, this::export), 10);
        add(page, text("Le bilan contient les jours de pause, les pesées et les totaux de calories saisis par jour.",
                13, MUTED), 13);
    }

    private void showReports(List<Journal.SavedReport> reports) {
        String[] titles = new String[reports.size()];
        for (int i = 0; i < reports.size(); i++) {
            Journal.SavedReport report = reports.get(i);
            titles[i] = report.title() + "\n" + HISTORY_DATE.format(report.start()) + " – " + HISTORY_DATE.format(report.end());
        }
        new AlertDialog.Builder(this).setTitle("Bilans enregistrés").setItems(titles, (dialog, which) -> {
            Journal.SavedReport report = reports.get(which);
            LinearLayout form = form();
            add(form, text(HISTORY_DATE.format(report.start()) + " – " + HISTORY_DATE.format(report.end()), 13, MUTED), 0);
            TextView body = text(report.text(), 15, INK);
            body.setTextIsSelectable(true);
            add(form, body, 8);
            new AlertDialog.Builder(this).setTitle(report.title()).setView(scrollingForm(form))
                    .setPositiveButton("Fermer", null).show();
        }).setNegativeButton("Fermer", null).show();
    }

    private void startShare() {
        try {
            bridge.start();
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            handler.removeCallbacks(expireShare);
            handler.postDelayed(expireShare, 10 * 60 * 1000L);
            render(true);
        } catch (IOException error) {
            toast("Impossible d’ouvrir le partage. Réessaie.");
        }
    }

    private void stopShare(boolean update) {
        bridge.stop();
        handler.removeCallbacks(expireShare);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (update && tab == AI && visible) render(true);
    }

    private void showPairing() {
        String token = bridge.token();
        LinearLayout form = form();
        add(form, text("Entre ce code dans la commande d’association sur ton ordinateur.", 14, MUTED), 0);
        TextView code = text(token, 19, INK);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        add(form, code, 10);
        new AlertDialog.Builder(this).setTitle("Code du téléphone").setView(form)
                .setNegativeButton("Fermer", null)
                .setNeutralButton("Réinitialiser", (dialog, which) -> {
                    bridge.refreshToken();
                    stopShare(true);
                    showPairing();
                })
                .setPositiveButton("Copier", (dialog, which) -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    ClipData content = ClipData.newPlainText("Code du connecteur Pause", token);
                    if (Build.VERSION.SDK_INT >= 33) {
                        android.os.PersistableBundle extras = new android.os.PersistableBundle();
                        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                        content.getDescription().setExtras(extras);
                    }
                    clipboard.setPrimaryClip(content);
                }).show();
    }

    private void export() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "pause-bilan.json");
        try {
            startActivityForResult(intent, EXPORT);
        } catch (ActivityNotFoundException error) {
            toast("Aucun gestionnaire de fichiers disponible.");
        }
    }

    private void choosePhoto(boolean barcode, LocalDate day) {
        String[] choices = barcode ? new String[]{"Prendre une photo", "Choisir une image", "Taper le code-barres"}
                : new String[]{"Prendre une photo", "Choisir une image"};
        new AlertDialog.Builder(this).setTitle(barcode ? "Code-barres du produit" : "Tableau nutritionnel")
                .setItems(choices, (dialog, selected) -> {
                    if (selected == 2) {
                        manualBarcode(day);
                        return;
                    }
                    photoPairing = false;
                    photoBarcode = barcode;
                    photoDate = day;
                    if (selected == 0) capture();
                    else chooseImage();
                }).setNegativeButton("Annuler", null).show();
    }

    private void capture() {
        File directory = new File(getCacheDir(), "camera");
        try {
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cache inaccessible.");
            File file = File.createTempFile("pause_", ".jpg", directory);
            photoPath = file.getAbsolutePath();
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".photos", file);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.setClipData(ClipData.newRawUri("Photo de produit", uri));
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, CAMERA);
        } catch (IOException | ActivityNotFoundException error) {
            removePhoto(photoPath);
            photoPath = null;
            toast("Appareil photo indisponible. Essaie une image existante.");
        }
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, GALLERY);
        } catch (ActivityNotFoundException error) {
            toast("Aucun sélecteur d’images disponible.");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CAMERA) {
            String path = photoPath;
            photoPath = null;
            if (path == null) return;
            if (result == RESULT_OK) {
                Uri uri = Uri.fromFile(new File(path));
                if (photoPairing) analyzePairing(uri, path);
                else analyze(uri, path, photoBarcode, photoDate == null ? foodDate : photoDate);
            } else {
                removePhoto(path);
            }
        } else if (request == GALLERY && result == RESULT_OK && data != null && data.getData() != null) {
            if (photoPairing) analyzePairing(data.getData(), null);
            else analyze(data.getData(), null, photoBarcode, photoDate == null ? foodDate : photoDate);
        } else if (request == EXPORT && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            worker.execute(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException();
                    output.write(journal.snapshot().toString(2).getBytes(StandardCharsets.UTF_8));
                    runOnUiThread(() -> toast("Bilan exporté."));
                } catch (Exception error) {
                    runOnUiThread(() -> toast("Impossible d’enregistrer le bilan."));
                }
            });
        }
    }

    private void removePhoto(String file) {
        if (file != null) new File(file).delete();
    }

    private void analyze(Uri uri, String path, boolean barcode, LocalDate day) {
        setProcessing(true);
        worker.execute(() -> {
            if (barcode) {
                String code;
                try {
                    code = FoodRecognition.barcode(this, uri);
                } catch (Exception error) {
                    completeRecognition(day, null, true, "Code illisible. Rapproche le téléphone ou tape le code.");
                    return;
                } finally {
                    removePhoto(path);
                }
                fetchProduct(day, code);
                return;
            }
            TextRecognizer recognizer = null;
            try {
                InputImage image = InputImage.fromFilePath(this, uri);
                recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                TextRecognizer client = recognizer;
                client.process(image).addOnCompleteListener(result -> {
                    client.close();
                    removePhoto(path);
                    if (result.isSuccessful()) {
                        completeRecognition(day, FoodRecognition.label(result.getResult().getText()), false, null);
                    } else {
                        completeRecognition(day, null, false, "Étiquette illisible. Tu peux saisir les calories à la main.");
                    }
                });
            } catch (Exception error) {
                if (recognizer != null) recognizer.close();
                removePhoto(path);
                completeRecognition(day, null, false, "Impossible de lire l’image.");
            }
        });
    }

    private void manualBarcode(LocalDate day) {
        LinearLayout form = form();
        EditText input = field(form, "Chiffres sous le code-barres", "", InputType.TYPE_CLASS_NUMBER, 14);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Code-barres").setView(form)
                .setNegativeButton("Annuler", null).setPositiveButton("Rechercher", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = input.getText().toString().trim();
            if (!FoodRecognition.CODE.matcher(code).matches()) {
                input.setError("Vérifie les chiffres du code.");
                return;
            }
            dialog.dismiss();
            setProcessing(true);
            worker.execute(() -> fetchProduct(day, code));
        });
    }

    private void fetchProduct(LocalDate day, String code) {
        FoodRecognition.Product product;
        try {
            product = FoodRecognition.lookup(code);
        } catch (Exception error) {
            product = null;
        }
        if (product != null && product.kcalPer100() != null) {
            completeRecognition(day, product, false, null);
            return;
        }
        String name = product == null ? "" : product.name().replaceAll("\\s+", " ").trim();
        if (name.codePointCount(0, name.length()) > 160) name = name.substring(0, name.offsetByCodePoints(0, 160));
        String description = "Code-barres " + code + (name.isEmpty() ? "" : "\n" + name);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            journal.addDraft(day, description);
            setProcessing(false);
            toast("Calories indisponibles. Produit gardé pour la prochaine synchro.");
        });
    }

    private void setProcessing(boolean processing) {
        this.processing = processing;
        if (tab == FOOD) render(true);
    }

    private void completeRecognition(LocalDate day, FoodRecognition.Product product, boolean retryCode, String message) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            setProcessing(false);
            if (product != null) {
                editMeal(day, null, product);
                return;
            }
            AlertDialog.Builder dialog = new AlertDialog.Builder(this).setMessage(message)
                    .setNegativeButton("Fermer", null)
                    .setPositiveButton(retryCode ? "Taper le code" : "Saisir à la main", (d, which) -> {
                        if (retryCode) manualBarcode(day);
                        else editMeal(day, null, null);
                    });
            if (!retryCode) dialog.setNeutralButton("Lire l’étiquette", (d, which) -> choosePhoto(false, day));
            dialog.show();
        });
    }

    private void editMeal(LocalDate day, Journal.Meal previous, FoodRecognition.Product product) {
        LinearLayout form = form();
        if (product != null) add(form, text(product.note(), 13, MUTED), 0);
        String name = previous != null ? previous.name() : product == null ? "" : product.name();
        EditText nameInput = field(form, "Nom de l’aliment", name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 90);
        Double energy = previous != null ? previous.kcalPer100() : product == null ? null : product.kcalPer100();
        RadioGroup method = new RadioGroup(this);
        method.setOrientation(RadioGroup.VERTICAL);
        RadioButton whole = radio("Calories de la portion");
        RadioButton portion = radio("Valeur pour 100 g ou 100 ml");
        method.addView(whole);
        method.addView(portion);
        add(form, method, 4);
        LinearLayout totalForm = column(), portionForm = column();
        add(form, totalForm, 0);
        add(form, portionForm, 0);
        EditText kcal = field(totalForm, "Calories de la portion (kcal)",
                previous == null ? "" : decimalInput(previous.kcal()), decimalType(), 10);
        EditText per100 = field(portionForm, "Calories pour 100 g ou 100 ml", decimalInput(energy), decimalType(), 10);
        EditText amount = field(portionForm, "Quantité consommée (g ou ml)",
                previous == null ? "" : decimalInput(previous.amount()), decimalType(), 10);
        method.setOnCheckedChangeListener((group, checked) -> {
            boolean usePortion = checked == portion.getId();
            totalForm.setVisibility(usePortion ? View.GONE : View.VISIBLE);
            portionForm.setVisibility(usePortion ? View.VISIBLE : View.GONE);
        });
        method.check(energy != null || product != null ? portion.getId() : whole.getId());
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(previous == null ? "Nouvel aliment" : "Modifier l’aliment")
                .setView(scrollingForm(form)).setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null);
        if (previous != null) builder.setNeutralButton("Supprimer", (d, which) -> confirmDeletion("Supprimer cet aliment ?", () -> {
            journal.deleteMeal(day, previous);
            render(true);
        }));
        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = nameInput.getText().toString().trim();
            if (title.isEmpty()) {
                nameInput.setError("Indique le nom de l’aliment.");
                return;
            }
            boolean usePortion = method.getCheckedRadioButtonId() == portion.getId();
            Double basis = null, consumed = null;
            double total;
            if (usePortion) {
                basis = number(per100);
                consumed = number(amount);
                if (!Double.isFinite(basis) || basis < 0 || basis > 1000) {
                    per100.setError("Indique la valeur en kcal pour 100.");
                    return;
                }
                if (!Double.isFinite(consumed) || consumed <= 0 || consumed > 10000) {
                    amount.setError("Indique la quantité dans la même unité que l’étiquette.");
                    return;
                }
                total = basis * consumed / 100;
            } else {
                total = number(kcal);
                if (!Double.isFinite(total) || total < 0 || total > 10000) {
                    kcal.setError("Indique les calories de la portion.");
                    return;
                }
            }
            String source = previous != null ? previous.source() : product == null ? "Manuel" : product.source();
            journal.saveMeal(day, previous, title, total, consumed, basis, source);
            foodDate = day;
            if (tab == AI) stopShare(false);
            tab = FOOD;
            dialog.dismiss();
            render(true);
        });
    }

    private RadioButton radio(String label) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTextColor(INK);
        return button;
    }

    private int decimalType() {
        return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
    }

    private void confirmDeletion(String message, Runnable delete) {
        new AlertDialog.Builder(this).setMessage(message).setNegativeButton("Annuler", null)
                .setPositiveButton("Supprimer", (dialog, which) -> delete.run()).show();
    }

    private void pickDate(LocalDate initial, boolean priorOnly, Consumer<LocalDate> result) {
        DatePickerDialog picker = new DatePickerDialog(this,
                (view, year, month, day) -> result.accept(LocalDate.of(year, month + 1, day)),
                initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        if (priorOnly) picker.getDatePicker().setMaxDate(System.currentTimeMillis());
        picker.show();
    }

    private String displayDate(LocalDate date) {
        String result = FULL_DATE.format(date);
        return result.substring(0, 1).toUpperCase(FR) + result.substring(1);
    }

    private static String format(double value, int decimals) {
        NumberFormat number = NumberFormat.getNumberInstance(FR);
        number.setMaximumFractionDigits(decimals);
        return number.format(Math.abs(value) < 0.00001 ? 0 : value);
    }

    private static String decimalInput(Double value) {
        return value == null ? "" : java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static double number(EditText input) {
        try {
            return Double.parseDouble(input.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }

    private EditText field(LinearLayout parent, String label, String value, int type, int maxLength) {
        TextView caption = text(label, 13, MUTED);
        add(parent, caption, 10);
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        input.setContentDescription(label);
        input.setTextSize(16);
        input.setTextColor(INK);
        input.setSingleLine(true);
        input.setInputType(type);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        if ((type & InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0 && (type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER) {
            input.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789,."));
            input.setRawInputType(type);
        }
        input.setText(value);
        add(parent, input, 0);
        return input;
    }

    private ScrollView scrollingForm(LinearLayout form) {
        ScrollView container = new ScrollView(this);
        container.addView(form);
        return container;
    }

    private LinearLayout form() {
        LinearLayout form = column();
        form.setPadding(dp(22), dp(10), dp(22), dp(10));
        return form;
    }

    private LinearLayout panel(LinearLayout parent, int color, int margin) {
        LinearLayout panel = column();
        if (color == FOREST) {
            GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.BL_TR,
                    new int[]{0xFF133B2D, 0xFF275B3F});
            background.setCornerRadius(dp(22));
            panel.setBackground(background);
        } else {
            panel.setBackground(shape(color == WHITE ? SURFACE : color, 20, color == WHITE ? LINE : 0));
        }
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        add(parent, panel, margin);
        return panel;
    }

    private void add(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(child, params);
    }

    private void weighted(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginStart(dp(margin));
        parent.addView(child, params);
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setBaselineAligned(false);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView heading(String label, int size, int color) {
        TextView view = text(label, size, color);
        view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return view;
    }

    private TextView text(String label, int size, int color) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        return view;
    }

    private TextView button(String label, int fill, int color, Runnable action) {
        android.widget.Button button = new android.widget.Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(color);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinimumWidth(0);
        button.setPadding(dp(9), dp(7), dp(9), dp(7));
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26285C44), shape(fill, 12, 0), null));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private TextView link(String label, Runnable action) {
        return button(label, Color.TRANSPARENT, ACCENT, action);
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (stroke != 0) background.setStroke(dp(1), stroke);
        return background;
    }

    private int dp(float pixels) {
        return Math.round(pixels * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
