package fr.pause.journal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.core.content.FileProvider;

import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

public final class MainActivity extends Activity {
    private static final int PAPER = PaperStyle.PAPER, SURFACE = PaperStyle.SURFACE, FIELD = PaperStyle.FIELD;
    private static final int INK = PaperStyle.INK, MUTED = PaperStyle.MUTED, LINE = PaperStyle.LINE;
    private static final int BRAND = PaperStyle.BRAND, ACCENT = PaperStyle.ACCENT;
    private static final int SOFT_LINE = 0xFFE8D8B8, SIDE = 0xFFEBDABB;
    private static final int GOLD = 0xFFFBE3A0, GOLD_EDGE = 0xFFDDBE6C;
    private static final int GREEN = 0xFFD5EBC6, GREEN_MID = 0xFF6E9A55, GREEN_INK = 0xFF22401A;
    private static final int PEACH = 0xFFF6D3C4, PEACH_INK = 0xFF5A2412;
    private static final int VIOLET = 0xFFE3DDF5, VIOLET_INK = 0xFF453876;
    private static final int CAMERA = 10, GALLERY = 11, EXPORT = 12;
    private static final int NOTIFICATION_PERMISSION = 13;
    private static final int DRIVE_PERMISSION = 14;
    private static final int TODAY = 0, TRACKS = 1, TRENDS = 2, SETTINGS = 3;
    private static final int NONE = 0, DRIVE = 1, WIFI = 2, USB = 3;
    private static final String[] WEEKDAYS = {"L", "M", "M", "J", "V", "S", "D"};
    private static final Locale FR = Locale.FRANCE;
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", FR);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", FR);
    private static final DateTimeFormatter HISTORY_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", FR);
    private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("EEEE d MMM", FR);
    private static final DateTimeFormatter HEADER_DATE = DateTimeFormatter.ofPattern("d MMMM", FR);

    private Journal journal;
    private Bridge bridge;
    private LinearLayout root;
    private LinearLayout navigation;
    private ScrollView scroll;
    private MealComposer composer;
    private LinearLayout weightCard;
    private EditText weightInput;
    private Object backCallback;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService chatgptWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService driveWorker = Executors.newSingleThreadExecutor();
    private ChatGptPlan chatgpt;
    private DriveSync drive;
    private AuthorizationClient driveAuthorization;
    private boolean driveBusy, driveNeedsConsent, visible;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable driveAfterEdit = () -> startDrive(false, null);
    private final Runnable drivePolling = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            startDrive(false, null);
            handler.postDelayed(this, 120_000L);
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener journalChanged = (preferences, key) -> {
        if (visible && drive.connected() && "data".equals(key)) {
            handler.removeCallbacks(driveAfterEdit);
            handler.postDelayed(driveAfterEdit, 4_000L);
        }
    };
    private boolean chatgptAccountBusy;
    private final Runnable expireShare = () -> stopShare(true);
    private int tab = TODAY, section = NONE;
    private int chart, range = 30;
    private boolean keyboard, refreshPending, revealWeight, pauseEditing, weightEditing;
    private String weightDraft = "";
    private YearMonth month;
    private LocalDate currentDay, day;
    private LocalDate streakStart;
    private int streakTier;
    private String photoPath;
    private boolean photoBarcode;
    private boolean photoPairing;
    private LocalDate photoDate;
    private boolean processing;
    private SharedPreferences desktop;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        journal = Journal.open(this);
        chatgpt = new ChatGptPlan(this);
        drive = new DriveSync(this, journal);
        driveAuthorization = Identity.getAuthorizationClient(this);
        bridge = new Bridge(this, journal);
        desktop = getSharedPreferences("pause.desktop", MODE_PRIVATE);
        currentDay = day = today();
        if (state != null) {
            tab = state.getInt("page");
            section = state.getInt("section");
            chart = state.getInt("chart");
            range = state.getInt("range", 30);
            weightEditing = state.getBoolean("weightEditing");
            weightDraft = state.getString("weightDraft", "");
            day = LocalDate.parse(state.getString("day", today().toString()));
            currentDay = LocalDate.parse(state.getString("currentDay", today().toString()));
            photoPath = state.getString("photoPath");
            photoBarcode = state.getBoolean("photoBarcode");
            photoPairing = state.getBoolean("photoPairing");
            String date = state.getString("photoDate");
            if (date != null) photoDate = LocalDate.parse(date);
        }
        else openReminder(getIntent());
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = column();
        root.setBackground(new PaperStyle.Backdrop(this));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        root.setFocusableInTouchMode(true);
        root.setDefaultFocusHighlightEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        composer = new MealComposer(this, new MealComposer.Actions() {
            @Override public void more() { moreWaysToAdd(); }
            @Override public void scan() { choosePhoto(true, day); }
            @Override public void send(String description) { addFromComposer(description, false); }
        });
        if (state != null) composer.setText(state.getString("composer", ""));
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, -2);
        bar.setMargins(dp(14), dp(4), dp(14), dp(10));
        root.addView(composer, bar);
        navigation = row();
        GradientDrawable shelf = shape(SURFACE, 0, 0);
        LayerDrawable edge = new LayerDrawable(new Drawable[]{shape(SOFT_LINE, 0, 0), shelf});
        edge.setLayerInset(1, 0, dp(1), 0, 0);
        navigation.setBackground(edge);
        navigation.setPadding(dp(9), dp(6), dp(9), dp(6));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                keyboard = insets.isVisible(WindowInsets.Type.ime());
                if (Build.VERSION.SDK_INT >= 35) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    view.setPadding(bars.left, bars.top, bars.right, keyboard ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0);
                    navigation.setPadding(dp(9), dp(6), dp(9), dp(6) + bars.bottom);
                }
                showBars();
                return insets;
            });
        }
        setContentView(root);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", tab);
        state.putInt("section", section);
        state.putInt("chart", chart);
        state.putInt("range", range);
        state.putBoolean("weightEditing", weightEditing);
        state.putString("weightDraft", weightDraft);
        state.putString("composer", composer.text());
        state.putString("day", day.toString());
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
        if (day.equals(currentDay)) day = now;
        currentDay = now;
        render(true);
        getSharedPreferences("journal", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(journalChanged);
        handler.post(drivePolling);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (openReminder(intent)) {
            stopShare(false);
            render(false);
        }
    }

    private boolean openReminder(Intent intent) {
        revealWeight = JournalReminder.OPEN_WEIGHT.equals(intent.getAction());
        if (!revealWeight && !JournalReminder.OPEN_TODAY.equals(intent.getAction())) return false;
        tab = TODAY;
        section = NONE;
        day = today();
        return true;
    }

    @Override
    protected void onStop() {
        visible = false;
        getSharedPreferences("journal", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(journalChanged);
        handler.removeCallbacks(drivePolling);
        handler.removeCallbacks(driveAfterEdit);
        stopShare(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        bridge.stop();
        worker.shutdownNow();
        chatgptWorker.shutdownNow();
        driveWorker.shutdown();
        super.onDestroy();
    }

    private LocalDate today() {
        return LocalDate.now();
    }

    private void go(int tab, int section) {
        if (section != USB) stopShare(false);
        composer.hideKeyboard();
        this.tab = tab;
        this.section = section;
        render(false);
    }

    private void openDay(LocalDate date) {
        day = date;
        pauseEditing = weightEditing = false;
        weightDraft = "";
        go(TODAY, NONE);
    }

    private void refresh() {
        if (scroll.findFocus() instanceof EditText) refreshPending = true;
        else render(true);
    }

    private void render(boolean keepScroll) {
        refreshPending = false;
        int position = keepScroll ? scroll.getScrollY() : 0;
        scroll.removeAllViews();
        weightCard = null;
        LinearLayout page = column();
        page.setPadding(dp(18), dp(10), dp(18), dp(18));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        switch (tab) {
            case TODAY -> showToday(page);
            case TRACKS -> showTracks(page);
            case TRENDS -> showTrends(page);
            default -> {
                switch (section) {
                    case DRIVE -> showDrive(page);
                    case WIFI -> showWifi(page);
                    case USB -> showUsb(page);
                    default -> showSettings(page);
                }
            }
        }
        scroll.post(() -> {
            boolean weight = revealWeight && weightCard != null;
            scroll.scrollTo(0, weight ? weightCard.getTop() : position);
            revealWeight = false;
        });
        showNavigation();
        showBars();
        updateBack();
    }

    private void showNavigation() {
        navigation.removeAllViews();
        String[] names = {"Aujourd’hui", "Suivis", "Évolution", "Réglages"};
        JournalIcon.Kind[] icons = {JournalIcon.Kind.SUN, JournalIcon.Kind.CHECKS, JournalIcon.Kind.CHART, JournalIcon.Kind.SLIDERS};
        for (int i = 0; i < names.length; i++) {
            int destination = i;
            boolean selected = tab == i;
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER);
            item.setSelected(selected);
            item.setFocusable(true);
            item.setContentDescription(names[i]);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x209A3412), null, shape(Color.WHITE, 14, 0)));
            LinearLayout pill = row();
            pill.setGravity(Gravity.CENTER);
            pill.setBackground(shape(selected ? GOLD : Color.TRANSPARENT, 15, 0));
            pill.addView(new JournalIcon(this, icons[i], selected ? ACCENT : MUTED), new LinearLayout.LayoutParams(dp(22), dp(22)));
            item.addView(pill, new LinearLayout.LayoutParams(dp(58), dp(30)));
            TextView label = heading(names[i], 12, selected ? ACCENT : MUTED);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(label);
            item.setOnClickListener(view -> {
                if (tab == destination && section == NONE) scroll.smoothScrollTo(0, 0);
                else go(destination, NONE);
            });
            navigation.addView(item, new LinearLayout.LayoutParams(0, dp(58), 1));
        }
    }

    private void showBars() {
        navigation.setVisibility(keyboard ? View.GONE : View.VISIBLE);
        composer.setVisibility(tab == TODAY && (!keyboard || composer.hasFocus()) ? View.VISIBLE : View.GONE);
    }

    private void updateBack() {
        if (Build.VERSION.SDK_INT < 33) return;
        boolean needed = tab != TODAY;
        if (needed == (backCallback != null)) return;
        if (needed) {
            OnBackInvokedCallback callback = this::goBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            backCallback = callback;
        } else {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
    }

    private void goBack() {
        if (section != NONE) go(SETTINGS, NONE);
        else go(TODAY, NONE);
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        if (tab == TODAY) super.onBackPressed();
        else goBack();
    }

    private LinearLayout pageHeader(LinearLayout page, String title) {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(48));
        TextView name = heading(title, 28, INK);
        if (Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        add(page, header, 0);
        return header;
    }

    private void subpageHeader(LinearLayout page, String title) {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(iconButton(JournalIcon.Kind.LEFT, "Retour aux réglages", this::goBack), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView name = heading(title, 25, INK);
        if (Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMarginStart(dp(-12));
        page.addView(header, params);
    }

    private void showToday(LinearLayout page) {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout date = row();
        date.setGravity(Gravity.CENTER_VERTICAL);
        date.setMinimumHeight(dp(48));
        date.setContentDescription(displayDate(day) + ", choisir une date");
        date.setOnClickListener(view -> pickDate(day, true, this::openDay));
        TextView name = heading(capital(TITLE_DATE.format(day)), 24, INK);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        date.addView(name);
        date.addView(new JournalIcon(this, JournalIcon.Kind.DOWN, MUTED), new LinearLayout.LayoutParams(dp(22), dp(22)));
        header.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
        if (day.equals(today())) header.addView(syncState());
        else header.addView(button("Aujourd’hui", FIELD, ACCENT, () -> openDay(today())));
        add(page, header, 0);
        showWeek(page);
        showPauseAnswer(page);
        showMeals(page);
        showHabits(page);
        showWeight(page);
    }

    private View syncState() {
        boolean trouble = drive.connected() && (driveNeedsConsent || !drive.conflicts().isEmpty());
        String label = !drive.connected() ? "Non relié" : driveBusy ? "Synchro…" : trouble ? "À vérifier" : "Drive";
        LinearLayout state = row();
        state.setGravity(Gravity.CENTER_VERTICAL);
        state.setMinimumHeight(dp(48));
        state.setContentDescription(drive.status());
        state.setOnClickListener(view -> go(SETTINGS, DRIVE));
        View dot = new View(this);
        dot.setBackground(shape(trouble ? PEACH_INK : drive.connected() ? GREEN_MID : LINE, 4, 0));
        state.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView text = text(label, 13, MUTED);
        text.setPadding(dp(6), 0, 0, 0);
        state.addView(text);
        return state;
    }

    private void showWeek(LinearLayout page) {
        LocalDate now = today(), monday = day.minusDays(day.getDayOfWeek().getValue() - 1L);
        WeekStrip week = new WeekStrip(this, direction -> {
            LocalDate target = day.plusWeeks(direction);
            openDay(target.isAfter(now) ? now : target);
        });
        for (int i = 0; i < 7; i++) {
            LocalDate date = monday.plusDays(i);
            boolean future = date.isAfter(now), selected = date.equals(day);
            String status = journal.status(date);
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, dp(6), 0, dp(8));
            cell.setAlpha(future ? .45f : 1f);
            cell.setSelected(selected);
            cell.setBackground(new RippleDrawable(ColorStateList.valueOf(0x209A3412),
                    selected ? shape(SURFACE, 16, BRAND) : null, shape(Color.WHITE, 16, 0)));
            TextView letter = text(WEEKDAYS[i], 13, MUTED), number = heading(Integer.toString(date.getDayOfMonth()), 17, INK);
            letter.setGravity(Gravity.CENTER);
            number.setGravity(Gravity.CENTER);
            cell.addView(letter);
            cell.addView(number);
            View dot = new View(this);
            boolean waiting = status.isEmpty() && !future && journal.inPause(date);
            dot.setBackground(shape(status.equals("tenu") ? GREEN_MID : status.isEmpty() ? Color.TRANSPARENT : PEACH_INK, 4, waiting ? BRAND : 0));
            LinearLayout.LayoutParams mark = new LinearLayout.LayoutParams(dp(7), dp(7));
            mark.topMargin = dp(3);
            cell.addView(dot, mark);
            cell.setContentDescription(displayDate(date) + (status.isEmpty() ? waiting ? ", sans réponse" : "" : ", " + statusLabel(status)));
            if (!future) cell.setOnClickListener(view -> openDay(date));
            week.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        add(page, week, 4);
    }

    private void showPauseAnswer(LinearLayout page) {
        LocalDate start = journal.start(), now = today();
        if (start == null) return;
        LinearLayout card = goldCard(page, 12);
        pauseHeader(card);
        boolean inside = journal.inPause(day);
        String status = journal.status(day);
        boolean asking = inside && (status.isEmpty() || pauseEditing);
        String moment = day.equals(now) ? "aujourd’hui" : "ce jour-là";
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(text(day.isBefore(start) ? "Elle débute le " + HEADER_DATE.format(start) + "."
                : day.isAfter(journal.end()) ? "Terminée le " + HEADER_DATE.format(journal.end()) + "."
                : asking ? "Jour " + journal.elapsed(day) + " sur " + journal.duration() + ". Tu as tenu " + moment + " ?"
                : capital(statusLabel(status)) + " " + moment, 15, INK), new LinearLayout.LayoutParams(0, -2, 1));
        if (inside && !asking) line.addView(link("Modifier", () -> {
            pauseEditing = true;
            render(true);
        }));
        add(card, line, 2);
        if (!asking) {
            card.setPadding(dp(18), dp(18), dp(18), dp(10));
            return;
        }
        LinearLayout actions = row();
        boolean kept = status.equals("tenu"), notKept = status.equals(Journal.NOT_KEPT_STATUS);
        TextView yes = button("Tenu", kept ? GREEN_INK : GREEN, kept ? Color.WHITE : GREEN_INK, () -> mark(day, "tenu"));
        TextView no = button("Non tenu", notKept ? PEACH_INK : PEACH, notKept ? Color.WHITE : PEACH_INK, () -> mark(day, Journal.NOT_KEPT_STATUS));
        yes.setSelected(kept);
        no.setSelected(notKept);
        weighted(actions, yes, 0);
        weighted(actions, no, 10);
        add(card, actions, 10);
        if (!status.isEmpty()) add(card, link("Effacer la réponse", () -> mark(day, "")), 2);
    }

    private void pauseHeader(LinearLayout card) {
        LocalDate now = today();
        int streak = journal.streak(now), tier = StreakBadge.tier(streak);
        boolean reached = journal.start().equals(streakStart) && tier > streakTier;
        streakStart = journal.start();
        streakTier = tier;
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(heading("Ma pause", 18, INK), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(new StreakBadge(this, streak, reached));
        add(card, head, 0);
    }

    private void showMeals(LinearLayout page) {
        LinearLayout card = card(page, 12);
        List<Journal.Meal> meals = journal.meals(day);
        List<Journal.MealDraft> drafts = journal.drafts(day);
        Double target = journal.target(false);
        double total = meals.stream().mapToDouble(Journal.Meal::kcal).sum();
        LinearLayout head = row();
        head.addView(heading("Repas", 18, INK), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(heading(format(total, 0), 18, INK));
        TextView unit = text(target == null ? "kcal" : "sur " + format(target, 0) + " kcal", 14, MUTED);
        unit.setPadding(dp(5), dp(3), 0, 0);
        head.addView(unit);
        add(card, head, 0);
        if (target != null) add(card, progress((float) Math.min(1, total / target), SIDE), 10);
        if (processing) add(card, text("Lecture en cours…", 15, MUTED), 12);
        if (meals.isEmpty() && drafts.isEmpty())
            add(card, text("Rien de noté. Écris ce que tu as mangé juste en dessous, sans quantité ni calories si tu veux.", 15, MUTED), 10);
        LinearLayout rows = column();
        for (Journal.Meal meal : meals)
            addRow(rows, entry(null, meal.name(), null, text(format(meal.kcal(), 0) + " kcal", 15, MUTED), () -> editMeal(day, meal, null)));
        for (Journal.MealDraft draft : drafts) {
            String label = draft.description().length() > 90 ? draft.description().substring(0, 87) + "…" : draft.description();
            addRow(rows, entry(null, label, null, chip("à estimer"), () -> openDraft(draft)));
        }
        add(card, rows, 4);
    }

    private void openDraft(Journal.MealDraft draft) {
        new PaperStyle.DialogBuilder(this).setTitle("Repas à estimer")
                .setMessage(draft.description() + "\n\nAprès une synchro, le PC peut aussi proposer une estimation.")
                .setPositiveButton("Estimer ici", (dialog, which) -> meal(draft.date()).openDraft(draft))
                .setNegativeButton("Fermer", null)
                .setNeutralButton("Supprimer", (dialog, which) -> confirmDeletion("Supprimer ce repas en attente ?", () -> {
                    journal.deleteDraft(draft.id());
                    render(true);
                })).show();
    }

    private MealDescriptionDialog meal(LocalDate date) {
        return new MealDescriptionDialog(this, journal, date, () -> render(true), chatgpt, chatgptWorker, mealProvider());
    }

    private void addFromComposer(String description, boolean check) {
        LocalDate date = day;
        if (description.isEmpty()) {
            composer.showError("Écris ce que tu as mangé.");
            return;
        }
        MealDescriptionDialog meal = new MealDescriptionDialog(this, journal, date, () -> {
            composer.clear();
            render(true);
        }, chatgpt, chatgptWorker, mealProvider());
        if (!meal.add(description, check)) composer.showError("Indique entre 1 et 20 aliments.");
    }

    private void moreWaysToAdd() {
        String description = composer.text();
        List<String> labels = new ArrayList<>(List.of("Photographier l’étiquette", "Saisir les calories"));
        if (!description.isEmpty()) labels.addAll(List.of("Vérifier avant d’ajouter",
                mealProvider().equals("claude") ? "Garder pour Claude" : "Garder pour la synchro"));
        LocalDate date = day;
        new PaperStyle.DialogBuilder(this).setTitle("Ajouter autrement")
                .setItems(labels.toArray(new String[0]), (dialog, choice) -> {
                    switch (choice) {
                        case 0 -> choosePhoto(false, date);
                        case 1 -> editMeal(date, null, null);
                        case 2 -> addFromComposer(description, true);
                        default -> {
                            journal.addDraft(date, description);
                            composer.clear();
                            render(true);
                            toast("Repas gardé pour la prochaine synchro.");
                        }
                    }
                }).setNegativeButton("Annuler", null).show();
    }

    private void showHabits(LinearLayout page) {
        List<Tracker> active = journal.trackers().stream().filter(tracker -> tracker.includes(day)).collect(Collectors.toList());
        if (active.isEmpty()) return;
        LinearLayout card = card(page, 12);
        add(card, heading("Habitudes", 18, INK), 0);
        LinearLayout rows = column();
        for (Tracker tracker : active) {
            Tracker.Day status = journal.trackerDay(tracker, day);
            Tracker.Progress progress = journal.trackerProgress(tracker, today());
            String detail = status == Tracker.Day.MISSED || status == Tracker.Day.REST ? trackerLabel(tracker, status)
                    : tracker.duration() > 0 ? progress.done() + " sur " + (tracker.duration() - tracker.restDays())
                    : progress.streak() > 0 ? days(progress.streak()) + " d’affilée" : days(progress.done());
            FrameLayout tick = new FrameLayout(this);
            int fill = status == null ? FIELD : status == Tracker.Day.DONE ? GREEN_MID : status == Tracker.Day.MISSED ? PEACH : SIDE;
            GradientDrawable box = shape(fill, 13, 0);
            box.setStroke(dp(2), status == null ? LINE : fill);
            tick.setBackground(new RippleDrawable(ColorStateList.valueOf(0x229A3412), box, null));
            if (status != null) tick.addView(new JournalIcon(this, status == Tracker.Day.DONE ? JournalIcon.Kind.CHECK
                    : status == Tracker.Day.MISSED ? JournalIcon.Kind.CROSS : JournalIcon.Kind.MINUS,
                    status == Tracker.Day.DONE ? Color.WHITE : INK), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            tick.setContentDescription(tracker.name() + " · " + (status == null ? trackerLabel(tracker, Tracker.Day.DONE)
                    : "Effacer " + trackerLabel(tracker, status)));
            tick.setOnClickListener(view -> markTracker(tracker, day, status == null ? Tracker.Day.DONE : null));
            LinearLayout line = row();
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(entry(null, tracker.name(), detail, null, () -> editTrackerDay(tracker, day)), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(tick, new LinearLayout.LayoutParams(dp(46), dp(46)));
            line.setPadding(0, dp(4), 0, dp(4));
            addRow(rows, line);
        }
        add(card, rows, 2);
    }

    private void showWeight(LinearLayout page) {
        LinearLayout card = card(page, 12);
        weightCard = card;
        Double current = journal.weightAt(day);
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(heading("Poids", 18, INK), new LinearLayout.LayoutParams(0, -2, 1));
        Journal.Weight last = null;
        for (Journal.Weight weight : journal.weights()) if (weight.date().isBefore(day)) last = weight;
        if (last != null) head.addView(text(format(last.kg(), 1) + " kg le " + SHORT_DATE.format(last.date()), 14, MUTED));
        add(card, head, 0);
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        if (current != null && !weightEditing) {
            line.addView(heading(format(current, 1) + " kg", 27, INK), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(link("Modifier", () -> {
                weightEditing = true;
                weightDraft = decimalInput(current);
                render(true);
                scroll.post(() -> {
                    weightInput.requestFocus();
                    weightInput.setSelection(weightInput.length());
                    getSystemService(InputMethodManager.class).showSoftInput(weightInput, 0);
                });
            }));
            add(card, line, 4);
            return;
        }
        EditText input = new EditText(this);
        input.setHint("kg");
        input.setContentDescription("Poids en kilogrammes");
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        input.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789,."));
        input.setRawInputType(decimalType());
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        PaperStyle.field(input);
        input.setText(weightDraft);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { weightDraft = text.toString(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            saveWeight(input);
            return true;
        });
        View.OnFocusChangeListener edge = input.getOnFocusChangeListener();
        input.setOnFocusChangeListener((view, focus) -> {
            edge.onFocusChange(view, focus);
            if (!focus && refreshPending) handler.post(() -> { if (visible && refreshPending) refresh(); });
        });
        weighted(line, input, 0);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        size.setMarginStart(dp(10));
        line.addView(button("Noter", FIELD, ACCENT, () -> saveWeight(input)), size);
        add(card, line, 9);
        weightInput = input;
    }

    private void saveWeight(EditText input) {
        Double kg = null;
        if (!input.getText().toString().trim().isEmpty() || !weightEditing) {
            double value = number(input);
            if (!Double.isFinite(value) || value <= 0 || value > 1000) {
                input.setError("Indique un poids en kg.");
                return;
            }
            kg = value;
        }
        journal.saveWeight(day, kg);
        weightEditing = false;
        weightDraft = "";
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        render(true);
    }

    private void showTracks(LinearLayout page) {
        LinearLayout header = pageHeader(page, "Suivis");
        LinearLayout create = row();
        create.setGravity(Gravity.CENTER);
        create.setPadding(dp(12), 0, dp(16), 0);
        create.setContentDescription("Nouveau suivi");
        create.setBackground(new RippleDrawable(ColorStateList.valueOf(0x229A3412), shape(FIELD, 14, LINE), null));
        create.addView(new JournalIcon(this, JournalIcon.Kind.PLUS, ACCENT), new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView label = heading("Nouveau", 15, ACCENT);
        label.setPadding(dp(5), 0, 0, 0);
        create.addView(label);
        create.setOnClickListener(view -> editTracker(null));
        header.addView(create, new LinearLayout.LayoutParams(-2, dp(48)));
        showPause(page);
        List<Tracker> trackers = journal.trackers();
        if (trackers.isEmpty()) add(card(page, 12), text("Ajoute un suivi pour une habitude ou une pause. Par exemple, pratiquer l’anglais ou faire du sport.", 15, MUTED), 0);
        for (Tracker tracker : trackers) addTracker(page, tracker);
    }

    private void showPause(LinearLayout page) {
        LocalDate now = today(), start = journal.start(), end = journal.end();
        LinearLayout hero = goldCard(page, 8);
        if (start == null) {
            add(hero, heading("Ma pause", 18, INK), 0);
            add(hero, text("Choisis tes dates et note comment s’est passée chaque journée.", 15, MUTED), 4);
            add(hero, button("Démarrer une pause", BRAND, Color.WHITE, this::editGoal), 12);
            return;
        }
        pauseHeader(hero);
        int kept = journal.keptDays(now);
        add(hero, heading(kept + (kept == 1 ? " jour tenu" : " jours tenus"), 32, INK), 0);
        add(hero, text(now.isBefore(start) ? "Pause à venir" : now.isAfter(end) ? "Pause terminée"
                : "Jour " + journal.elapsed(now) + " sur " + journal.duration(), 14, MUTED), 0);
        add(hero, progress((float) kept / journal.duration(), GOLD_EDGE), 10);
        LinearLayout dates = row();
        dates.setGravity(Gravity.CENTER_VERTICAL);
        dates.addView(text("Du " + SHORT_DATE.format(start) + " au " + SHORT_DATE.format(end), 14, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        if (journal.duration() < Journal.MAX_PAUSE_DURATION) {
            TextView extend = link("Prolonger", this::extendGoal);
            extend.setContentDescription("Prolonger la pause");
            dates.addView(extend);
        }
        add(hero, dates, 2);
        hero.setPadding(dp(18), dp(18), dp(18), dp(10));
        if (now.isAfter(end)) {
            add(hero, button("Démarrer une nouvelle pause", BRAND, Color.WHITE, this::editGoal), 8);
            hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        }
        showMonth(page, start, end);
    }

    private void showMonth(LinearLayout page, LocalDate start, LocalDate end) {
        YearMonth first = YearMonth.from(start), last = YearMonth.from(end);
        if (month == null) month = YearMonth.now();
        if (month.isBefore(first)) month = first;
        if (month.isAfter(last)) month = last;
        LinearLayout card = card(page, 12);
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(monthButton(JournalIcon.Kind.LEFT, "Mois précédent", -1, month.isAfter(first)), new LinearLayout.LayoutParams(dp(40), dp(48)));
        TextView name = heading(capital(DateTimeFormatter.ofPattern(month.getYear() == today().getYear() ? "LLLL" : "LLLL yyyy", FR).format(month)), 18, INK);
        name.setGravity(Gravity.CENTER);
        name.setMinWidth(dp(96));
        head.addView(name);
        head.addView(monthButton(JournalIcon.Kind.RIGHT, "Mois suivant", 1, month.isBefore(last)), new LinearLayout.LayoutParams(dp(40), dp(48)));
        TextView change = link("Changer les dates", this::editGoal);
        change.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        head.addView(change, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(-10), dp(-8), 0, 0);
        card.addView(head, params);
        addWeekdays(card);
        LocalDate week = month.atDay(1);
        week = week.minusDays(week.getDayOfWeek().getValue() - 1L);
        while (!week.isAfter(month.atEndOfMonth())) {
            LinearLayout days = row();
            for (int i = 0; i < 7; i++) {
                LocalDate date = week.plusDays(i);
                addCalendarDay(days, date, YearMonth.from(date).equals(month));
            }
            add(card, days, 4);
            week = week.plusWeeks(1);
        }
    }

    private View monthButton(JournalIcon.Kind kind, String label, int offset, boolean enabled) {
        View button = iconButton(kind, label, () -> {
            month = month.plusMonths(offset);
            render(true);
        });
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .35f);
        return button;
    }

    private void addWeekdays(LinearLayout parent) {
        LinearLayout weekdays = row();
        for (String label : WEEKDAYS) {
            TextView cell = text(label, 13, MUTED);
            cell.setGravity(Gravity.CENTER);
            weekdays.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        add(parent, weekdays, 2);
    }

    private void addCalendarDay(LinearLayout days, LocalDate date, boolean shown) {
        int height = Math.max(dp(42), Math.round(dp(37) * getResources().getConfiguration().fontScale));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, 1);
        params.setMarginStart(dp(2));
        params.setMarginEnd(dp(2));
        if (!shown) {
            days.addView(new View(this), params);
            return;
        }
        LocalDate now = today();
        boolean inside = journal.inPause(date), future = date.isAfter(now);
        String status = inside ? journal.status(date) : "";
        boolean kept = status.equals("tenu"), notKept = status.equals(Journal.NOT_KEPT_STATUS);
        TextView cell = heading(Integer.toString(date.getDayOfMonth()), 15, kept ? GREEN_INK : notKept ? PEACH_INK : inside ? MUTED : LINE);
        cell.setGravity(Gravity.CENTER);
        cell.setAlpha(inside && future ? .55f : 1f);
        GradientDrawable fill = shape(kept ? GREEN : notKept ? PEACH : Color.TRANSPARENT, 12,
                date.equals(now) ? BRAND : inside && !future && status.isEmpty() ? SOFT_LINE : 0);
        if (date.equals(now)) fill.setStroke(dp(2), BRAND);
        cell.setBackground(fill);
        cell.setContentDescription(displayDate(date) + ", " + (!inside ? "hors pause" : future ? "à venir" : status.isEmpty() ? "sans réponse" : statusLabel(status)));
        if (inside && !future) cell.setOnClickListener(view -> openDay(date));
        days.addView(cell, params);
    }

    private void addTracker(LinearLayout page, Tracker tracker) {
        LocalDate now = today(), end = tracker.end();
        LinearLayout card = card(page, 12);
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(heading(tracker.name(), 18, INK), new LinearLayout.LayoutParams(0, -2, 1));
        LocalDate last = end != null && end.isBefore(now) ? end : now;
        if (!last.isBefore(tracker.start())) {
            TextView other = link("Autre date", () -> pickDate(last, true, date -> editTrackerDay(tracker, date)));
            other.setContentDescription(tracker.name() + ", choisir une autre date");
            head.addView(other);
        }
        TextView modify = link("Modifier", () -> editTracker(tracker));
        modify.setContentDescription("Modifier " + tracker.name());
        head.addView(modify);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(-8), 0, 0);
        card.addView(head, params);
        Tracker.Progress progress = journal.trackerProgress(tracker, now);
        List<String> facts = new ArrayList<>();
        if (tracker.pause()) facts.add("Pause");
        facts.add(tracker.duration() == 0 ? days(progress.done()) : progress.done() + " jours sur " + (tracker.duration() - tracker.restDays()));
        if (progress.streak() > 0) facts.add("série de " + progress.streak());
        if (tracker.restDays() > 0 || progress.rests() > 0) {
            int extra = progress.rests() - tracker.restDays();
            facts.add("repos " + progress.rests() + " sur " + tracker.restDays() + (extra > 0 ? ", " + extra + " hors quota" : ""));
        }
        facts.add(now.isBefore(tracker.start()) ? "débute le " + SHORT_DATE.format(tracker.start())
                : end != null ? "jusqu’au " + SHORT_DATE.format(end) : "depuis le " + SHORT_DATE.format(tracker.start()));
        add(card, text(String.join(" · ", facts), 14, MUTED), 0);
        if (last.isBefore(tracker.start())) return;
        LinearLayout history = row();
        for (int offset = 6; offset >= 0; offset--) addTrackerDay(history, tracker, last.minusDays(offset));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.setMargins(dp(-2), dp(10), dp(-2), 0);
        card.addView(history, size);
    }

    private void addTrackerDay(LinearLayout history, Tracker tracker, LocalDate date) {
        Tracker.Day status = journal.trackerDay(tracker, date);
        String symbol = status == Tracker.Day.DONE ? "✓" : status == Tracker.Day.MISSED ? "×" : status == Tracker.Day.REST ? "−" : "";
        TextView cell = heading(date.getDayOfMonth() + "\n" + symbol, 14,
                status == Tracker.Day.DONE ? GREEN_INK : status == Tracker.Day.MISSED ? PEACH_INK : MUTED);
        cell.setGravity(Gravity.CENTER);
        cell.setMinHeight(dp(48));
        GradientDrawable fill = shape(status == Tracker.Day.DONE ? GREEN : status == Tracker.Day.MISSED ? PEACH
                : status == Tracker.Day.REST ? SIDE : Color.TRANSPARENT, 12, status == null ? SOFT_LINE : 0);
        if (date.equals(today())) fill.setStroke(dp(2), BRAND);
        cell.setBackground(new RippleDrawable(ColorStateList.valueOf(0x229A3412), fill, null));
        cell.setContentDescription(tracker.name() + " · " + displayDate(date) + " · " + (status == null ? "Sans réponse" : trackerLabel(tracker, status)));
        boolean included = tracker.includes(date);
        cell.setAlpha(included ? 1f : .35f);
        if (included) cell.setOnClickListener(view -> editTrackerDay(tracker, date));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginStart(dp(2));
        params.setMarginEnd(dp(2));
        history.addView(cell, params);
    }

    private static String trackerLabel(Tracker tracker, Tracker.Day status) {
        return switch (status) {
            case DONE -> tracker.pause() ? "Tenue" : "Fait";
            case REST -> "Repos";
            case MISSED -> tracker.pause() ? "Non tenue" : "Non fait";
        };
    }

    private void showTrends(LinearLayout page) {
        pageHeader(page, "Évolution");
        add(page, segments(new String[]{"Poids", "Calories", "Pause"}, chart, 46, index -> {
            chart = index;
            render(true);
        }), 4);
        LocalDate now = today(), from = now.minusDays(range - 1L);
        LinearLayout card = card(page, 12);
        LinearLayout head = row();
        LinearLayout summary = column();
        head.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
        int[] ranges = {7, 30, 90};
        head.addView(segments(new String[]{"7 j", "30 j", "90 j"}, range == 7 ? 0 : range == 30 ? 1 : 2, 44, index -> {
            range = ranges[index];
            render(true);
        }), new LinearLayout.LayoutParams(dp(156), -2));
        add(card, head, 0);
        switch (chart) {
            case 0 -> showWeightTrend(card, summary, from);
            case 1 -> showCaloriesTrend(card, summary, from);
            default -> showPauseTrend(card, summary, from);
        }
        List<Journal.SavedReport> reports = journal.reports();
        add(card(page, 12), entry(JournalIcon.Kind.DOC, "Bilans", reports.isEmpty() ? "Aucun bilan enregistré" : "Dernier : " + reports.get(0).title(),
                chevron(), () -> {
                    if (!reports.isEmpty()) showReports(reports);
                    else new PaperStyle.DialogBuilder(this).setTitle("Bilans")
                            .setMessage("Demande à ton IA d’enregistrer un bilan dans Fil des jours sur le PC, puis synchronise.")
                            .setPositiveButton("Fermer", null).show();
                }), 0);
        showHistory(page, from, now);
    }

    private void summary(LinearLayout summary, String value, String detail) {
        add(summary, heading(value, 27, INK), 0);
        add(summary, text(detail, 14, MUTED), 0);
    }

    private void showWeightTrend(LinearLayout card, LinearLayout summary, LocalDate from) {
        List<Journal.Weight> weights = journal.weights().stream().filter(weight -> !weight.date().isBefore(from)).collect(Collectors.toList());
        Double target = journal.target(true);
        String goal = target == null ? "" : "\nObjectif " + format(target, 1) + " kg";
        if (weights.isEmpty()) {
            summary(summary, "—", "Aucune pesée" + goal);
            return;
        }
        Journal.Weight first = weights.get(0), last = weights.get(weights.size() - 1);
        double change = last.kg() - first.kg();
        summary(summary, format(last.kg(), 1) + " kg", (weights.size() == 1 ? "Le " + SHORT_DATE.format(last.date())
                : Math.abs(change) < .05 ? "Stable" : format(Math.abs(change), 1) + " kg de " + (change < 0 ? "moins" : "plus")) + goal);
        if (weights.size() < 2) {
            add(card, text("Deux pesées suffisent pour afficher la courbe.", 14, MUTED), 12);
            return;
        }
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(190));
        size.topMargin = dp(12);
        card.addView(new WeightChart(this, weights), size);
        LinearLayout dates = row();
        TextView end = text(SHORT_DATE.format(last.date()), 13, MUTED);
        dates.setPadding(dp(42), 0, 0, 0);
        dates.addView(text(SHORT_DATE.format(first.date()), 13, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        dates.addView(end);
        add(card, dates, 2);
    }

    private void showCaloriesTrend(LinearLayout card, LinearLayout summary, LocalDate from) {
        List<Double> totals = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        for (int i = 0; i < range; i++) {
            LocalDate date = from.plusDays(i);
            List<Journal.Meal> meals = journal.meals(date);
            totals.add(meals.isEmpty() ? null : meals.stream().mapToDouble(Journal.Meal::kcal).sum());
            dates.add(range == 7 ? Integer.toString(date.getDayOfMonth()) : SHORT_DATE.format(date));
        }
        Double target = journal.target(false);
        String goal = target == null ? "" : "\nObjectif " + format(target, 0);
        double[] logged = totals.stream().filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).toArray();
        if (logged.length == 0) {
            summary(summary, "—", "Aucune saisie" + goal);
            return;
        }
        summary(summary, format(java.util.Arrays.stream(logged).average().orElse(0), 0) + " kcal", "Moyenne par jour noté" + goal);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(200));
        size.topMargin = dp(12);
        card.addView(new DailyCaloriesChart(this, dates, totals, target), size);
    }

    private void showPauseTrend(LinearLayout card, LinearLayout summary, LocalDate from) {
        LocalDate start = journal.start(), now = today();
        if (start == null) {
            summary(summary, "—", "Aucune pause en cours");
            return;
        }
        summary(summary, journal.keptDays(now) + " sur " + journal.duration(), "Jours tenus");
        LocalDate first = start.isAfter(from) ? start : from, last = journal.end().isBefore(now) ? journal.end() : now;
        if (first.isAfter(last)) {
            add(card, text("Aucun jour de pause sur cette période.", 14, MUTED), 12);
            return;
        }
        LinearLayout grid = column();
        addWeekdays(grid);
        for (LocalDate week = first.minusDays(first.getDayOfWeek().getValue() - 1L); !week.isAfter(last); week = week.plusWeeks(1)) {
            LinearLayout days = row();
            for (int i = 0; i < 7; i++) {
                LocalDate date = week.plusDays(i);
                addCalendarDay(days, date, !date.isBefore(first) && !date.isAfter(last));
            }
            add(grid, days, 4);
        }
        add(card, grid, 10);
    }

    private void showHistory(LinearLayout page, LocalDate from, LocalDate now) {
        LinearLayout rows = column();
        for (LocalDate date = now; !date.isBefore(from); date = date.minusDays(1)) {
            LocalDate entry = date;
            List<Journal.Meal> meals = journal.meals(date);
            Double kg = journal.weightAt(date);
            List<String> details = new ArrayList<>();
            if (!journal.status(date).isEmpty()) details.add(capital(statusLabel(journal.status(date))));
            if (kg != null) details.add(format(kg, 1) + " kg");
            if (details.isEmpty() && meals.isEmpty()) continue;
            if (details.isEmpty()) details.add(meals.size() + " repas");
            View kcal = meals.isEmpty() ? null : text(format(meals.stream().mapToDouble(Journal.Meal::kcal).sum(), 0) + " kcal", 15, MUTED);
            addRow(rows, entry(null, capital(TITLE_DATE.format(date)), String.join(", ", details), kcal, () -> openDay(entry)));
        }
        if (rows.getChildCount() == 0) return;
        LinearLayout card = card(page, 12);
        add(card, heading("Journal", 18, INK), 0);
        add(card, rows, 2);
    }

    private void showSettings(LinearLayout page) {
        pageHeader(page, "Réglages");
        long last = desktop.getLong("last", 0);
        String wifi = desktopPairing() == null ? "Associer un PC avec un QR code" : last == 0 ? "PC associé" : "Dernier échange le " + moment(last);
        LinearLayout sync = group(page, "Synchronisation");
        addRow(sync, entry(JournalIcon.Kind.CLOUD, "Google Drive", drive.connected() ? drive.status() : "Non connecté", chevron(), () -> go(SETTINGS, DRIVE)));
        addRow(sync, entry(JournalIcon.Kind.LAPTOP, "Wi-Fi local", wifi, chevron(), () -> go(SETTINGS, WIFI)));
        LinearLayout goals = group(page, "Objectifs");
        List<String> targets = new ArrayList<>();
        if (journal.target(true) != null) targets.add(format(journal.target(true), 1) + " kg");
        if (journal.target(false) != null) targets.add(format(journal.target(false), 0) + " kcal par jour");
        addRow(goals, entry(JournalIcon.Kind.SCALE, "Poids et calories", targets.isEmpty() ? "Aucun objectif" : String.join(" · ", targets), chevron(), this::editTargets));
        addRow(goals, entry(JournalIcon.Kind.FLAG, "Ma pause", journal.start() == null ? "Aucune pause"
                : "Du " + HEADER_DATE.format(journal.start()) + " au " + HEADER_DATE.format(journal.end()), chevron(), this::editGoal));
        LinearLayout daily = group(page, "Au quotidien");
        JournalReminder.Settings reminder = JournalReminder.settings(this);
        addRow(daily, entry(JournalIcon.Kind.BELL, "Rappels", !reminder.enabled() ? "Désactivés"
                : !JournalReminder.canNotify(this) ? "Notifications à autoriser" : "Tous les jours à " + reminderTime(reminder.hour(), reminder.minute()),
                chevron(), this::editReminder));
        addRow(daily, entry(JournalIcon.Kind.SPARK, "Estimation des repas", chatgptAccountBusy ? "Connexion ChatGPT en cours…" : mealProviderLabel(),
                chevron(), () -> { if (!chatgptAccountBusy) chooseMealProvider(); }));
        LinearLayout data = group(page, "IA et données");
        addRow(data, entry(JournalIcon.Kind.EXPORT, "Exporter un bilan", "Fichier JSON à joindre à une conversation", chevron(), this::export));
        addRow(data, entry(JournalIcon.Kind.PLUG, "Partage USB", "Ouvert 10 minutes à la demande", chevron(), () -> go(SETTINGS, USB)));
    }

    private LinearLayout group(LinearLayout page, String title) {
        TextView label = heading(title, 14, MUTED);
        label.setPadding(dp(4), 0, 0, 0);
        add(page, label, 14);
        LinearLayout card = card(page, 6);
        card.setPadding(dp(16), dp(4), dp(16), dp(4));
        return card;
    }

    private static String moment(long time) {
        return DateTimeFormatter.ofPattern("d MMM 'à' HH:mm", FR).format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
    }

    private void editTracker(Tracker previous) {
        LinearLayout form = form();
        EditText name = field(form, "Nom", previous == null ? "" : previous.name(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, Tracker.MAX_NAME);
        RadioGroup kind = new RadioGroup(this);
        kind.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton habit = radio("Habitude"), pause = radio("Pause");
        kind.addView(habit);
        kind.addView(pause);
        kind.check(previous != null && previous.pause() ? pause.getId() : habit.getId());
        add(form, kind, 6);
        LocalDate[] start = {previous == null ? today() : previous.start()};
        add(form, text("Début", 14, MUTED), 8);
        TextView date = button(displayDate(start[0]), FIELD, ACCENT, () -> {});
        date.setOnClickListener(view -> pickDate(start[0], false, chosen -> {
            start[0] = chosen;
            date.setText(displayDate(chosen));
        }));
        add(form, date, 3);
        EditText duration = field(form, "Durée en jours (facultatif)", previous == null || previous.duration() == 0 ? ""
                : Integer.toString(previous.duration()), InputType.TYPE_CLASS_NUMBER, 3);
        EditText rests = field(form, "Jours de repos autorisés", previous == null || previous.restDays() == 0 ? ""
                : Integer.toString(previous.restDays()), InputType.TYPE_CLASS_NUMBER, 3);
        AlertDialog dialog = new PaperStyle.DialogBuilder(this).setTitle(previous == null ? "Nouveau suivi" : "Modifier le suivi")
                .setView(scrollingForm(form)).setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty()) { name.setError("Donne un nom au suivi."); return; }
            boolean noEnd = duration.getText().toString().trim().isEmpty();
            double length = noEnd ? 0 : number(duration);
            if (!noEnd && (!Double.isFinite(length) || length < 1 || length > Journal.MAX_PAUSE_DURATION || length != Math.floor(length))) {
                duration.setError("Indique entre 1 et " + Journal.MAX_PAUSE_DURATION + " jours, ou laisse vide.");
                return;
            }
            double allowance = rests.getText().toString().trim().isEmpty() ? 0 : number(rests);
            int maximum = noEnd ? Journal.MAX_PAUSE_DURATION : (int) length;
            if (!Double.isFinite(allowance) || allowance < 0 || allowance > maximum || allowance != Math.floor(allowance)) {
                rests.setError("Indique entre 0 et " + maximum + " jours de repos.");
                return;
            }
            try {
                String id = previous == null ? java.util.UUID.randomUUID().toString() : previous.id();
                journal.saveTracker(new Tracker(id, label, kind.getCheckedRadioButtonId() == pause.getId(), start[0], (int) length, (int) allowance), previous);
            } catch (org.json.JSONException error) { toast(error.getMessage()); return; }
            dialog.dismiss();
            render(true);
        });
    }

    private void editTrackerDay(Tracker tracker, LocalDate date) {
        if (!tracker.includes(date)) { toast("Choisis un jour dans la période de ce suivi."); return; }
        new PaperStyle.DialogBuilder(this).setTitle(tracker.name() + " · " + displayDate(date))
                .setItems(new String[]{tracker.pause() ? "Tenue" : "Fait", "Repos", tracker.pause() ? "Non tenue" : "Non fait", "Effacer la réponse"},
                        (dialog, choice) -> markTracker(tracker, date, switch (choice) {
                            case 0 -> Tracker.Day.DONE;
                            case 1 -> Tracker.Day.REST;
                            case 2 -> Tracker.Day.MISSED;
                            default -> null;
                        }))
                .setNegativeButton("Fermer", null).show();
    }

    private void markTracker(Tracker tracker, LocalDate date, Tracker.Day status) {
        if (!journal.setTrackerDay(tracker, date, status)) toast("Ce suivi a changé. Réessaie après l’actualisation.");
        render(true);
    }

    private void mark(LocalDate date, String status) {
        if (journal.inPause(date) && !date.isAfter(today())) {
            pauseEditing = false;
            journal.setStatus(date, status);
            render(true);
        }
    }

    private void editGoal() {
        LinearLayout form = form();
        LocalDate[] start = {journal.start() == null ? today() : journal.start()};
        TextView date = button(displayDate(start[0]), FIELD, ACCENT, () -> {});
        date.setOnClickListener(view -> pickDate(start[0], false, chosen -> {
            start[0] = chosen;
            date.setText(displayDate(chosen));
        }));
        add(form, text("Date de début", 14, MUTED), 0);
        add(form, date, 4);
        EditText duration = field(form, "Durée en jours", Integer.toString(journal.duration()), InputType.TYPE_CLASS_NUMBER, 3);
        AlertDialog dialog = new PaperStyle.DialogBuilder(this).setTitle("Objectif de pause").setView(form)
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
        AlertDialog dialog = new PaperStyle.DialogBuilder(this).setTitle("Prolonger la pause").setView(form)
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
        month = null;
        render(true);
    }

    private static String reminderTime(int hour, int minute) {
        return String.format(FR, "%02d:%02d", hour, minute);
    }

    private void editReminder() {
        JournalReminder.Settings settings = JournalReminder.settings(this);
        LinearLayout form = form();
        CheckBox enabled = checkBox("Activer le rappel quotidien", settings.enabled());
        add(form, enabled, 0);
        CheckBox weight = checkBox("Me rappeler aussi la pesée", settings.weight());
        add(form, weight, 0);
        int[] time = {settings.hour(), settings.minute()};
        TextView hour = button(reminderTime(time[0], time[1]), FIELD, ACCENT, () -> {});
        hour.setContentDescription("Heure du rappel");
        hour.setOnClickListener(v -> new TimePickerDialog(this, (picker, hours, minutes) -> {
            time[0] = hours;
            time[1] = minutes;
            hour.setText(reminderTime(hours, minutes));
        }, time[0], time[1], true).show());
        add(form, hour, 7);
        add(form, text("Rappel si ta journée reste à renseigner. Android peut décaler la notification.", 13, MUTED), 8);
        if (settings.enabled() && !JournalReminder.canNotify(this))
            add(form, text("Autorise les notifications de Fil des jours dans les paramètres Android.", 13, PEACH_INK), 5);
        new PaperStyle.DialogBuilder(this).setTitle("Rappel du journal").setView(form)
                .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", (d, w) -> {
                    JournalReminder.configure(this, enabled.isChecked(), time[0], time[1], weight.isChecked());
                    render(true);
                    if (enabled.isChecked() && Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
                    else if (enabled.isChecked() && !JournalReminder.canNotify(this))
                        toast("Autorise les notifications de Fil des jours dans les paramètres Android.");
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

    private void editTargets() {
        LinearLayout form = form();
        EditText weight = field(form, "Poids cible (kg)", decimalInput(journal.target(true)), decimalType(), 12);
        EditText calories = field(form, "Calories quotidiennes (kcal)", decimalInput(journal.target(false)), decimalType(), 12);
        add(form, text("Laisse un champ vide pour retirer son objectif.", 13, MUTED), 4);
        AlertDialog dialog = new PaperStyle.DialogBuilder(this).setTitle("Poids et calories").setView(form)
                .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!validTarget(weight, 1000) || !validTarget(calories, 100000)) return;
            journal.setTarget(true, optional(weight));
            journal.setTarget(false, optional(calories));
            dialog.dismiss();
            render(true);
        });
    }

    private static boolean validTarget(EditText input, double maximum) {
        Double value = optional(input);
        boolean valid = value == null || Double.isFinite(value) && value > 0 && value <= maximum;
        if (!valid) input.setError("Indique une valeur valide.");
        return valid;
    }

    private static Double optional(EditText input) {
        return input.getText().toString().trim().isEmpty() ? null : Double.valueOf(number(input));
    }

    private void chatgptAccount() {
        if (chatgptAccountBusy) return;
        if (chatgpt.connected()) {
            new PaperStyle.DialogBuilder(this).setTitle("Compte ChatGPT")
                    .setMessage(chatgpt.account() + "\nFil des jours utilise ton abonnement pour estimer les aliments absents de Ciqual.")
                    .setNeutralButton("Utilisation", (dialog, which) -> openBrowser(Uri.parse("https://chatgpt.com/settings/usage")))
                    .setNegativeButton("Déconnecter", (dialog, which) -> updateChatGptAccount(false))
                    .setPositiveButton("Fermer", null).show();
            return;
        }
        new PaperStyle.DialogBuilder(this).setTitle("Continuer avec ChatGPT")
                .setMessage("Avec un abonnement éligible, Fil des jours peut estimer les aliments inconnus directement sur ce téléphone. Seuls leurs noms sont envoyés à ChatGPT pour l’estimation.")
                .setNegativeButton("Annuler", null).setPositiveButton("Continuer", (dialog, which) -> updateChatGptAccount(true)).show();
    }

    private String mealProvider() {
        return getPreferences(MODE_PRIVATE).getString("mealProvider", chatgpt.connected() ? "chatgpt" : "ciqual");
    }

    private String mealProviderLabel() {
        return switch (mealProvider()) {
            case "chatgpt" -> chatgpt.connected() ? "Ciqual, puis ChatGPT" : "ChatGPT à connecter";
            case "claude" -> "Claude sur le PC";
            default -> "Ciqual sur le téléphone";
        };
    }

    private void chooseMealProvider() {
        String[] providers = {"ciqual", "chatgpt", "claude"};
        String[] labels = {"Ciqual sur le téléphone", chatgpt.connected() ? "ChatGPT · compte connecté" : "ChatGPT · à connecter", "Claude via MCP sur le PC"};
        int selected = java.util.Arrays.asList(providers).indexOf(mealProvider());
        new PaperStyle.DialogBuilder(this).setTitle("Source des estimations").setNegativeButton("Fermer", null)
                .setSingleChoiceItems(labels, selected, (dialog, choice) -> {
                    getPreferences(MODE_PRIVATE).edit().putString("mealProvider", providers[choice]).apply();
                    dialog.dismiss();
                    render(true);
                    if (choice == 1) chatgptAccount();
                    if (choice == 2) new PaperStyle.DialogBuilder(this).setTitle("Claude via MCP")
                            .setMessage("Garde le repas pour Claude avec le bouton +, puis synchronise avec le PC. Tu pourras demander à Claude une proposition à relire dans Fil des jours.")
                            .setPositiveButton("Compris", null).show();
                }).show();
    }

    private void updateChatGptAccount(boolean connect) {
        chatgptAccountBusy = true;
        render(true);
        chatgptWorker.execute(() -> {
            String message;
            try {
                if (connect) {
                    chatgpt.signIn(uri -> runOnUiThread(() -> openBrowser(uri)));
                    message = "ChatGPT connecté.";
                } else message = chatgpt.signOut() ? "ChatGPT déconnecté."
                        : "Déconnexion locale effectuée. Vérifie l’accès de Fil des jours dans les réglages ChatGPT.";
            } catch (Exception error) {
                message = error instanceof IOException && error.getMessage() != null ? error.getMessage() : "Connexion ChatGPT indisponible.";
            }
            String result = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                chatgptAccountBusy = false;
                render(true);
                toast(result);
            });
        });
    }

    private void openBrowser(Uri url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, url)); }
        catch (ActivityNotFoundException error) { toast("Aucun navigateur disponible."); }
    }

    private void showDrive(LinearLayout page) {
        subpageHeader(page, "Google Drive");
        LinearLayout card = card(page, 8);
        add(card, text(driveBusy ? "Connexion ou synchronisation en cours…" : drive.status(), 15, INK), 0);
        if (!drive.connected()) {
            add(card, text("Relie tes appareils au même compte Google pour retrouver ton journal automatiquement quand tu ouvres l’app.", 14, MUTED), 6);
            TextView connect = button("Connecter Google Drive", BRAND, Color.WHITE, () -> new PaperStyle.DialogBuilder(this)
                    .setTitle("Connecter Google Drive")
                    .setMessage("Tes saisies seront synchronisées dans l’espace privé de Fil des jours sur ton compte Google. Tes données restent aussi sur cet appareil.")
                    .setNegativeButton("Annuler", null).setPositiveButton("Continuer", (dialog, which) -> startDrive(true, null)).show());
            connect.setEnabled(!driveBusy);
            add(card, connect, 14);
            return;
        }
        LinearLayout conflicts = column();
        for (DriveJournal.Conflict conflict : drive.conflicts())
            addRow(conflicts, entry(null, driveEntryLabel(conflict.key()), "Deux versions à départager", chevron(), () -> reviewDriveConflict(conflict)));
        if (conflicts.getChildCount() > 0) add(card, conflicts, 6);
        add(card, button(driveNeedsConsent ? "Reconnecter Google Drive" : "Synchroniser maintenant", BRAND, Color.WHITE,
                () -> startDrive(driveNeedsConsent, null)), 14);
        LinearLayout actions = card(page, 12);
        actions.setPadding(dp(16), dp(4), dp(16), dp(4));
        addRow(actions, entry(null, "Remplacer le contenu de Drive", "Repartir du journal de ce téléphone", null, () -> new PaperStyle.DialogBuilder(this)
                .setTitle("Remplacer le contenu de Drive")
                .setMessage("Drive repartira du journal de ce téléphone. Tes autres appareils garderont le leur : les différences y seront à vérifier.")
                .setNegativeButton("Annuler", null).setPositiveButton("Remplacer", (dialog, which) -> startDrive(false, true, null)).show()));
        addRow(actions, entry(null, "Déconnecter Google Drive", "Ton journal reste sur ce téléphone", null, () -> {
            if (driveBusy) return;
            drive.disconnect();
            render(true);
        }));
    }

    private void showWifi(LinearLayout page) {
        subpageHeader(page, "Wi-Fi local");
        WindowsSync.Pairing pairing = desktopPairing();
        LinearLayout card = card(page, 8);
        if (pairing == null) {
            add(card, heading("Associer ton PC", 18, INK), 0);
            add(card, text("Connecte les deux appareils au même réseau. Sur le PC, ouvre Fil des jours, puis Réglages et Wi-Fi local.", 14, MUTED), 4);
            add(card, button("Scanner le QR code", BRAND, Color.WHITE, this::pickPairingPhoto), 14);
            add(card, button("Coller le code", FIELD, ACCENT, this::enterPairing), 8);
            return;
        }
        add(card, heading("PC associé", 18, INK), 0);
        add(card, text(pairing.address(), 14, MUTED), 2);
        long last = desktop.getLong("last", 0);
        if (last > 0) add(card, text("Dernier échange le " + moment(last), 14, MUTED), 2);
        add(card, text("Garde Fil des jours ouvert sur le PC pendant l’échange.", 14, MUTED), 2);
        add(card, button("Synchroniser en Wi-Fi", BRAND, Color.WHITE, () -> syncPc(pairing)), 14);
        LinearLayout actions = card(page, 12);
        actions.setPadding(dp(16), dp(4), dp(16), dp(4));
        addRow(actions, entry(null, "Modifier l’adresse du PC", null, chevron(), () -> changePcAddress(pairing)));
        addRow(actions, entry(null, "Scanner un nouveau QR code", null, chevron(), this::pickPairingPhoto));
        addRow(actions, entry(null, "Coller un nouveau code", null, chevron(), this::enterPairing));
        addRow(actions, entry(null, "Dissocier ce PC", null, chevron(), this::forgetPc));
    }

    private void startDrive(boolean interactive, DriveJournal.Resolution resolution) { startDrive(interactive, false, resolution); }

    private void startDrive(boolean interactive, boolean replace, DriveJournal.Resolution resolution) {
        if (driveBusy || (!interactive && !drive.connected())) return;
        driveBusy = true;
        if (section == DRIVE) render(true);
        AuthorizationRequest request = AuthorizationRequest.builder().setRequestedScopes(List.of(new Scope(DriveFiles.SCOPE))).build();
        driveAuthorization.authorize(request).addOnSuccessListener(result -> {
            if (isFinishing() || isDestroyed()) return;
            if (result.hasResolution()) {
                driveNeedsConsent = true;
                if (!interactive || !visible || result.getPendingIntent() == null) {
                    finishDrive("Google demande une nouvelle autorisation. Reconnecte ton compte depuis Réglages.");
                    return;
                }
                try { startIntentSenderForResult(result.getPendingIntent().getIntentSender(), DRIVE_PERMISSION, null, 0, 0, 0); }
                catch (IntentSender.SendIntentException error) { finishDrive("Impossible d’ouvrir l’autorisation Google."); }
            } else runDrive(result, interactive, replace, resolution);
        }).addOnFailureListener(error -> finishDrive("Connexion Google indisponible sur ce téléphone. Essaie à nouveau."));
    }

    private void runDrive(AuthorizationResult result, boolean interactive, boolean replace, DriveJournal.Resolution resolution) {
        String token = result.getAccessToken();
        if (token == null || token.isBlank()) { finishDrive("Google n’a pas donné accès au dossier de Fil des jours."); return; }
        driveNeedsConsent = false;
        driveWorker.execute(() -> {
            String errorMessage = null;
            try { drive.sync(token, interactive, replace, resolution); }
            catch (Exception error) {
                errorMessage = error instanceof IOException && error.getMessage() != null ? error.getMessage()
                        : "La synchronisation Drive a été interrompue. Ton journal reste sur le téléphone.";
            }
            String status = errorMessage;
            runOnUiThread(() -> finishDrive(status));
        });
    }

    private void finishDrive(String error) {
        driveBusy = false;
        if (error != null) drive.setStatus(error);
        if (!isFinishing() && !isDestroyed() && visible) refresh();
    }

    private static String driveEntryLabel(String key) {
        String[] parts = key.split("/");
        return switch (parts[0]) {
            case "goal" -> "Objectif de pause";
            case "pause" -> "Pause du " + parts[1];
            case "weight" -> "Pesée du " + parts[1];
            case "meal" -> "Aliment du " + parts[1];
            case "track" -> "Suivi";
            case "target" -> "Objectif";
            case "report" -> "Bilan";
            default -> "Repas à estimer";
        };
    }

    private void reviewDriveConflict(DriveJournal.Conflict conflict) {
        List<Object> options = new ArrayList<>();
        options.add(conflict.local());
        options.add(conflict.cloud());
        options.addAll(conflict.alternatives());
        String[] labels = new String[options.size()];
        for (int i = 0; i < labels.length; i++) {
            String summary = driveEntryValue(conflict.key(), options.get(i));
            if (summary.length() > 100) summary = summary.substring(0, 97) + "…";
            labels[i] = (i == 0 ? "Ce téléphone" : i == 1 ? "Drive" : "Autre version") + " · " + summary;
        }
        new PaperStyle.DialogBuilder(this).setTitle(driveEntryLabel(conflict.key())).setNegativeButton("Annuler", null)
                .setItems(labels, (dialog, index) -> startDrive(false, new DriveJournal.Resolution(conflict, options.get(index)))).show();
    }

    private static String driveEntryValue(String key, Object value) {
        if (value == null || value == JSONObject.NULL) return "entrée vide";
        if (value instanceof Boolean day) return day ? "Fait" : "Non fait";
        if (value instanceof Number number) return format(number.doubleValue(), 2) + (key.endsWith("calories") ? " kcal" : " kg");
        if (value instanceof JSONObject row) {
            if (key.equals("goal")) return row.optInt("duration") + " jours dès le " + row.optString("start");
            if (key.startsWith("meal/")) return row.optString("name") + " · " + format(row.optDouble("kcal", 0), 1) + " kcal";
            if (key.startsWith("track/")) return row.optString("name") + " · " + row.optInt("duration") + " j · " + row.optInt("restDays") + " repos";
            if (key.startsWith("report/")) return row.optString("title") + " · " + row.optString("text");
            return row.optString("description");
        }
        return switch (value.toString()) { case "tenu" -> "Tenu"; case "consommation" -> "Non tenu"; case "rest" -> "Repos"; default -> value.toString(); };
    }

    private WindowsSync.Pairing desktopPairing() {
        try {
            return WindowsSync.Pairing.parse(desktop.getString("code", ""));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private void pickPairingPhoto() {
        new PaperStyle.DialogBuilder(this).setTitle("QR code affiché sur le PC")
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
        AlertDialog dialog = new PaperStyle.DialogBuilder(this).setTitle("Associer ton PC").setView(form)
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
                else new PaperStyle.DialogBuilder(this).setMessage("QR code illisible. Reprends une photo ou colle le code du PC.")
                        .setNegativeButton("Fermer", null).setPositiveButton("Coller le code", (d, w) -> enterPairing()).show();
            });
        });
    }

    private void savePairing(WindowsSync.Pairing pairing) {
        WindowsSync.Pairing previous = desktopPairing();
        SharedPreferences.Editor change = desktop.edit().putString("code", pairing.code());
        if (previous != null && !previous.pin().equals(pairing.pin())) change.remove("last");
        change.apply();
        go(SETTINGS, WIFI);
        syncPc(pairing);
    }

    private void changePcAddress(WindowsSync.Pairing pairing) {
        LinearLayout form = form();
        EditText address = field(form, "Adresse IPv4 affichée sur le PC", pairing.address(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 15);
        AlertDialog dialog = new PaperStyle.DialogBuilder(this).setTitle("Adresse du PC").setView(form)
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
        new PaperStyle.DialogBuilder(this).setMessage("Dissocier ce PC ? Tes entrées restent sur le téléphone.")
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
        AlertDialog progress = new PaperStyle.DialogBuilder(this).setTitle("Synchronisation")
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
                    JSONObject estimate = result.optJSONObject("estimationPc");
                    desktop.edit().putLong("last", System.currentTimeMillis()).apply();
                    render(true);
                    if (!result.optBoolean("extended")) {
                        toast("Journal synchronisé. Mets Pause Windows à jour pour les bilans et les objectifs.");
                    } else if (!result.optBoolean("drafts") && WindowsSync.keys(state.current()).stream().anyMatch(WindowsSync::draftKey)) {
                        toast("Journal synchronisé. Mets Pause Windows à jour pour retrouver les repas à estimer sur le PC.");
                    } else if (!result.optBoolean("trackers") && WindowsSync.keys(state.current()).stream().anyMatch(WindowsSync::trackerKey)) {
                        toast("Journal synchronisé. Mets Pause Windows à jour pour retrouver les suivis sur le PC.");
                    } else if (estimate != null
                            && WindowsSync.keys(state.current()).stream().anyMatch(WindowsSync::draftKey)) {
                        int ready = estimate.optInt("propositions");
                        toast(ready > 0 ? "Journal synchronisé. " + ready + " proposition(s) à vérifier sur le PC."
                                : "Journal synchronisé. Le PC cherche dans les références gratuites.");
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
                case "track" -> parts.length == 3 ? "Jour de suivi · " + parts[2] : "Paramètres d’un suivi";
                default -> "Aliment du " + parts[1];
            };
            details.append("\n").append(title)
                    .append("\nPC : ").append(conflictValue(key, conflict.opt("pc")))
                    .append("\nTéléphone : ").append(conflictValue(key, conflict.opt("phone"))).append("\n");
        }
        if (conflicts.length() > 7) details.append("\nEt ").append(conflicts.length() - 7).append(" autre(s) différence(s).");
        new PaperStyle.DialogBuilder(this).setTitle("Versions différentes").setMessage(details)
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
        if (WindowsSync.trackerKey(key)) return value instanceof JSONObject tracker ? tracker.optString("name")
                : "rest".equals(value) ? "Repos" : Boolean.TRUE.equals(value) ? "Coché" : "Non coché";
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
        new PaperStyle.DialogBuilder(this).setTitle("Synchro interrompue").setMessage(message)
                .setPositiveButton("Fermer", null).show();
    }

    private void showUsb(LinearLayout page) {
        subpageHeader(page, "Partage USB");
        LinearLayout live = card(page, 8);
        add(live, text("Branche le téléphone à ton ordinateur, puis ouvre l’accès le temps de demander ton bilan à ton IA. "
                + "Il contient les jours de pause, les pesées et les calories par jour.", 14, MUTED), 0);
        if (bridge.active()) {
            add(live, text("Accès ouvert pour 10 minutes maximum. Garde cet écran ouvert.", 15, INK), 10);
            add(live, button("Fermer l’accès", FIELD, ACCENT, () -> stopShare(true)), 12);
        } else {
            add(live, button("Ouvrir l’accès pour 10 min", BRAND, Color.WHITE, this::startShare), 14);
        }
        LinearLayout first = card(page, 12);
        first.setPadding(dp(16), dp(4), dp(16), dp(4));
        addRow(first, entry(null, "Afficher le code du téléphone", "Pour associer le connecteur sur ton ordinateur", chevron(), this::showPairing));
    }

    private void showReports(List<Journal.SavedReport> reports) {
        String[] titles = new String[reports.size()];
        for (int i = 0; i < reports.size(); i++) {
            Journal.SavedReport report = reports.get(i);
            titles[i] = report.title() + "\n" + HISTORY_DATE.format(report.start()) + " – " + HISTORY_DATE.format(report.end());
        }
        new PaperStyle.DialogBuilder(this).setTitle("Bilans enregistrés").setItems(titles, (dialog, which) -> {
            Journal.SavedReport report = reports.get(which);
            LinearLayout form = form();
            add(form, text(HISTORY_DATE.format(report.start()) + " – " + HISTORY_DATE.format(report.end()), 13, MUTED), 0);
            TextView body = text(report.text(), 15, INK);
            body.setTextIsSelectable(true);
            add(form, body, 8);
            new PaperStyle.DialogBuilder(this).setTitle(report.title()).setView(scrollingForm(form))
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
        if (update && section == USB && visible) render(true);
    }

    private void showPairing() {
        String token = bridge.token();
        LinearLayout form = form();
        add(form, text("Entre ce code dans la commande d’association sur ton ordinateur.", 14, MUTED), 0);
        TextView code = text(token, 19, INK);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        add(form, code, 10);
        new PaperStyle.DialogBuilder(this).setTitle("Code du téléphone").setView(form)
                .setNegativeButton("Fermer", null)
                .setNeutralButton("Réinitialiser", (dialog, which) -> {
                    bridge.refreshToken();
                    stopShare(true);
                    showPairing();
                })
                .setPositiveButton("Copier", (dialog, which) -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    ClipData content = ClipData.newPlainText("Code du connecteur Fil des jours", token);
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
        new PaperStyle.DialogBuilder(this).setTitle(barcode ? "Code-barres du produit" : "Tableau nutritionnel")
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
        if (request == DRIVE_PERMISSION) {
            if (result != RESULT_OK || data == null) finishDrive("Connexion Google annulée.");
            else try { runDrive(driveAuthorization.getAuthorizationResultFromIntent(data), true, false, null); }
            catch (Exception error) { finishDrive("Google n’a pas donné accès à Fil des jours."); }
        } else if (request == CAMERA) {
            String path = photoPath;
            photoPath = null;
            if (path == null) return;
            if (result == RESULT_OK) {
                Uri uri = Uri.fromFile(new File(path));
                if (photoPairing) analyzePairing(uri, path);
                else analyze(uri, path, photoBarcode, photoDate == null ? day : photoDate);
            } else {
                removePhoto(path);
            }
        } else if (request == GALLERY && result == RESULT_OK && data != null && data.getData() != null) {
            if (photoPairing) analyzePairing(data.getData(), null);
            else analyze(data.getData(), null, photoBarcode, photoDate == null ? day : photoDate);
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
        AlertDialog dialog = new PaperStyle.DialogBuilder(this).setTitle("Code-barres").setView(form)
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
        if (tab == TODAY) render(true);
    }

    private void completeRecognition(LocalDate day, FoodRecognition.Product product, boolean retryCode, String message) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            setProcessing(false);
            if (product != null) {
                editMeal(day, null, product);
                return;
            }
            AlertDialog.Builder dialog = new PaperStyle.DialogBuilder(this).setMessage(message)
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
        EditText kcal = field(totalForm, previous == null ? "Calories de la portion (kcal), vide pour une estimation" : "Calories de la portion (kcal)",
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
        AlertDialog.Builder builder = new PaperStyle.DialogBuilder(this).setTitle(previous == null ? "Nouvel aliment" : "Modifier l’aliment")
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
            if (previous == null && (usePortion ? per100 : kcal).getText().toString().trim().isEmpty()
                    && meal(day).add(title, false)) {
                dialog.dismiss();
                openDay(day);
                return;
            }
            if (usePortion && previous == null && amount.getText().toString().trim().isEmpty()) {
                List<FoodDescription.Portion> parts = MealDescriptionDialog.parse(this, title);
                amount.setText(decimalInput(parts.isEmpty() || parts.get(0).grams() == null ? Double.valueOf(100) : parts.get(0).grams()));
            }
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
            dialog.dismiss();
            openDay(day);
        });
    }

    private CheckBox checkBox(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(INK);
        box.setTypeface(PaperStyle.body(this));
        box.setChecked(checked);
        return box;
    }

    private RadioButton radio(String label) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTextColor(INK);
        button.setTypeface(PaperStyle.body(this));
        return button;
    }

    private int decimalType() {
        return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
    }

    private void confirmDeletion(String message, Runnable delete) {
        new PaperStyle.DialogBuilder(this).setMessage(message).setNegativeButton("Annuler", null)
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
        return capital(FULL_DATE.format(date));
    }

    private static String capital(String label) {
        return label.substring(0, 1).toUpperCase(FR) + label.substring(1);
    }

    private static String days(int count) {
        return count + (count > 1 ? " jours" : " jour");
    }

    private static String statusLabel(String status) {
        return status.equals("tenu") ? "tenu" : "non tenu";
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
        input.setSingleLine(true);
        input.setInputType(type);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        if ((type & InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0 && (type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER) {
            input.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789,."));
            input.setRawInputType(type);
        }
        PaperStyle.field(input);
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

    private LinearLayout card(LinearLayout parent, int margin) {
        LinearLayout card = column();
        card.setBackground(shape(SURFACE, 20, SOFT_LINE));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        add(parent, card, margin);
        return card;
    }

    private LinearLayout goldCard(LinearLayout parent, int margin) {
        LinearLayout card = card(parent, margin);
        GradientDrawable seam = shape(Color.TRANSPARENT, 15, 0);
        seam.setStroke(dp(1), 0x59745726, dp(4), dp(4));
        LayerDrawable sheets = new LayerDrawable(new Drawable[]{shape(GOLD, 20, GOLD_EDGE), seam});
        sheets.setLayerInset(1, dp(6), dp(6), dp(6), dp(6));
        card.setBackground(sheets);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        return card;
    }

    private View progress(float ratio, int color) {
        LinearLayout track = row();
        track.setBackground(shape(color, 4, 0));
        track.setClipToOutline(true);
        View fill = new View(this);
        fill.setBackground(shape(BRAND, 4, 0));
        track.addView(fill, new LinearLayout.LayoutParams(0, dp(8), ratio));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, dp(8), 1 - ratio));
        return track;
    }

    private void addRow(LinearLayout rows, View row) {
        if (rows.getChildCount() > 0) {
            View separator = new View(this);
            separator.setBackgroundColor(SOFT_LINE);
            rows.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private View entry(JournalIcon.Kind icon, String title, String detail, View end, Runnable action) {
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(52));
        line.setPadding(0, dp(6), 0, dp(6));
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(0x189A3412), null, shape(Color.WHITE, 12, 0)));
        if (icon != null) {
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(24), dp(24));
            size.setMarginEnd(dp(13));
            line.addView(new JournalIcon(this, icon, ACCENT), size);
        }
        LinearLayout labels = column();
        labels.addView(detail == null ? text(title, 16, INK) : heading(title, 16, INK));
        if (detail != null) labels.addView(text(detail, 14, MUTED));
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (end != null) {
            LinearLayout.LayoutParams size = end instanceof JournalIcon
                    ? new LinearLayout.LayoutParams(dp(22), dp(22)) : new LinearLayout.LayoutParams(-2, -2);
            size.setMarginStart(dp(10));
            line.addView(end, size);
        }
        line.setOnClickListener(view -> action.run());
        return line;
    }

    private View chevron() {
        return new JournalIcon(this, JournalIcon.Kind.RIGHT, MUTED);
    }

    private TextView chip(String label) {
        TextView chip = heading(label, 13, VIOLET_INK);
        chip.setBackground(shape(VIOLET, 12, 0));
        chip.setPadding(dp(10), dp(3), dp(10), dp(4));
        return chip;
    }

    private View iconButton(JournalIcon.Kind kind, String label, Runnable action) {
        FrameLayout button = new FrameLayout(this);
        button.setContentDescription(label);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x229A3412), null, shape(Color.WHITE, 14, 0)));
        button.addView(new JournalIcon(this, kind, ACCENT), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private View segments(String[] labels, int selected, int height, IntConsumer choose) {
        LinearLayout track = row();
        track.setBackground(shape(SIDE, 15, 0));
        track.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            TextView part = heading(labels[i], 15, i == selected ? ACCENT : MUTED);
            part.setGravity(Gravity.CENTER);
            part.setSingleLine(true);
            part.setSelected(i == selected);
            part.setBackground(new RippleDrawable(ColorStateList.valueOf(0x189A3412),
                    i == selected ? shape(SURFACE, 12, 0) : null, shape(Color.WHITE, 12, 0)));
            part.setOnClickListener(view -> choose.accept(index));
            track.addView(part, new LinearLayout.LayoutParams(0, dp(height), 1));
        }
        return track;
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
        view.setTypeface(PaperStyle.title(this));
        return view;
    }

    private TextView text(String label, int size, int color) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(PaperStyle.body(this));
        view.setIncludeFontPadding(true);
        return view;
    }

    private TextView button(String label, int fill, int color, Runnable action) {
        android.widget.Button button = new android.widget.Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(color);
        button.setAllCaps(false);
        button.setTypeface(PaperStyle.title(this));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinimumWidth(0);
        button.setPadding(dp(9), dp(7), dp(9), dp(7));
        button.setStateListAnimator(null);
        GradientDrawable front = shape(fill, 14, fill == FIELD ? LINE : 0);
        Drawable felt = front;
        if (fill == BRAND) {
            LayerDrawable raised = new LayerDrawable(new Drawable[]{shape(0xFF8F3216, 14, 0), front});
            raised.setLayerInset(1, 0, 0, 0, dp(3));
            felt = raised;
        }
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x229A3412), felt, null));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private TextView link(String label, Runnable action) {
        TextView link = button(label, Color.TRANSPARENT, ACCENT, action);
        link.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        link.setMinWidth(dp(48));
        link.setPadding(dp(12), dp(7), 0, dp(7));
        return link;
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
