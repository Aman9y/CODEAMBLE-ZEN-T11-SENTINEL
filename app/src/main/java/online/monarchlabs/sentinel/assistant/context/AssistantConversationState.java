package online.monarchlabs.sentinel.assistant.context;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import online.monarchlabs.sentinel.assistant.core.AssistantErrorCode;

public class AssistantConversationState {
    private static final long CLARIFICATION_TTL_MILLIS = 5L * 60L * 1000L;
    private static final int MAX_RECENT_USER_MESSAGES = 6;

    private PendingClarification pendingClarification;
    private String lastUserCommand;
    private String lastActionableRequest;
    private String lastAssistantActionId;
    private String lastCommandStatus;
    private final List<String> recentUserMessages = new ArrayList<>();
    private ConversationSubject currentSubject;
    private AssistantErrorCode lastClarificationErrorCode;
    private int clarificationRepeatCount;

    public void rememberUserCommand(String input) {
        lastUserCommand = input;
        if (input == null || input.trim().isEmpty()) {
            return;
        }
        recentUserMessages.add(input.trim());
        while (recentUserMessages.size() > MAX_RECENT_USER_MESSAGES) {
            recentUserMessages.remove(0);
        }
    }

    public String getLastUserCommand() {
        return lastUserCommand;
    }

    public List<String> getRecentUserMessages() {
        return Collections.unmodifiableList(recentUserMessages);
    }

    public void rememberActionableRequest(String input) {
        if (input == null || input.trim().isEmpty()) {
            return;
        }
        lastActionableRequest = input.trim();
    }

    public String getLastActionableRequest() {
        return lastActionableRequest;
    }

    public void rememberSubject(String childId, String childName,
                                String intentName, List<String> appTargets,
                                String categoryName, boolean timerSubject) {
        ConversationSubject subject = new ConversationSubject();
        subject.childId = childId;
        subject.childName = childName;
        subject.intentName = intentName;
        subject.categoryName = categoryName;
        subject.timerSubject = timerSubject;
        if (appTargets != null) {
            for (String app : appTargets) {
                if (app != null && !app.trim().isEmpty() && !subject.appTargets.contains(app.trim())) {
                    subject.appTargets.add(app.trim());
                }
            }
        }
        currentSubject = subject;
    }

    public ConversationSubject getCurrentSubject() {
        return currentSubject;
    }

    public void setSubject(List<String> appTargets) {
        if (currentSubject == null) {
            currentSubject = new ConversationSubject();
        }
        currentSubject.appTargets = new ArrayList<>();
        if (appTargets != null) {
            for (String app : appTargets) {
                if (app != null && !app.trim().isEmpty()) {
                    currentSubject.appTargets.add(app.trim());
                }
            }
        }
    }

    public void setPendingClarification(AssistantErrorCode errorCode, String originalInput, long nowMillis) {
        if (errorCode == lastClarificationErrorCode) {
            clarificationRepeatCount++;
        } else {
            lastClarificationErrorCode = errorCode;
            clarificationRepeatCount = 1;
        }
        pendingClarification = new PendingClarification(
                errorCode,
                originalInput,
                nowMillis + CLARIFICATION_TTL_MILLIS);
    }

    public AssistantErrorCode getLastClarificationErrorCode() {
        return lastClarificationErrorCode;
    }

    public int getClarificationRepeatCount() {
        return clarificationRepeatCount;
    }

    public void clearClarificationRepeatCount() {
        lastClarificationErrorCode = null;
        clarificationRepeatCount = 0;
    }

    public boolean hasActivePendingClarification(long nowMillis) {
        return pendingClarification != null && !pendingClarification.isExpired(nowMillis);
    }

    public PendingClarification getPendingClarification() {
        return pendingClarification;
    }

    public void clearPendingClarification() {
        pendingClarification = null;
    }

    public String completePendingClarification(String answer, long nowMillis) {
        if (!hasActivePendingClarification(nowMillis) || answer == null || answer.trim().isEmpty()) {
            clearPendingClarification();
            return null;
        }
        String trimmedAnswer = answer.trim();
        String lowerAnswer = trimmedAnswer.toLowerCase(Locale.US);
        if ("cancel".equals(lowerAnswer) || "no".equals(lowerAnswer) || "never mind".equals(lowerAnswer)) {
            clearPendingClarification();
            return null;
        }

        PendingClarification pending = pendingClarification;
        clearPendingClarification();
        String original = pending.getOriginalInput() == null ? "" : pending.getOriginalInput().trim();
        String lowerOriginal = original.toLowerCase(Locale.US);
        switch (pending.getErrorCode()) {
            case MISSING_DURATION:
                String finalAnswer = trimmedAnswer;
                if (trimmedAnswer.matches("\\d+")) {
                    finalAnswer = trimmedAnswer + " minutes";
                }
                if (endsWithAny(lowerOriginal, " for", " to")) {
                    return original + " " + finalAnswer;
                }
                return original + (startsWithAny(finalAnswer.toLowerCase(Locale.US), "for ", "to ") ? " " : " for ") + finalAnswer;
            case MISSING_TIME_RANGE:
                if (endsWithAny(lowerOriginal, " from", " for", " between")) {
                    return original + " " + trimmedAnswer;
                }
                return original + (startsWithAny(lowerAnswer, "from ", "for ", "between ") ? " " : " from ")
                        + trimmedAnswer;
            case MISSING_APP:
            case MISSING_CATEGORY:
                return original + " " + trimmedAnswer;
            case AMBIGUOUS_TIME:
                return completeAmbiguousTime(original, lowerAnswer);
            case CONFLICT_FOUND:
                if (lowerAnswer.contains("unblock")) {
                    return resolveConflictChoice(original, "unblock");
                } else if (lowerAnswer.contains("block")) {
                    return resolveConflictChoice(original, "block");
                }
                return original;
            default:
                return original + " " + trimmedAnswer;
        }
    }

