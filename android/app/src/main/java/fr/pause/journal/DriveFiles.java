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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class DriveFiles {
    static final String SCOPE = "https://www.googleapis.com/auth/drive.appdata";
    static final int MAX_BYTES = 2_097_152;
    private static final String FILES = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id";
    private static final String PREFIX = "fil-des-jours-changes-v1-";
    private final String access;

    DriveFiles(String access) { this.access = access; }
    record Remote(DriveJournal journal, String revision) {}
    private record File(String id, String name, Instant created) {}

    String account() throws Exception {
        JSONObject data = request("https://www.googleapis.com/drive/v3/about?fields=user%28permissionId%29", null, null);
        String identity = data.getJSONObject("user").getString("permissionId");
        if (identity.isBlank()) throw new IOException("Compte Google Drive introuvable.");
        return digest(identity);
    }

    Remote read(Map<String, DriveJournal.Batch> cache) throws Exception {
        List<File> files = new ArrayList<>();
        String page = "";
        do {
            String url = FILES + "?spaces=appDataFolder&pageSize=1000&fields=nextPageToken%2Cfiles%28id%2Cname%2CcreatedTime%29"
                    + "&q=" + Uri.encode("name contains '" + PREFIX + "'");
            if (!page.isEmpty()) url += "&pageToken=" + Uri.encode(page);
            JSONObject listing = request(url, null, null);
            JSONArray rows = listing.getJSONArray("files");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.getJSONObject(i);
                String name = item.getString("name");
                if (!name.startsWith(PREFIX)) throw new JSONException("Fichier d’historique Drive illisible.");
                files.add(new File(item.getString("id"), name, Instant.parse(item.getString("createdTime"))));
            }
            if (files.size() > 10000) throw new JSONException("Historique Drive trop volumineux.");
            page = listing.optString("nextPageToken");
        } while (!page.isEmpty());

        DriveJournal journal = new DriveJournal();
        files.sort(Comparator.comparing(File::created).thenComparing(File::id));
        List<String> revision = new ArrayList<>();
        for (File file : files) {
            DriveJournal.Batch batch = cache.get(file.id());
            if (batch == null) {
                batch = DriveJournal.parse(request(FILES + "/" + Uri.encode(file.id()) + "?alt=media", null, null));
                if (!file.name().equals(PREFIX + batch.id() + ".json")) throw new JSONException("Nom du fichier Drive invalide.");
                cache.put(file.id(), batch);
            }
            journal.replay(batch);
            revision.add(file.id());
        }
        revision.sort(String::compareTo);
        return new Remote(journal, String.join(",", revision));
    }

    String append(String writerId, String revision, List<DriveJournal.Edit> edits) throws Exception {
        JSONArray changes = DriveJournal.encode(edits);
        String id = digest(writerId + "\n" + revision + "\n" + changes);
        byte[] batch = bytes(new JSONObject().put("v", 1).put("id", id).put("edits", changes).toString());
        if (batch.length > MAX_BYTES) throw new IOException("Trop de changements à envoyer à Drive à la fois.");
        String boundary = UUID.randomUUID().toString();
        JSONObject metadata = new JSONObject().put("name", PREFIX + id + ".json")
                .put("parents", new JSONArray().put("appDataFolder")).put("mimeType", "application/json");
        String header = "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(bytes(header + metadata + "\r\n" + header));
        body.write(batch);
        body.write(bytes("\r\n--" + boundary + "--\r\n"));
        request(UPLOAD, "multipart/related; boundary=" + boundary, body.toByteArray());
        return id;
    }

    private JSONObject request(String url, String type, byte[] payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setRequestProperty("Authorization", "Bearer " + access);
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
