package online.monarchlabs.sentinel.assistant.execution;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class AssistantCommandOutbox {
    private static final String PREF_NAME = "assistant_command_outbox";
    private static final String KEY_PENDING_COMMANDS = "pending_commands";

    private final SharedPreferences preferences;
    private final Gson gson;
    private final Type listType;

    public AssistantCommandOutbox(Context context) {
        this.preferences = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
        this.listType = new TypeToken<List<PendingCommand>>() { }.getType();
    }

    public synchronized boolean save(SentinelCommand command) {
        if (command == null || command.getCommandId() == null || command.getCommandId().trim().isEmpty()) {
            return false;
        }
        List<PendingCommand> commands = readAll();
        removeById(commands, command.getCommandId());
        commands.add(PendingCommand.from(command));
        return writeAll(commands);
    }

    public synchronized boolean remove(String commandId) {
        if (commandId == null || commandId.trim().isEmpty()) {
            return false;
        }
        List<PendingCommand> commands = readAll();
        boolean removed = removeById(commands, commandId);
        if (removed) {
            writeAll(commands);
        }
        return removed;
    }

    public synchronized boolean contains(String commandId) {
        if (commandId == null || commandId.trim().isEmpty()) {
            return false;
        }
        for (PendingCommand command : readAll()) {
            if (commandId.equals(command.commandId)) {
                return true;
            }
        }
        return false;
    }

    public synchronized List<PendingCommand> getAll() {
        return new ArrayList<>(readAll());
    }

    private List<PendingCommand> readAll() {
        String json = preferences.getString(KEY_PENDING_COMMANDS, "[]");
        List<PendingCommand> commands = gson.fromJson(json, listType);
        return commands == null ? new ArrayList<>() : commands;
    }

    private boolean writeAll(List<PendingCommand> commands) {
        preferences.edit().putString(KEY_PENDING_COMMANDS, gson.toJson(commands, listType)).apply();
        return true;
    }

    private boolean removeById(List<PendingCommand> commands, String commandId) {
        boolean removed = false;
        Iterator<PendingCommand> iterator = commands.iterator();
        while (iterator.hasNext()) {
            PendingCommand command = iterator.next();
            if (commandId.equals(command.commandId)) {
                iterator.remove();
                removed = true;
            }
        }
        return removed;
    }

    public static class PendingCommand {
        public String commandId;
        public String childId;
        public String parentId;
        public long createdAtMillis;
        public Map<String, Object> firebasePayload;

        public PendingCommand() {
        }

        static PendingCommand from(SentinelCommand command) {
            PendingCommand pendingCommand = new PendingCommand();
            pendingCommand.commandId = command.getCommandId();
            pendingCommand.childId = command.getChildId();
            pendingCommand.parentId = command.getParentId();
            pendingCommand.createdAtMillis = command.getCreatedAtMillis();
            pendingCommand.firebasePayload = new HashMap<>();
            pendingCommand.firebasePayload.put("commandId", command.getCommandId());
            pendingCommand.firebasePayload.put("assistantActionId", command.getAssistantActionId());
            pendingCommand.firebasePayload.put("idempotencyKey", command.getIdempotencyKey());
            pendingCommand.firebasePayload.put("deviceId", command.getChildId());
            pendingCommand.firebasePayload.put("childId", command.getChildId());
            pendingCommand.firebasePayload.put("parentId", command.getParentId());
            pendingCommand.firebasePayload.put("type", command.getCommandType().name());
            pendingCommand.firebasePayload.put("source", command.getSource().name());
            pendingCommand.firebasePayload.put("status", "pending");
            pendingCommand.firebasePayload.put("targetPackages", command.getTargetPackages());
            pendingCommand.firebasePayload.put("targetCategories", command.getTargetCategories());
            pendingCommand.firebasePayload.put("payload", command.getPayload());
            pendingCommand.firebasePayload.put("createdAtMillis", command.getCreatedAtMillis());
            pendingCommand.firebasePayload.put("expiresAtMillis", command.getExpiresAtMillis());
            pendingCommand.firebasePayload.put("schemaVersion", 2);
            return pendingCommand;
        }
    }
}