    public void rememberAssistantAction(String assistantActionId, String status) {
        this.lastAssistantActionId = assistantActionId;
        this.lastCommandStatus = status;
    }

    public String getLastAssistantActionId() {
        return lastAssistantActionId;
    }

    public String getLastCommandStatus() {
        return lastCommandStatus;
    }

    public Snapshot toSnapshot() {
        Snapshot snapshot = new Snapshot();
        snapshot.lastUserCommand = lastUserCommand;
        snapshot.lastActionableRequest = lastActionableRequest;
        snapshot.lastAssistantActionId = lastAssistantActionId;
        snapshot.lastCommandStatus = lastCommandStatus;
        snapshot.recentUserMessages = new ArrayList<>(recentUserMessages);
        snapshot.currentSubject = currentSubject;
        snapshot.lastClarificationErrorCode = lastClarificationErrorCode != null ? lastClarificationErrorCode.name() : null;
        snapshot.clarificationRepeatCount = clarificationRepeatCount;
        if (pendingClarification != null) {
            snapshot.pendingClarification = new Snapshot.PendingClarificationSnapshot();
            snapshot.pendingClarification.errorCode = pendingClarification.getErrorCode().name();
            snapshot.pendingClarification.originalInput = pendingClarification.getOriginalInput();
            snapshot.pendingClarification.expiresAtMillis = pendingClarification.getExpiresAtMillis();
        }
        return snapshot;
    }

    public void restore(Snapshot snapshot, long nowMillis) {
        recentUserMessages.clear();
        if (snapshot == null) {
            clearPendingClarification();
            return;
        }
        lastUserCommand = snapshot.lastUserCommand;
        lastActionableRequest = snapshot.lastActionableRequest;
        lastAssistantActionId = snapshot.lastAssistantActionId;
        lastCommandStatus = snapshot.lastCommandStatus;
        currentSubject = snapshot.currentSubject;
        clarificationRepeatCount = snapshot.clarificationRepeatCount;
        if (snapshot.lastClarificationErrorCode != null) {
            try {
                lastClarificationErrorCode = AssistantErrorCode.valueOf(snapshot.lastClarificationErrorCode);
            } catch (IllegalArgumentException ignored) {
                lastClarificationErrorCode = null;
            }
        } else {
            lastClarificationErrorCode = null;
        }
        if (snapshot.recentUserMessages != null) {
            recentUserMessages.addAll(snapshot.recentUserMessages);
            while (recentUserMessages.size() > MAX_RECENT_USER_MESSAGES) {
                recentUserMessages.remove(0);
            }
        }
        if (snapshot.pendingClarification != null
                && snapshot.pendingClarification.errorCode != null
                && snapshot.pendingClarification.expiresAtMillis > nowMillis) {
            try {
                pendingClarification = new PendingClarification(
                        AssistantErrorCode.valueOf(snapshot.pendingClarification.errorCode),
                        snapshot.pendingClarification.originalInput,
                        snapshot.pendingClarification.expiresAtMillis);
            } catch (IllegalArgumentException ignored) {
                pendingClarification = null;
            }
        } else {
            pendingClarification = null;
        }
    }

    public static class Snapshot {
        public String lastUserCommand;
        public String lastActionableRequest;
        public String lastAssistantActionId;
        public String lastCommandStatus;
        public List<String> recentUserMessages;
        public ConversationSubject currentSubject;
        public PendingClarificationSnapshot pendingClarification;
        public String lastClarificationErrorCode;
        public int clarificationRepeatCount;

        public static class PendingClarificationSnapshot {
            public String errorCode;
            public String originalInput;
            public long expiresAtMillis;
        }
    }

    public static class ConversationSubject {
        public String childId;
        public String childName;
        public String intentName;
        public List<String> appTargets = new ArrayList<>();
        public String categoryName;
        public boolean timerSubject;

        public boolean hasSingleAppTarget() {
            return appTargets != null && appTargets.size() == 1;
        }

        public String getSingleAppTarget() {
            return hasSingleAppTarget() ? appTargets.get(0) : null;
        }
    }

    private String completeAmbiguousTime(String original, String lowerAnswer) {
        if (!("am".equals(lowerAnswer) || "pm".equals(lowerAnswer))) {
            return original + " " + lowerAnswer;
        }
        return original.replaceAll("(?i)\\bfrom\\s+(\\d{1,2})\\s+to\\s+(\\d{1,2})\\b",
                "from $1 " + lowerAnswer + " to $2 " + lowerAnswer);
    }

    private boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean endsWithAny(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private String resolveConflictChoice(String original, String choice) {
        String replaced = original.replaceAll("(?i)\\b(block|unblock)\\s+(and|or)\\s+(block|unblock)\\b", choice);
        if (!replaced.equals(original)) {
            return replaced;
        }
        return choice + " " + original.replaceAll("(?i)\\b(block|unblock)\\b", "").replaceAll("\\s+", " ").trim();
    }
}
