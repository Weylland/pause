package fr.pause.journal;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

final class DriveSync {
    private static final int COMPACT_AFTER = 40, DELETE_PER_SYNC = 20;
    private static final Baseline EMPTY = new Baseline(0, List.of(), new JSONObject());
    private static final Semaphore active = new Semaphore(1);
    private final Context context;
    private final Journal local;
    private final SharedPreferences preferences;
    private DriveCache cache;
    private volatile String status = "Google Drive n’est pas connecté.";
    private volatile List<DriveJournal.Conflict> conflicts = List.of();

    private record Baseline(long generation, List<String> batches, JSONObject entries) {}

    DriveSync(Context context, Journal local) {
        this.context = context.getApplicationContext();
        this.local = local;
        preferences = this.context.getSharedPreferences("fil.des.jours.drive", Context.MODE_PRIVATE);
        if (connected()) status = "Google Drive connecté.";
    }

    boolean connected() { return preferences.contains("account"); }
    String status() { return status; }
    List<DriveJournal.Conflict> conflicts() { return conflicts; }
    void setStatus(String text) { status = text; }

    void disconnect() {
        if (!active.tryAcquire()) return;
        try {
            String account = preferences.getString("account", null);
            if (account != null) DriveCache.delete(context, account);
            preferences.edit().remove("account").apply();
            cache = null;
            conflicts = List.of();
            status = "Google Drive déconnecté de ce téléphone.";
        } finally { active.release(); }
    }

    void sync(String token, boolean connect, boolean replace, DriveJournal.Resolution resolution) throws Exception {
        if (!active.tryAcquire()) return;
        try { exchange(token, connect, replace, resolution); }
        finally { active.release(); }
    }

    private void exchange(String token, boolean connect, boolean replace, DriveJournal.Resolution resolution) throws Exception {
        DriveFiles drive = new DriveFiles(token);
        String account = drive.account();
        if (!account.equals(preferences.getString("account", null))) {
            if (!connect) throw new IOException("Reconnecte le même compte Google pour poursuivre la synchro.");
            cache = null;
            preferences.edit().putString("account", account).apply();
        }
        if (cache == null) cache = new DriveCache(context, account);
        String writer = preferences.getString("writer", null);
        if (writer == null) {
            writer = UUID.randomUUID().toString();
            if (!preferences.edit().putString("writer", writer).commit()) throw new IOException("Impossible d’enregistrer la configuration Drive.");
        }
        AtomicFile base = new AtomicFile(new File(context.getFilesDir(), "drive-base-" + account + ".json"));
        if (replace) {
            Baseline known;
            try { known = read(base); }
            catch (JSONException error) {
                base.delete();
                known = EMPTY;
            }
            drive.replace(writer, known.generation(), known.batches(), local.driveEntries());
        }
        Baseline saved = read(base);
        DriveFiles.Remote remote = drive.read(cache);
        Baseline baseline = verify(saved, remote);
        JSONObject entries = local.driveEntries();
        DriveJournal.Plan plan = remote.journal().plan(baseline.entries(), entries, resolution);
        if (!plan.upload().isEmpty()) {
            String batch = drive.append(writer, remote, plan.upload());
            remote = drive.read(cache);
            baseline = verify(saved, remote);
            if (!remote.journal().hasBatch(batch)) {
                status = "Google Drive enregistre tes changements. La vérification reprendra automatiquement.";
                return;
            }
        }
        List<DriveJournal.Conflict> pending = new ArrayList<>(remote.journal().plan(baseline.entries(), entries, null).conflicts());
        if (resolution != null) {
            String key = resolution.expected().key();
            if (plan.upload().stream().anyMatch(edit -> edit.key().equals(key) && edit.resolve()) && !remote.journal().hasConflict(key))
                pending.removeIf(conflict -> conflict.key().equals(key));
        }
        DriveJournal.Received received = remote.journal().receive(baseline.entries(), entries, pending);
        local.applyDrive(entries, received.entries());
        List<String> batches = new ArrayList<>(remote.pending());
        if (remote.generation() != saved.generation() || !batches.equals(saved.batches()) || !WindowsSync.same(received.baseline(), saved.entries()))
            write(base, new Baseline(remote.generation(), batches, received.baseline()));
        conflicts = pending;
        status = pending.isEmpty() ? "Google Drive · synchronisé à " + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                : "Google Drive · " + pending.size() + " entrée(s) à vérifier";
        tidy(drive, writer, remote);
    }

    private void tidy(DriveFiles drive, String writer, DriveFiles.Remote remote) throws Exception {
        try {
            for (String id : remote.obsolete().subList(0, Math.min(DELETE_PER_SYNC, remote.obsolete().size()))) drive.delete(id);
            if (!remote.settled() || remote.pending().size() < COMPACT_AFTER) return;
            drive.compact(writer, remote, cache);
            cache.save();
        } catch (IOException ignored) { }
    }

    private static Baseline verify(Baseline saved, DriveFiles.Remote remote) throws IOException {
        if (remote.reset() > saved.generation()) return EMPTY;
        boolean missing = remote.generation() < saved.generation();
        for (String batch : saved.batches()) missing |= !remote.journal().hasBatch(batch);
        if (missing) throw new IOException("Des fichiers de synchro Drive manquent. Ton journal local est conservé. Si cela dure, remplace le contenu de Drive depuis cet appareil.");
        return saved;
    }

    private static Baseline read(AtomicFile base) throws IOException, JSONException {
        if (!base.getBaseFile().isFile()) return EMPTY;
        JSONObject saved = new JSONObject(new String(base.readFully(), StandardCharsets.UTF_8));
        if (saved.has("revision")) return new Baseline(0, List.of(), saved.getJSONObject("entries"));
        JSONArray rows = saved.getJSONArray("batches");
        List<String> batches = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) batches.add(rows.getString(i));
        return new Baseline(saved.getLong("generation"), batches, saved.getJSONObject("entries"));
    }

    private static void write(AtomicFile base, Baseline baseline) throws IOException, JSONException {
        byte[] written = new JSONObject().put("generation", baseline.generation()).put("batches", new JSONArray(baseline.batches()))
                .put("entries", baseline.entries()).toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream stream = base.startWrite();
        try { stream.write(written); base.finishWrite(stream); }
        catch (IOException error) { base.failWrite(stream); throw error; }
    }
}
