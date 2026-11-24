package fr.pause.journal;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

final class Journal {
    static final int MAX_PAUSE_DURATION = 366;
    static final String NOT_KEPT_STATUS = "consommation";

    record Meal(String id, String name, double kcal, Double amount, Double kcalPer100, String source) {}
    record Weight(LocalDate date, double kg) {}
    record MealDraft(String id, LocalDate date, String description) {}
    record SavedReport(String title, String text, LocalDate start, LocalDate end, OffsetDateTime created) {}
    record SyncState(JSONObject base, JSONObject current, String journalId) {}

    private final SharedPreferences preferences;
    private final JSONObject data;

    Journal(Context context) {
        preferences = context.getSharedPreferences("journal", Context.MODE_PRIVATE);
        try {
            data = new JSONObject(preferences.getString("data", "{}"));
        } catch (JSONException error) {
            throw new IllegalStateException("Le journal ne peut pas être ouvert.", error);
        }
        for (String section : new String[]{"pauseDays", "weights", "meals", "targets", "reports", "drafts"}) {
            if (data.optJSONObject(section) == null) put(data, section, new JSONObject());
        }
    }

    private static void put(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (JSONException error) {
            throw new IllegalArgumentException(key, error);
        }
    }

    private JSONObject section(String key) {
        return data.optJSONObject(key);
    }

    private void save() {
        preferences.edit().putString("data", data.toString()).apply();
    }

    synchronized LocalDate start() {
        String start = data.optString("start");
        return start.isEmpty() ? null : LocalDate.parse(start);
    }

    synchronized int duration() {
        return data.optInt("duration", 30);
    }

    synchronized LocalDate end() {
        return start() == null ? null : start().plusDays(duration() - 1L);
    }

    synchronized void setPause(LocalDate start, int duration) {
        put(data, "start", start.toString());
        put(data, "duration", duration);
        save();
    }

    synchronized boolean inPause(LocalDate date) {
        return start() != null && !date.isBefore(start()) && !date.isAfter(end());
    }

    synchronized String status(LocalDate date) {
        return section("pauseDays").optString(date.toString());
    }

    synchronized void setStatus(LocalDate date, String status) {
        if (status.isEmpty()) section("pauseDays").remove(date.toString());
        else put(section("pauseDays"), date.toString(), status);
        save();
    }

    synchronized int keptDays(LocalDate today) {
        if (start() == null) return 0;
        int count = 0;
        for (LocalDate day = start(); !day.isAfter(end()) && !day.isAfter(today); day = day.plusDays(1)) {
            if (status(day).equals("tenu")) count++;
        }
        return count;
    }

    synchronized int streak(LocalDate today) {
        if (start() == null || today.isBefore(start())) return 0;
        LocalDate day = today.isAfter(end()) ? end() : today;
        if (day.equals(today) && status(day).isEmpty()) day = day.minusDays(1);
        int count = 0;
        while (!day.isBefore(start()) && status(day).equals("tenu")) {
            count++;
            day = day.minusDays(1);
        }
        return count;
    }

    synchronized int elapsed(LocalDate today) {
        if (start() == null || today.isBefore(start())) return 0;
        return (int) Math.min(duration(), ChronoUnit.DAYS.between(start(), today) + 1);
    }

    synchronized Double weightAt(LocalDate date) {
        JSONObject weights = section("weights");
        return weights.has(date.toString()) ? weights.optDouble(date.toString()) : null;
    }

    synchronized Double target(boolean weight) {
        JSONObject targets = section("targets");
        String key = weight ? "weight" : "calories";
        return targets.has(key) ? targets.optDouble(key) : null;
    }

    synchronized void setTarget(boolean weight, Double value) {
        put(section("targets"), weight ? "weight" : "calories", value);
        save();
    }

    synchronized boolean needsReminder(LocalDate date) {
        if (inPause(date)) return status(date).isEmpty();
        return status(date).isEmpty() && weightAt(date) == null && meals(date).isEmpty();
    }

