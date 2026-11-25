package fr.pause.journal;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.UUID;

record Tracker(String id, String name, boolean pause, LocalDate start, int duration, int restDays) {
    static final String PREFIX = "track/";
    static final int MAX_NAME = 60;

    enum Day {
        DONE, MISSED, REST;
        Object value() { return this == REST ? "rest" : this == DONE; }
        static Day read(Object value) {
            if (value instanceof Boolean checked) return checked ? DONE : MISSED;
            return "rest".equals(value) ? REST : null;
        }
    }

    record Progress(int done, int rests, int streak) {}

    String key() { return PREFIX + id; }
    String dayKey(LocalDate date) { return key() + "/" + date; }
    LocalDate end() { return duration == 0 ? null : start.plusDays(duration - 1L); }
    boolean includes(LocalDate date) {
        LocalDate end = end();
        return !date.isBefore(start) && (end == null || !date.isAfter(end));
    }

    JSONObject json() throws JSONException {
        return new JSONObject().put("name", name).put("kind", pause ? "pause" : "habit")
                .put("start", start.toString()).put("duration", duration).put("restDays", restDays);
    }

    static Tracker read(String id, JSONObject value) throws JSONException {
        String name = value.getString("name").trim();
        String kind = value.getString("kind");
        LocalDate start = date(value.getString("start"));
        int duration = value.getInt("duration");
        int rests = value.has("restDays") ? value.getInt("restDays") : 0;
        if (name.isEmpty() || name.length() > MAX_NAME || !kind.equals("pause") && !kind.equals("habit")
                || duration < 0 || duration > Journal.MAX_PAUSE_DURATION || rests < 0 || rests > Journal.MAX_PAUSE_DURATION
                || (duration > 0 && rests > duration))
            throw new JSONException("Suivi invalide.");
        return new Tracker(id, name, kind.equals("pause"), start, duration, rests);
    }

    static LocalDate date(String input) throws JSONException {
        try {
            LocalDate date = LocalDate.parse(input);
            if (date.getYear() >= 1900 && date.getYear() <= 9998 && date.toString().equals(input)) return date;
        } catch (java.time.DateTimeException ignored) { }
        throw new JSONException("Date de suivi invalide.");
    }

    static Object normalize(String key, Object value) throws JSONException {
        String[] parts = key.split("/", -1);
        if (parts.length < 2 || parts.length > 3 || !UUID.fromString(parts[1]).toString().equals(parts[1]))
            throw new JSONException("Identifiant de suivi invalide.");
        if (parts.length == 2 && value instanceof JSONObject tracker) return read(parts[1], tracker).json();
        if (parts.length == 3 && Day.read(value) != null) { date(parts[2]); return value; }
        throw new JSONException("Entrée de suivi invalide.");
    }
}
