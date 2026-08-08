package online.monarchlabs.sentinel.assistant.suggestions;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.util.HashMap;
import java.util.Map;

public final class SuggestionRepository {
    private final String parentId;
    private final String childId;
    private final Map<String, Long> activeDismissals = new HashMap<>();

    public SuggestionRepository(String parentId, String childId) {
        this.parentId = parentId;
        this.childId = childId;
    }

    public void loadDismissals(Runnable onComplete) {
        if (parentId == null || childId == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("assistant_suggestions")
                .child(parentId)
                .child(childId)
                .get()
                .addOnCompleteListener(task -> {
                    activeDismissals.clear();
                    if (task.isSuccessful() && task.getResult() != null) {
                        for (DataSnapshot suggestionSnap : task.getResult().getChildren()) {
                            Long expiresAt = suggestionSnap.child("expiresAt").getValue(Long.class);
                            if (expiresAt != null && expiresAt > System.currentTimeMillis()) {
                                activeDismissals.put(suggestionSnap.getKey(), expiresAt);
                            }
                        }
                    }
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
    }

    private String sanitizeKey(String key) {
        if (key == null) {
            return "null";
        }
        return key.replace(".", "_")
                .replace("#", "_")
                .replace("$", "_")
                .replace("[", "_")
                .replace("]", "_")
                .replace(":", "_");
    }

    public boolean isDismissed(String suggestionId) {
        String sanitized = sanitizeKey(suggestionId);
        Long expiresAt = activeDismissals.get(sanitized);
        if (expiresAt == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiresAt) {
            activeDismissals.remove(sanitized);
            return false;
        }
        return true;
    }

    public void dismiss(String suggestionId, long durationMillis) {
        String sanitized = sanitizeKey(suggestionId);
        long now = System.currentTimeMillis();
        long expiresAt = now + durationMillis;
        activeDismissals.put(sanitized, expiresAt);

        if (parentId == null || childId == null) {
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("dismissed", true);
        data.put("dismissedAt", now);
        data.put("expiresAt", expiresAt);
        data.put("originalId", suggestionId);

        FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("assistant_suggestions")
                .child(parentId)
                .child(childId)
                .child(sanitized)
                .setValue(data);
    }
}
