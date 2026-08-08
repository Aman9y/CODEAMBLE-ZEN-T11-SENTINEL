package online.monarchlabs.sentinel.assistant.history;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class AssistantActivityHistoryStore {
    private static final String PREF_NAME = "assistant_activity_history";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 50;

    private final SharedPreferences preferences;
    private final Gson gson;
    private final Type listType;
    private final String childId;

    public AssistantActivityHistoryStore(Context context, String childId) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
        this.listType = new TypeToken<List<HistoryEntry>>() { }.getType();
        this.childId = childId;
    }

    public synchronized List<HistoryEntry> getAll() {
        if (childId == null || childId.trim().isEmpty()) {
            return new ArrayList<>();
        }
        List<HistoryEntry> entries = readAll();
        List<HistoryEntry> filtered = new ArrayList<>();
        for (HistoryEntry entry : entries) {
            if (childId.equals(entry.childId)) {
                filtered.add(entry);
            }
        }
        Collections.sort(filtered, Comparator.comparingLong(item -> item.createdAtMillis));
        return filtered;
    }

    public synchronized HistoryEntry getByCommandId(String commandId) {
        if (commandId == null || commandId.trim().isEmpty() || childId == null || childId.trim().isEmpty()) {
            return null;
        }
        for (HistoryEntry entry : readAll()) {
            if (commandId.equals(entry.commandId) && childId.equals(entry.childId)) {
                return entry;
            }
        }
        return null;
    }

    public synchronized void upsert(HistoryEntry entry) {
        if (entry == null || entry.commandId == null || entry.commandId.trim().isEmpty()
                || entry.childId == null || entry.childId.trim().isEmpty()) {
            return;
        }
        List<HistoryEntry> entries = readAll();
        boolean replaced = false;
        for (int index = 0; index < entries.size(); index++) {
            if (entry.commandId.equals(entries.get(index).commandId)) {
                entries.set(index, entry);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            entries.add(entry);
        }
        trim(entries);
        writeAll(entries);
    }

    public synchronized void clear() {
        if (childId == null || childId.trim().isEmpty()) {
            return;
        }
        List<HistoryEntry> entries = readAll();
        Iterator<HistoryEntry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            HistoryEntry entry = iterator.next();
            if (childId.equals(entry.childId)) {
                iterator.remove();
            }
        }
        writeAll(entries);
    }

    private List<HistoryEntry> readAll() {
        String json = preferences.getString(KEY_ENTRIES, "[]");
        List<HistoryEntry> entries = gson.fromJson(json, listType);
        return entries == null ? new ArrayList<>() : entries;
    }

    private void writeAll(List<HistoryEntry> entries) {
        preferences.edit().putString(KEY_ENTRIES, gson.toJson(entries, listType)).apply();
    }

    private void trim(List<HistoryEntry> entries) {
        // Group entries by childId
        java.util.Map<String, List<HistoryEntry>> grouped = new java.util.HashMap<>();
        for (HistoryEntry entry : entries) {
            String cid = entry.childId != null ? entry.childId : "unknown";
            if (!grouped.containsKey(cid)) {
                grouped.put(cid, new ArrayList<>());
            }
            grouped.get(cid).add(entry);
        }

        List<HistoryEntry> trimmedList = new ArrayList<>();
        for (java.util.Map.Entry<String, List<HistoryEntry>> group : grouped.entrySet()) {
            List<HistoryEntry> groupEntries = group.getValue();
            if (groupEntries.size() > MAX_ENTRIES) {
                Collections.sort(groupEntries, Comparator.comparingLong(item -> item.createdAtMillis));
                while (groupEntries.size() > MAX_ENTRIES) {
                    groupEntries.remove(0);
                }
            }
            trimmedList.addAll(groupEntries);
        }

        entries.clear();
        entries.addAll(trimmedList);
    }

    public static class HistoryEntry {
        public String commandId;
        public String childId;
        public String userRequest;
        public String summary;
        public String status;
        public String result;
        public String debugStatus;
        public long createdAtMillis;
        public long updatedAtMillis;

        public static HistoryEntry pending(String commandId, String childId,
                String userRequest, String summary, long now) {
            HistoryEntry entry = new HistoryEntry();
            entry.commandId = commandId;
            entry.childId = childId;
            entry.userRequest = userRequest;
            entry.summary = summary;
            entry.status = "Pending";
            entry.result = "Sending request...";
            entry.debugStatus = "pending_sync";
            entry.createdAtMillis = now;
            entry.updatedAtMillis = now;
            return entry;
        }
    }
}