    synchronized List<SavedReport> reports() {
        JSONObject reports = section("reports");
        List<SavedReport> saved = new ArrayList<>();
        for (Iterator<String> it = reports.keys(); it.hasNext();) {
            JSONObject report = reports.optJSONObject(it.next());
            saved.add(new SavedReport(report.optString("title"), report.optString("text"),
                    LocalDate.parse(report.optString("start")), LocalDate.parse(report.optString("end")),
                    OffsetDateTime.parse(report.optString("created"))));
        }
        saved.sort((left, right) -> right.created().compareTo(left.created()));
        return saved;
    }

    synchronized void saveWeight(LocalDate date, Double kg) {
        if (kg == null) section("weights").remove(date.toString());
        else put(section("weights"), date.toString(), kg);
        save();
    }

    synchronized List<Weight> weights() {
        List<Weight> weights = new ArrayList<>();
        JSONObject values = section("weights");
        for (Iterator<String> it = values.keys(); it.hasNext();) {
            String date = it.next();
            weights.add(new Weight(LocalDate.parse(date), values.optDouble(date)));
        }
        weights.sort((a, b) -> a.date().compareTo(b.date()));
        return weights;
    }

    synchronized List<Meal> meals(LocalDate date) {
        JSONArray values = section("meals").optJSONArray(date.toString());
        if (values == null) return Collections.emptyList();
        List<Meal> meals = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject meal = values.optJSONObject(i);
            meals.add(new Meal(meal.optString("id"), meal.optString("name"), meal.optDouble("kcal"),
                    meal.has("amount") ? meal.optDouble("amount") : null,
                    meal.has("kcalPer100") ? meal.optDouble("kcalPer100") : null,
                    meal.optString("source", "Manuel")));
        }
        return meals;
    }

    synchronized void saveMeal(LocalDate date, Meal previous, String name, double kcal,
                               Double amount, Double kcalPer100, String source) {
        List<Meal> entries = new ArrayList<>(meals(date));
        Meal next = new Meal(previous == null ? UUID.randomUUID().toString() : previous.id(),
                name, kcal, amount, kcalPer100, source);
        int index = previous == null ? -1 : entries.indexOf(previous);
        if (index >= 0) entries.set(index, next);
        else entries.add(next);
        writeMeals(date, entries);
    }

    synchronized void deleteMeal(LocalDate date, Meal meal) {
        List<Meal> entries = new ArrayList<>(meals(date));
        entries.remove(meal);
        writeMeals(date, entries);
    }

    synchronized void addMeals(LocalDate date, List<Meal> additions) {
        List<Meal> entries = new ArrayList<>(meals(date));
        entries.addAll(additions);
        writeMeals(date, entries);
    }

    synchronized void addDraft(LocalDate day, String description) {
        JSONObject draft = new JSONObject();
        put(draft, "date", day.toString());
        put(draft, "description", description);
        put(section("drafts"), UUID.randomUUID().toString(), draft);
        save();
    }

    synchronized List<MealDraft> drafts(LocalDate day) {
        JSONObject drafts = section("drafts");
        List<MealDraft> result = new ArrayList<>();
        for (Iterator<String> it = drafts.keys(); it.hasNext();) {
            String id = it.next();
            JSONObject draft = drafts.optJSONObject(id);
            if (day.toString().equals(draft.optString("date")))
                result.add(new MealDraft(id, day, draft.optString("description")));
        }
        return result;
    }

    synchronized void deleteDraft(String id) {
        section("drafts").remove(id);
        save();
    }

    private void writeMeals(LocalDate date, List<Meal> meals) {
        if (meals.isEmpty()) {
            section("meals").remove(date.toString());
        } else {
            JSONArray values = new JSONArray();
            for (Meal meal : meals) {
                JSONObject entry = new JSONObject();
                put(entry, "id", meal.id());
                put(entry, "name", meal.name());
                put(entry, "kcal", meal.kcal());
                put(entry, "amount", meal.amount());
                put(entry, "kcalPer100", meal.kcalPer100());
                put(entry, "source", meal.source());
                values.put(entry);
            }
            put(section("meals"), date.toString(), values);
        }
        save();
    }

    synchronized SyncState syncState(String pin) {
        boolean samePc = pin.equals(data.optString("desktopPin"));
        JSONObject base = samePc ? data.optJSONObject("desktopBase") : null;
        return new SyncState(base == null ? new JSONObject() : base, syncEntries(),
                samePc ? data.optString("desktopJournal", null) : null);
    }

    synchronized void forgetDesktop() {
        data.remove("desktopBase");
        data.remove("desktopPin");
        data.remove("desktopJournal");
        save();
    }

    private JSONObject syncEntries() {
        JSONObject entries = new JSONObject();
        if (start() != null) {
            JSONObject goal = new JSONObject();
            put(goal, "start", start().toString());
            put(goal, "duration", duration());
            put(entries, "goal", goal);
        }
        JSONObject days = section("pauseDays"), weights = section("weights"), meals = section("meals");
        for (Iterator<String> it = days.keys(); it.hasNext();) {
            String date = it.next();
            put(entries, "pause/" + date, days.opt(date));
        }
        for (Iterator<String> it = weights.keys(); it.hasNext();) {
            String date = it.next();
            put(entries, "weight/" + date, weights.opt(date));
        }
        for (Iterator<String> it = meals.keys(); it.hasNext();) {
            String date = it.next();
            JSONArray list = meals.optJSONArray(date);
            for (int i = 0; i < list.length(); i++) {
                JSONObject entry = list.optJSONObject(i), row = new JSONObject();
                for (String field : new String[]{"name", "kcal", "source", "amount", "kcalPer100"}) {
                    if (entry.has(field)) put(row, field, entry.opt(field));
                }
                put(entries, "meal/" + date + "/" + entry.optString("id"), row);
            }
        }
        for (String name : new String[]{"targets", "reports", "drafts"}) {
            JSONObject values = section(name);
            String prefix = name.substring(0, name.length() - 1) + "/";
            for (Iterator<String> it = values.keys(); it.hasNext();) {
                String key = it.next();
                put(entries, prefix + key, values.opt(key));
            }
        }
        return entries;
    }

    synchronized void applyDesktop(String pin, SyncState sent, JSONObject response) throws JSONException {
        String identity = response.getString("journalId");
        UUID.fromString(identity);
        JSONObject remote = response.getJSONObject("entries");
        JSONObject current = syncEntries();
        JSONObject days = new JSONObject(), weights = new JSONObject(), meals = new JSONObject();
        JSONObject targets = new JSONObject(), reports = new JSONObject();
        JSONObject drafts = new JSONObject();
        boolean extended = response.optBoolean("extended");
        boolean draftSupport = response.optBoolean("drafts");
        String start = null;
        int duration = 30;
        for (String key : WindowsSync.keys(current, remote)) {
            Object local = current.opt(key);
            boolean fromPc = (extended || !WindowsSync.extendedKey(key)) && (draftSupport || !WindowsSync.draftKey(key))
                    && WindowsSync.same(local, sent.current().opt(key));
            Object value = fromPc ? remote.opt(key) : local;
            if (value == null || value == JSONObject.NULL) continue;
            if (key.equals("goal")) {
                JSONObject goal = (JSONObject) value;
                start = LocalDate.parse(goal.getString("start")).toString();
                duration = goal.getInt("duration");
                if (duration < 1 || duration > MAX_PAUSE_DURATION) throw new JSONException("Durée invalide.");
                continue;
            }
            String[] parts = key.split("/");
            if (parts.length < 2) throw new JSONException("Entrée invalide.");
            if (parts[0].equals("draft")) {
                if (parts.length != 2) throw new JSONException("Identifiant de repas invalide.");
                JSONObject draft = (JSONObject) value;
                LocalDate.parse(draft.getString("date"));
                String description = draft.getString("description");
                if (description.trim().isEmpty() || description.length() > 700) throw new JSONException("Description invalide.");
                put(drafts, UUID.fromString(parts[1]).toString(), draft);
                continue;
            }
            if (parts[0].equals("target")) {
                if (parts.length != 2 || !parts[1].equals("weight") && !parts[1].equals("calories")
                        || !(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() <= 0 || number.doubleValue() > (parts[1].equals("weight") ? 1000 : 100000)) {
                    throw new JSONException("Objectif invalide.");
                }
                put(targets, parts[1], value);
                continue;
            }
            if (parts[0].equals("report")) {
                if (parts.length != 2) throw new JSONException("Identifiant bilan invalide.");
                JSONObject report = (JSONObject) value;
                String title = report.getString("title"), text = report.getString("text");
                if (title.isEmpty() || title.length() > 120 || text.isEmpty() || text.length() > 12000
                        || LocalDate.parse(report.getString("start")).isAfter(LocalDate.parse(report.getString("end"))))
                    throw new JSONException("Bilan invalide.");
                OffsetDateTime.parse(report.getString("created"));
                put(reports, UUID.fromString(parts[1]).toString(), report);
                continue;
            }
            String date = LocalDate.parse(parts[1]).toString();
            switch (parts[0]) {
                case "pause" -> {
                    String status = (String) value;
                    if (!status.equals("tenu") && !status.equals(NOT_KEPT_STATUS)) throw new JSONException("État invalide.");
                    put(days, date, status);
                }
                case "weight" -> put(weights, date, value);
                case "meal" -> {
                    if (parts.length != 3) throw new JSONException("Identifiant aliment manquant.");
                    JSONObject meal = new JSONObject(((JSONObject) value).toString());
                    put(meal, "id", UUID.fromString(parts[2]).toString());
                    JSONArray list = meals.optJSONArray(date);
                    if (list == null) {
                        list = new JSONArray();
                        put(meals, date, list);
                    }
                    list.put(meal);
                }
                default -> throw new JSONException("Entrée non reconnue.");
            }
        }
        put(data, "start", start);
        put(data, "duration", duration);
        put(data, "pauseDays", days);
        put(data, "weights", weights);
        put(data, "meals", meals);
        put(data, "targets", targets);
        put(data, "reports", reports);
        put(data, "drafts", drafts);
        put(data, "desktopPin", pin);
        put(data, "desktopBase", remote);
        put(data, "desktopJournal", identity);
        save();
    }

    synchronized JSONObject snapshot() {
        LocalDate now = LocalDate.now();
        JSONObject snapshot = new JSONObject();
        put(snapshot, "schema", "pause.journal.v1");
        put(snapshot, "date_export", now.toString());
        put(snapshot, "fuseau", ZoneId.systemDefault().toString());
        if (start() != null) {
            JSONObject pause = new JSONObject();
            put(pause, "debut", start().toString());
            put(pause, "fin_incluse", end().toString());
            put(pause, "duree_jours", duration());
            put(snapshot, "objectif_pause", pause);
        }
        JSONObject days = section("pauseDays");
        JSONObject weights = section("weights");
        JSONObject meals = section("meals");
        Set<String> recorded = new TreeSet<>();
        for (JSONObject section : new JSONObject[]{days, weights, meals}) {
            for (Iterator<String> it = section.keys(); it.hasNext();) {
                recorded.add(it.next());
            }
        }
        JSONArray rows = new JSONArray();
        for (String date : recorded) {
            JSONObject row = new JSONObject();
            put(row, "date", date);
            if (days.has(date)) {
                String status = days.optString(date);
                put(row, "pause", status.equals(NOT_KEPT_STATUS) ? "non_tenu" : status);
            }
            if (weights.has(date)) put(row, "poids_kg", weights.optDouble(date));
            JSONArray entries = meals.optJSONArray(date);
            if (entries != null && entries.length() > 0) {
                double kcal = 0;
                for (int i = 0; i < entries.length(); i++) kcal += entries.optJSONObject(i).optDouble("kcal");
                put(row, "calories_notees_kcal", Math.round(kcal * 10) / 10.0);
                put(row, "aliments_notes", entries.length());
            }
            rows.put(row);
        }
        put(snapshot, "jours", rows);
        return snapshot;
    }
}
