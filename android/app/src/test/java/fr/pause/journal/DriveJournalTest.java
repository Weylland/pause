package fr.pause.journal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class DriveJournalTest {
    @Test
    public void sharedHistory() throws Exception {
        JSONObject shared = new JSONObject(new String(Files.readAllBytes(Paths.get("../../tests/drive.json")), StandardCharsets.UTF_8));
        DriveJournal journal = new DriveJournal(shared.getJSONObject("snapshot"));
        JSONArray batches = shared.getJSONArray("batches");
        for (int i = 0; i < batches.length(); i++) journal.replay(DriveJournal.parse(batches.getJSONObject(i)));
        JSONObject expected = shared.getJSONObject("expected"), actual = journal.snapshot(5, 0);
        assertTrue(actual.toString(), WindowsSync.same(normalized(expected), normalized(actual)));
        assertTrue(WindowsSync.same(normalized(actual), normalized(new DriveJournal(new JSONObject(actual.toString())).snapshot(5, 0))));

        DriveJournal.Plan plan = journal.plan(shared.getJSONObject("baseline"), shared.getJSONObject("local"), null);
        List<String> upload = new ArrayList<>(), conflicts = new ArrayList<>();
        for (DriveJournal.Edit edit : plan.upload()) upload.add(edit.key());
        for (DriveJournal.Conflict conflict : plan.conflicts()) conflicts.add(conflict.key());
        assertEquals(keys(shared.getJSONArray("upload")), upload);
        assertEquals(keys(shared.getJSONArray("conflicts")), conflicts);
    }

    private static JSONObject normalized(JSONObject snapshot) throws Exception {
        JSONObject result = new JSONObject(snapshot.toString());
        JSONObject alternatives = result.getJSONObject("alternatives"), indexed = new JSONObject();
        for (String key : WindowsSync.keys(alternatives)) indexed.put(key, indexed(alternatives.getJSONArray(key)));
        return result.put("alternatives", indexed).put("batches", indexed(result.getJSONArray("batches")));
    }

    private static JSONObject indexed(JSONArray values) throws Exception {
        JSONObject result = new JSONObject();
        for (int i = 0; i < values.length(); i++) result.put(String.valueOf(i), values.get(i));
        return result;
    }

    private static List<String> keys(JSONArray values) throws Exception {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) keys.add(values.getString(i));
        return keys;
    }
}
