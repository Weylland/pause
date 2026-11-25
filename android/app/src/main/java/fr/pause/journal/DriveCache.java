package fr.pause.journal;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

final class DriveCache {
    private final AtomicFile file;
    private final Map<String, String> contents = new HashMap<>();
    private boolean changed;

    DriveCache(Context context, String account) {
        file = file(context, account);
        if (!file.getBaseFile().isFile()) return;
        try {
            JSONObject saved = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            for (String id : WindowsSync.keys(saved)) contents.put(id, saved.getString(id));
        } catch (IOException | JSONException error) { contents.clear(); }
    }

    JSONObject get(String id) throws JSONException {
        String content = contents.get(id);
        return content == null ? null : new JSONObject(content);
    }

    void add(String id, JSONObject content) {
        contents.put(id, content.toString());
        changed = true;
    }

    void keep(Collection<String> ids) { changed |= contents.keySet().retainAll(ids); }

    void save() {
        if (!changed) return;
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(new JSONObject(contents).toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
            changed = false;
        } catch (IOException error) { if (stream != null) file.failWrite(stream); }
    }

    static void delete(Context context, String account) { file(context, account).delete(); }

    private static AtomicFile file(Context context, String account) {
        return new AtomicFile(new File(context.getFilesDir(), "drive-cache-" + account + ".json"));
    }
}
