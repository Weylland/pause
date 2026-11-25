package fr.pause.journal;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class DriveJournal {
    record Edit(String key, Object before, Object after, boolean resolve) {}
    record Batch(String id, List<Edit> edits) {}
    record Conflict(String key, Object local, Object cloud, List<Object> alternatives) {}
    record Resolution(Conflict expected, Object choice) {}
    record Plan(List<Edit> upload, List<Conflict> conflicts) {}
    record Received(JSONObject entries, JSONObject baseline) {}

    private final JSONObject entries = new JSONObject();
    private final Map<String, List<Object>> alternatives = new HashMap<>();
    private final Set<String> replayed = new TreeSet<>();

    DriveJournal() {}

    DriveJournal(JSONObject snapshot) throws JSONException {
        JSONArray batches = snapshot.getJSONArray("batches");
        for (int i = 0; i < batches.length(); i++) replayed.add(batches.getString(i));
        JSONObject values = snapshot.getJSONObject("entries");
        for (String key : WindowsSync.keys(values)) entries.put(key, values.get(key));
        JSONObject conflicts = snapshot.getJSONObject("alternatives");
        for (String key : WindowsSync.keys(conflicts)) {
            JSONArray rows = conflicts.getJSONArray(key);
            List<Object> choices = new ArrayList<>(rows.length());
            for (int i = 0; i < rows.length(); i++) choices.add(rows.get(i));
            alternatives.put(key, choices);
        }
    }

    JSONObject snapshot(long generation, long reset) throws JSONException {
        JSONObject conflicts = new JSONObject();
        for (Map.Entry<String, List<Object>> item : alternatives.entrySet()) {
            JSONArray rows = new JSONArray();
            for (Object value : item.getValue()) rows.put(withNull(value));
            conflicts.put(item.getKey(), rows);
        }
        return snapshot(generation, reset, replayed, entries, conflicts);
    }

    static JSONObject snapshot(long generation, long reset, Collection<String> batches, JSONObject entries, JSONObject alternatives) throws JSONException {
        return new JSONObject().put("v", 1).put("generation", generation).put("reset", reset)
                .put("batches", new JSONArray(batches)).put("entries", entries).put("alternatives", alternatives);
    }

    boolean hasBatch(String id) { return replayed.contains(id); }
    boolean hasConflict(String key) { return alternatives.containsKey(key); }

    void replay(Batch batch) throws JSONException {
        if (!replayed.add(batch.id())) return;
        for (Edit edit : batch.edits()) {
            Object current = entries.opt(edit.key());
            if (WindowsSync.same(current, edit.before()) && (edit.resolve() || !hasConflict(edit.key()))) {
                assign(entries, edit.key(), edit.after());
                if (edit.resolve()) alternatives.remove(edit.key());
            } else if (!WindowsSync.same(current, edit.after())) {
                List<Object> choices = alternatives.computeIfAbsent(edit.key(), ignored -> new ArrayList<>());
                if (choices.stream().noneMatch(value -> WindowsSync.same(value, edit.after()))) choices.add(edit.after());
            }
        }
    }

    Plan plan(JSONObject baseline, JSONObject local, Resolution resolution) {
        List<Edit> upload = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        for (String key : keys(baseline, local)) {
            Object before = baseline.opt(key), here = local.opt(key), there = entries.opt(key);
            boolean localChanged = !WindowsSync.same(before, here), cloudChanged = !WindowsSync.same(before, there);
            List<Object> choices = alternatives.getOrDefault(key, List.of());
            if (!choices.isEmpty() || localChanged && cloudChanged && !WindowsSync.same(here, there)) {
                Conflict conflict = new Conflict(key, here, there, choices);
                if (resolution != null && sameConflict(resolution.expected(), conflict))
                    upload.add(new Edit(key, there, resolution.choice(), true));
                else conflicts.add(conflict);
            } else if (localChanged && !WindowsSync.same(here, there)) upload.add(new Edit(key, there, here, false));
        }
        return new Plan(upload, conflicts);
    }

    Received receive(JSONObject baseline, JSONObject local, List<Conflict> conflicts) throws JSONException {
        Set<String> blocked = new HashSet<>(alternatives.keySet());
        for (Conflict conflict : conflicts) blocked.add(conflict.key());
        JSONObject combined = new JSONObject(), next = new JSONObject();
        for (String key : keys(baseline, local)) {
            if (blocked.contains(key)) {
                assign(combined, key, local.opt(key));
                assign(next, key, baseline.opt(key));
            } else {
                assign(combined, key, entries.opt(key));
                assign(next, key, entries.opt(key));
            }
        }
        return new Received(combined, next);
    }

    static Batch parse(JSONObject batch) throws JSONException {
        String id = batch.getString("id");
        if (batch.getInt("v") != 1 || !id.matches("[0-9a-f]{32}")) throw new JSONException("Historique Drive illisible.");
        JSONArray rows = batch.getJSONArray("edits");
        List<Edit> edits = new ArrayList<>(rows.length());
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String key = row.getString("key");
            if (!keys.add(key) || !key.matches("[a-z]+(?:/[a-z0-9-]+){0,2}")) throw new JSONException("Modification Drive illisible.");
            edits.add(new Edit(key, row.get("before"), row.get("after"), row.optBoolean("resolve")));
        }
        return new Batch(id, edits);
    }

    static JSONArray encode(List<Edit> edits) throws JSONException {
        JSONArray result = new JSONArray();
        for (Edit edit : edits) result.put(new JSONObject().put("key", edit.key())
                .put("before", withNull(edit.before())).put("after", withNull(edit.after())).put("resolve", edit.resolve()));
        return result;
    }

    private Set<String> keys(JSONObject baseline, JSONObject local) {
        Set<String> keys = new TreeSet<>(WindowsSync.keys(baseline, local, entries));
        keys.addAll(alternatives.keySet());
        return keys;
    }

    private static boolean sameConflict(Conflict first, Conflict second) {
        if (!first.key().equals(second.key()) || !WindowsSync.same(first.local(), second.local())
                || !WindowsSync.same(first.cloud(), second.cloud()) || first.alternatives().size() != second.alternatives().size()) return false;
        for (int i = 0; i < first.alternatives().size(); i++)
            if (!WindowsSync.same(first.alternatives().get(i), second.alternatives().get(i))) return false;
        return true;
    }

    private static void assign(JSONObject target, String key, Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) target.remove(key);
        else target.put(key, value);
    }

    private static Object withNull(Object value) { return value == null ? JSONObject.NULL : value; }
}
