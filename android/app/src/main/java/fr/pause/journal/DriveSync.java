package fr.pause.journal;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

final class DriveSync {
    private static final Semaphore active = new Semaphore(1);
    private final Context context;
    private final Journal local;
    private final SharedPreferences preferences;
    private final Map<String, DriveJournal.Batch> cache = new HashMap<>();
    private volatile String status = "Google Drive n’est pas connecté.";
    private volatile List<DriveJournal.Conflict> conflicts = List.of();

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
            preferences.edit().remove("account").apply();
            cache.clear();
            conflicts = List.of();
            status = "Google Drive déconnecté de ce téléphone.";
        } finally { active.release(); }
    }

    void sync(String token, boolean connect, DriveJournal.Resolution resolution) throws Exception {
        if (!active.tryAcquire()) return;
        try { exchange(token, connect, resolution); }
        finally { active.release(); }
    }

    private void exchange(String token, boolean connect, DriveJournal.Resolution resolution) throws Exception {
        DriveFiles drive = new DriveFiles(token);
        String account = drive.account();
        String previousAccount = preferences.getString("account", null);
        if (!account.equals(previousAccount)) {
            if (!connect) throw new IOException("Reconnecte le même compte Google pour poursuivre la synchro.");
            cache.clear();
            preferences.edit().putString("account", account).apply();
        }
        String writer = preferences.getString("writer", null);
        if (writer == null) {
            writer = UUID.randomUUID().toString();
            if (!preferences.edit().putString("writer", writer).commit()) throw new IOException("Impossible d’enregistrer la configuration Drive.");
        }
        AtomicFile base = new AtomicFile(new File(context.getFilesDir(), "drive-base-" + account + ".json"));
        JSONObject last = base.getBaseFile().isFile()
                ? new JSONObject(new String(base.readFully(), StandardCharsets.UTF_8)) : new JSONObject();
        JSONObject baseline = last.optJSONObject("entries");
        if (baseline == null) baseline = new JSONObject();
        String previous = last.optString("revision");
        DriveFiles.Remote remote = drive.read(cache);
        DriveJournal.verifyHistory(previous, remote.revision());
        JSONObject entries = local.driveEntries();
        DriveJournal.Plan plan = remote.journal().plan(baseline, entries, resolution);
        if (!plan.upload().isEmpty()) {
            String batch = drive.append(writer, remote.revision(), plan.upload());
            remote = drive.read(cache);
            DriveJournal.verifyHistory(previous, remote.revision());
            if (!remote.journal().hasBatch(batch)) {
                status = "Google Drive enregistre tes changements. La vérification reprendra automatiquement.";
                return;
            }
        }
        List<DriveJournal.Conflict> pending = new ArrayList<>(remote.journal().plan(baseline, entries, null).conflicts());
        if (resolution != null) {
            String key = resolution.expected().key();
            if (plan.upload().stream().anyMatch(edit -> edit.key().equals(key) && edit.resolve()) && !remote.journal().hasConflict(key))
                pending.removeIf(conflict -> conflict.key().equals(key));
        }
        DriveJournal.Received received = remote.journal().receive(baseline, entries, pending);
        local.applyDrive(entries, received.entries());
        if (!previous.equals(remote.revision()) || !WindowsSync.same(received.baseline(), baseline)) {
            byte[] written = new JSONObject().put("revision", remote.revision()).put("entries", received.baseline()).toString().getBytes(StandardCharsets.UTF_8);
            FileOutputStream stream = base.startWrite();
            try { stream.write(written); base.finishWrite(stream); }
            catch (IOException error) { base.failWrite(stream); throw error; }
        }
        conflicts = pending;
        status = pending.isEmpty() ? "Google Drive · synchronisé à " + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                : "Google Drive · " + pending.size() + " entrée(s) à vérifier";
    }
}
