package fr.pause.journal;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DriveFiles {
    static final String SCOPE = "https://www.googleapis.com/auth/drive.appdata";
    private static final int MAX_BYTES = 8_388_608;
    private static final String FILES = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id";
    private static final Pattern BATCH = Pattern.compile("fil-des-jours-changes-v1-([0-9a-f]{32})\\.json");
    private static final Pattern SNAPSHOT = Pattern.compile("fil-des-jours-snapshot-v1-([0-9]{1,12})-[0-9a-f]{32}\\.json");
    private final String access;

    DriveFiles(String access) { this.access = access; }
    record Remote(DriveJournal journal, long generation, long reset, Set<String> pending, List<String> obsolete, boolean settled) {}
    private record File(String id, String batch, long generation, Instant created) {}

    String account() throws Exception {
        JSONObject data = request("https://www.googleapis.com/drive/v3/about?fields=user%28permissionId%29", null, null);
        String identity = data.getJSONObject("user").getString("permissionId");
        if (identity.isBlank()) throw new IOException("Compte Google Drive introuvable.");
        return digest(identity);
    }

    Remote read(DriveCache cache) throws Exception {
        List<File> files = list();
        files.sort(Comparator.comparing(File::created).thenComparing(File::id));
        File origin = null;
        for (File file : files)
            if (file.batch() == null && (origin == null || file.generation() > origin.generation())) origin = file;
        DriveJournal journal = new DriveJournal();
        long reset = 0;
        List<String> used = new ArrayList<>();
        if (origin != null) {
            JSONObject snapshot = content(origin, cache);
            if (snapshot.getInt("v") != 1 || snapshot.getLong("generation") != origin.generation()) throw new JSONException("État Drive illisible.");
            journal = new DriveJournal(snapshot);
            reset = snapshot.getLong("reset");
            used.add(origin.id());
        }
        List<File> pending = new ArrayList<>();
        for (File file : files) if (file.batch() != null && !journal.hasBatch(file.batch())) pending.add(file);
        Set<String> batches = new TreeSet<>();
        for (File file : pending) {
            DriveJournal.Batch batch = DriveJournal.parse(content(file, cache));
            if (!batch.id().equals(file.batch())) throw new JSONException("Nom du fichier Drive invalide.");
            journal.replay(batch);
            batches.add(batch.id());
            used.add(file.id());
        }
        cache.keep(used);
        cache.save();
        boolean settled = origin == null
                || Duration.between(origin.created(), files.get(files.size() - 1).created()).compareTo(Duration.ofDays(1)) >= 0;
        List<String> obsolete = new ArrayList<>();
        if (origin != null && settled) for (File file : files) if (!used.contains(file.id())) obsolete.add(file.id());
        return new Remote(journal, origin == null ? 0 : origin.generation(), reset, batches, obsolete, settled);
    }

    String append(String writer, Remote remote, List<DriveJournal.Edit> edits) throws Exception {
        JSONArray changes = DriveJournal.encode(edits);
        String id = digest(writer + "\n" + remote.generation() + "\n" + String.join(",", remote.pending()) + "\n" + changes).substring(0, 32);
        create("fil-des-jours-changes-v1-" + id + ".json", new JSONObject().put("v", 1).put("id", id).put("edits", changes));
        return id;
    }

    void compact(String writer, Remote remote, DriveCache cache) throws Exception {
        long generation = remote.generation() + 1;
        JSONObject snapshot = remote.journal().snapshot(generation, remote.reset());
        cache.add(createSnapshot(writer, generation, snapshot), snapshot);
    }

    void replace(String writer, long known, List<String> batches, JSONObject entries) throws Exception {
        long generation = known;
        Set<String> covered = new TreeSet<>(batches);
        for (File file : list()) {
            generation = Math.max(generation, file.generation());
            if (file.batch() != null) covered.add(file.batch());
        }
        generation += ThreadLocalRandom.current().nextInt(1_000, 1_000_000);
        createSnapshot(writer, generation, DriveJournal.snapshot(generation, generation, covered, entries, new JSONObject()));
    }

    void delete(String id) throws IOException {
        HttpURLConnection connection = open(FILES + "/" + Uri.encode(id));
        try {
            connection.setRequestMethod("DELETE");
            int status = connection.getResponseCode();
            if ((status < 200 || status >= 300) && status != HttpURLConnection.HTTP_NOT_FOUND)
                throw new IOException("Google Drive n’a pas effacé un ancien fichier.");
        } finally { connection.disconnect(); }
    }

    private List<File> list() throws Exception {
        List<File> files = new ArrayList<>();
        String page = "";
        do {
            String url = FILES + "?spaces=appDataFolder&pageSize=1000&fields=nextPageToken%2Cfiles%28id%2Cname%2CcreatedTime%29"
                    + "&q=" + Uri.encode("trashed = false");
            if (!page.isEmpty()) url += "&pageToken=" + Uri.encode(page);
            JSONObject listing = request(url, null, null);
            JSONArray rows = listing.getJSONArray("files");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.getJSONObject(i);
                String name = item.getString("name");
                Matcher batch = BATCH.matcher(name), snapshot = SNAPSHOT.matcher(name);
                boolean change = batch.matches();
                if (!change && !snapshot.matches()) throw new IOException("La synchro Drive vient d’une version plus récente. Mets à jour Fil des jours.");
                files.add(new File(item.getString("id"), change ? batch.group(1) : null, change ? 0 : Long.parseLong(snapshot.group(1)),
                        Instant.parse(item.getString("createdTime"))));
            }
            if (files.size() > 10000) throw new JSONException("Historique Drive trop volumineux.");
            page = listing.optString("nextPageToken");
        } while (!page.isEmpty());
        return files;
    }

    private JSONObject content(File file, DriveCache cache) throws Exception {
        JSONObject known = cache.get(file.id());
        if (known != null) return known;
        JSONObject content = request(FILES + "/" + Uri.encode(file.id()) + "?alt=media", null, null);
        cache.add(file.id(), content);
        return content;
    }

    private String createSnapshot(String writer, long generation, JSONObject snapshot) throws Exception {
        String id = digest(writer + "\n" + snapshot).substring(0, 32);
        return create("fil-des-jours-snapshot-v1-" + generation + "-" + id + ".json", snapshot);
    }

    private String create(String name, JSONObject content) throws Exception {
        byte[] encoded = bytes(content.toString());
        if (encoded.length > MAX_BYTES) throw new IOException("Trop de changements à envoyer à Drive à la fois.");
        String boundary = UUID.randomUUID().toString();
        JSONObject metadata = new JSONObject().put("name", name)
                .put("parents", new JSONArray().put("appDataFolder")).put("mimeType", "application/json");
        String header = "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(bytes(header + metadata + "\r\n" + header));
        body.write(encoded);
        body.write(bytes("\r\n--" + boundary + "--\r\n"));
        return request(UPLOAD, "multipart/related; boundary=" + boundary, body.toByteArray()).getString("id");
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setRequestProperty("Authorization", "Bearer " + access);
        return connection;
    }

    private JSONObject request(String url, String type, byte[] payload) throws Exception {
        HttpURLConnection connection = open(url);
        try {
            if (payload != null) {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", type);
                connection.setFixedLengthStreamingMode(payload.length);
                connection.setDoOutput(true);
                try (var output = connection.getOutputStream()) { output.write(payload); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Google Drive est indisponible (" + status + "). Tes saisies restent sur le téléphone.");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (response.size() + count > MAX_BYTES) throw new IOException("Réponse Drive trop volumineuse.");
                    response.write(buffer, 0, count);
                }
                return new JSONObject(new String(response.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static String digest(String value) throws Exception {
        StringBuilder hex = new StringBuilder(64);
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes(value))) {
            hex.append(Character.forDigit((b & 0xFF) >>> 4, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
