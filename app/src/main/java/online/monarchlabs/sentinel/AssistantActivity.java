package online.monarchlabs.sentinel;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import online.monarchlabs.sentinel.assistant.context.AssistantConversationStore;
import online.monarchlabs.sentinel.assistant.history.AssistantActivityHistoryStore;
import online.monarchlabs.sentinel.assistant.core.AssistantIntent;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningRequest;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningResult;
import online.monarchlabs.sentinel.assistant.core.CommandType;
import online.monarchlabs.sentinel.assistant.core.CommandSource;
import online.monarchlabs.sentinel.assistant.context.AssistantConversationState;
import online.monarchlabs.sentinel.assistant.execution.AssistantCommandExecutor;
import online.monarchlabs.sentinel.assistant.execution.AssistantCommandFactory;
import online.monarchlabs.sentinel.assistant.execution.SentinelCommand;
import online.monarchlabs.sentinel.assistant.planner.AssistantPlan;
import online.monarchlabs.sentinel.assistant.planner.LocalRuleBasedCommandPlanner;
import online.monarchlabs.sentinel.assistant.state.AssistantLiveStateRepository;
import online.monarchlabs.sentinel.assistant.validation.AssistantPlanValidator;
import online.monarchlabs.sentinel.assistant.validation.ValidationResult;
import online.monarchlabs.sentinel.workers.AssistantCommandOutboxScheduler;

public class AssistantActivity extends BaseActivity {
    public static final String EXTRA_SELECTED_CHILD_ID = "selected_child_device_id";
    public static final String EXTRA_SELECTED_CHILD_NAME = "selected_child_name";

    private LinearLayout messageList;
    private ScrollView messageScroll;
    private EditText input;
    private TextView clearHistoryButton;
    private TextView tvScreenTimeValue;
    private android.widget.ProgressBar pbScreenTime;
    private TextView tvBlockedAppsCount;
    private TextView tvCurrentApp;
    private SessionManager sessionManager;
    private LocalRuleBasedCommandPlanner planner;
    private AssistantPlanValidator validator;
    private AssistantCommandFactory commandFactory;
    private AssistantCommandExecutor commandExecutor;
    private AssistantConversationState conversationState;
    private AssistantConversationStore conversationStore;
    private AssistantActivityHistoryStore historyStore;
    private AssistantLiveStateRepository liveStateRepository;
    private AssistantLiveStateRepository.LiveStateSnapshot latestLiveState;
    private String selectedChildId;
    private String selectedChildName;
    private final Map<String, ValueEventListener> pendingHistoryListeners = new HashMap<>();
    private final Map<String, DatabaseReference> pendingHistoryReferences = new HashMap<>();
    private online.monarchlabs.sentinel.assistant.suggestions.SuggestionEngine suggestionEngine;
    private online.monarchlabs.sentinel.assistant.suggestions.SuggestionRepository suggestionRepository;
    private online.monarchlabs.sentinel.assistant.suggestions.SuggestionPipeline suggestionPipeline;
    private List<online.monarchlabs.sentinel.assistant.suggestions.AssistantSuggestion> activeSuggestions = new ArrayList<>();
    private online.monarchlabs.sentinel.assistant.reliability.AssistantRequestProcessor requestProcessor;
    private online.monarchlabs.sentinel.assistant.reliability.AssistantClarificationManager clarificationManager;

    private static class ChatMessage {
        private static int globalIdCounter = 0;
        final int id = ++globalIdCounter;
        enum Type { USER, ASSISTANT, STATUS_CARD, RESULT_CARD, HISTORY_EVENT }
        Type type;
        String text;
        String title;
        String detail;
        int colorRes;
        AssistantPlan plan;
        String originalInput;
        String actionableRequest;

        static ChatMessage user(String text) {
            ChatMessage m = new ChatMessage();
            m.type = Type.USER;
            m.text = text;
            return m;
        }

        static ChatMessage assistant(String text) {
            ChatMessage m = new ChatMessage();
            m.type = Type.ASSISTANT;
            m.text = text;
            return m;
        }

        static ChatMessage statusCard(String title, String message, int colorRes) {
            ChatMessage m = new ChatMessage();
            m.type = Type.STATUS_CARD;
            m.title = title;
            m.text = message;
            m.colorRes = colorRes;
            return m;
        }


        static ChatMessage resultCard(String title, String summary, String detail) {
            ChatMessage m = new ChatMessage();
            m.type = Type.RESULT_CARD;
            m.title = title;
            m.text = summary;
            m.detail = detail;
            return m;
        }

        static ChatMessage historyEvent(String commandId) {
            ChatMessage m = new ChatMessage();
            m.type = Type.HISTORY_EVENT;
            m.text = commandId;
            return m;
        }
    }

    private final List<ChatMessage> conversationMessages = new ArrayList<>();
    private final java.util.Set<String> animatedSignatures = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        setContentView(R.layout.activity_assistant);

        sessionManager = new SessionManager(this);
        planner = new LocalRuleBasedCommandPlanner();
        selectedChildId = getIntent().getStringExtra(EXTRA_SELECTED_CHILD_ID);
        selectedChildName = getIntent().getStringExtra(EXTRA_SELECTED_CHILD_NAME);
        if (selectedChildName == null || selectedChildName.trim().isEmpty()) {
            selectedChildName = "Selected child";
        }

        validator = new AssistantPlanValidator();
        commandFactory = new AssistantCommandFactory();
        commandExecutor = new AssistantCommandExecutor(this);
        AssistantCommandOutboxScheduler.scheduleRetry(this);
        conversationState = new AssistantConversationState();
        conversationStore = new AssistantConversationStore(this, selectedChildId);
        historyStore = new AssistantActivityHistoryStore(this, selectedChildId);
        liveStateRepository = new AssistantLiveStateRepository(this);

        suggestionEngine = new online.monarchlabs.sentinel.assistant.suggestions.SuggestionEngine();
        suggestionEngine.registerRule(new online.monarchlabs.sentinel.assistant.suggestions.AppTimerRecommendationRule());
        suggestionRepository = new online.monarchlabs.sentinel.assistant.suggestions.SuggestionRepository(
                sessionManager.getParentUserId(), selectedChildId);
        suggestionPipeline = new online.monarchlabs.sentinel.assistant.suggestions.SuggestionPipeline(
                suggestionEngine, suggestionRepository);

        clarificationManager = new online.monarchlabs.sentinel.assistant.reliability.AssistantClarificationManager();

        online.monarchlabs.sentinel.assistant.context.ConversationContextEngine contextEngine = new online.monarchlabs.sentinel.assistant.context.ConversationContextEngine();
        online.monarchlabs.sentinel.assistant.handlers.AssistantHandlerRegistry handlerRegistry = new online.monarchlabs.sentinel.assistant.handlers.AssistantHandlerRegistry();
        handlerRegistry.register(new online.monarchlabs.sentinel.assistant.handlers.CommandHandler());
        handlerRegistry.register(new online.monarchlabs.sentinel.assistant.handlers.KnowledgeHandler(
                () -> latestLiveState));
        handlerRegistry.register(new online.monarchlabs.sentinel.assistant.handlers.SystemHandler());

        requestProcessor = new online.monarchlabs.sentinel.assistant.reliability.AssistantRequestProcessor(
                planner, validator, clarificationManager, contextEngine, handlerRegistry);

        conversationStore.restoreInto(conversationState);

        messageList = findViewById(R.id.messageList);
        messageScroll = findViewById(R.id.messageScroll);
        input = findViewById(R.id.etAssistantInput);
        messageList = findViewById(R.id.messageList);
        messageScroll = findViewById(R.id.messageScroll);
        input = findViewById(R.id.etAssistantInput);
        clearHistoryButton = findViewById(R.id.btnClearHistory);
        
        tvScreenTimeValue = findViewById(R.id.tvScreenTimeValue);
        pbScreenTime = findViewById(R.id.pbScreenTime);
        tvBlockedAppsCount = findViewById(R.id.tvBlockedAppsCount);
        tvCurrentApp = findViewById(R.id.tvCurrentApp);

        FrameLayout back = findViewById(R.id.btnBack);
        FrameLayout send = findViewById(R.id.btnSend);
        android.widget.ImageView mic = findViewById(R.id.btnMic); // Updated to ImageView for mic based on new layout

        back.setOnClickListener(v -> finish());
        send.setOnClickListener(v -> handleSend());
        mic.setOnClickListener(v -> startSpeechToText());
        clearHistoryButton.setOnClickListener(v -> showClearHistoryConfirmation());
        
        // Chip Listeners
        findViewById(R.id.chip1).setOnClickListener(v -> {
            input.setText("Block YouTube");
            input.setSelection(input.length());
        });
        findViewById(R.id.chip2).setOnClickListener(v -> {
            input.setText("Set YouTube limit to 30 minutes");
            input.setSelection(input.length());
        });
        findViewById(R.id.chip3).setOnClickListener(v -> {
            input.setText("Show today usage");
            input.setSelection(input.length());
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                handleSend();
                return true;
            }
            return false;
        });
        loadHistoryIntoConversation();
        rebuildAssistantFeed();
        refreshLiveAssistantState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cleanup all active command listeners to prevent memory leaks
        if (commandExecutor != null) {
            commandExecutor.cleanup();
        }
        cleanupPendingHistoryListeners();
        conversationStore.save(conversationState);
    }

    private void handleSend() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        input.setText("");
        addUserMessage(text);
        conversationState.rememberUserCommand(text);
        conversationStore.save(conversationState);

        String contextualInput = resolveContextualInput(text);
        if (!contextualInput.equals(text)) {
            addAssistantMessage("Understood. I’ll treat that as: " + contextualInput);
        }

        refreshLiveAssistantState();

        requestProcessor.processRequest(
                contextualInput,
                sessionManager.getParentUserId(),
                selectedChildId,
                selectedChildName,
                latestLiveState != null ? latestLiveState.installedApps : null,
                conversationState,
                conversationStore,
                result -> runOnUiThread(() -> handleRequestProcessorResult(result, text))
        );
    }

    private void handleRequestProcessorResult(online.monarchlabs.sentinel.assistant.reliability.AssistantResult<?> result, String originalInput) {
        if (!result.success) {
            online.monarchlabs.sentinel.assistant.reliability.AssistantError error = result.error;
            if (error.code == online.monarchlabs.sentinel.assistant.core.AssistantErrorCode.CLARIFICATION_LOOP_DETECTED) {
                addStatusCard("Command Cancelled", error.message, R.color.error_700);
            } else if (error.code == online.monarchlabs.sentinel.assistant.core.AssistantErrorCode.PARSE_UNKNOWN_COMMAND) {
                addStatusCard("Unsupported", error.message, R.color.warning_700);
            } else if (error.code == online.monarchlabs.sentinel.assistant.core.AssistantErrorCode.CONFIRMATION_CANCELLED) {
                addResultCard("Cancelled", "Clarification cancelled", "No command was created.");
            } else {
                addStatusCard("Clarification needed", error.message, R.color.warning_700);
            }
            return;
        }

        Object data = result.data;

        if (data instanceof online.monarchlabs.sentinel.assistant.handlers.CommandHandler.CommandResult) {
            online.monarchlabs.sentinel.assistant.handlers.CommandHandler.CommandResult cmdResult =
                    (online.monarchlabs.sentinel.assistant.handlers.CommandHandler.CommandResult) data;
            AssistantPlan plan = cmdResult.plan;
            SentinelCommand command = commandFactory.create(plan, CommandSource.PARENT_TEXT,
                    latestLiveState != null ? latestLiveState.appNameToPackage : null);

            if (command.getTargetPackages() != null) {
                if (command.getTargetPackages().contains("online.monarchlabs.sentinel")) {
                    addStatusCard("Action Restricted", "The Sentinel Parental Control app cannot be blocked or restricted.", R.color.error_700);
                    return;
                }
                if (command.getTargetPackages().contains("com.android.settings")) {
                    addStatusCard("Action Restricted", "The device Settings app cannot be blocked or restricted.", R.color.error_700);
                    return;
                }
            }
            executePlan(plan, command, originalInput, plan.getSummary());
        } else if (data instanceof online.monarchlabs.sentinel.assistant.handlers.KnowledgeHandler.KnowledgeResult) {
            online.monarchlabs.sentinel.assistant.handlers.KnowledgeHandler.KnowledgeResult knowledgeResult =
                    (online.monarchlabs.sentinel.assistant.handlers.KnowledgeHandler.KnowledgeResult) data;

            // Build the detailed card
            online.monarchlabs.sentinel.assistant.providers.UsageCard card =
                    online.monarchlabs.sentinel.assistant.providers.UsageCardBuilder.buildDetailed(knowledgeResult.usageSummary);

            String details = "Screen Time: " + card.totalScreenTime + "\n" +
                             "Blocked Apps: " + card.blockedAppsCount + "\n" +
                             "Current App: " + (card.currentApp != null ? card.currentApp : "None");

            addResultCard(card.title, "Based on today's activity", details);
        } else if (data instanceof online.monarchlabs.sentinel.assistant.handlers.SystemHandler.SystemResult) {
            online.monarchlabs.sentinel.assistant.handlers.SystemHandler.SystemResult sysResult =
                    (online.monarchlabs.sentinel.assistant.handlers.SystemHandler.SystemResult) data;
            AssistantPlanningResult planResult = sysResult.rawPlanResult;
            if (planResult.getType() == AssistantPlanningResult.ResultType.INFO) {
                addAssistantMessage(planResult.getMessage());
            } else {
                addStatusCard("System", planResult.getMessage() != null ? planResult.getMessage() : "Processed", R.color.primary_500);
            }
        }
    }

    private static final int SPEECH_REQUEST_CODE = 1234;

    private void startSpeechToText() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command...");
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Speech recognition is not supported on this device.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String spokenText = results.get(0);
                input.setText(spokenText);
                handleSend();
            }
        }
    }

    private void loadHistoryIntoConversation() {
        conversationMessages.clear();
        for (AssistantActivityHistoryStore.HistoryEntry entry : historyStore.getAll()) {
            if (entry.userRequest != null && !entry.userRequest.trim().isEmpty()) {
                conversationMessages.add(ChatMessage.user(entry.userRequest));
            } else if (entry.summary != null && !entry.summary.trim().isEmpty()) {
                conversationMessages.add(ChatMessage.user(entry.summary));
            }
            if (entry.commandId != null) {
                conversationMessages.add(ChatMessage.historyEvent(entry.commandId));
            }
        }
    }

    private void rebuildAssistantFeed() {
        messageList.removeAllViews();
        // Static dashboard is handled by XML. 
        // We only render active proactive suggestions and conversation history below it.
        renderProactiveSuggestions();
        resumePendingHistoryListeners();
        renderConversationMessages();
        refreshClearHistoryVisibility();
    }

    private void renderConversationMessages() {
        for (int i = 0; i < conversationMessages.size(); i++) {
            ChatMessage msg = conversationMessages.get(i);
            String baseSignature = "msg_" + i;

            switch (msg.type) {
                case USER:
                    addUserMessageDirect(msg, baseSignature);
                    break;
                case ASSISTANT:
                    addAssistantMessageDirect(msg, baseSignature);
                    break;
                case STATUS_CARD:
                    addStatusCardDirect(msg.title, msg.text, msg.colorRes, baseSignature);
                    break;
                case RESULT_CARD:
                    addResultCardDirect(msg.title, msg.text, msg.detail, baseSignature);
                    break;
                case HISTORY_EVENT:
                    AssistantActivityHistoryStore.HistoryEntry entry = historyStore.getByCommandId(msg.text);
                    if (entry != null) {
                        TextView applyingView = addAssistantMessageDirect("Applying on child's device...");
                        animateIfNew(applyingView, baseSignature + "_APPLY", () -> {
                            if (isPendingStatus(entry.status)) {
                                applyPulseAnimation(applyingView);
                            }
                        });

                        if ("Success".equalsIgnoreCase(entry.status)) {
                            TextView successView = addAssistantMessageDirect("I've successfully applied: " + entry.summary);
                            animateIfNew(successView, baseSignature + "_SUCCESS");
                        } else if ("Failed".equalsIgnoreCase(entry.status)) {
                            String resultMsg = (entry.result != null && !entry.result.trim().isEmpty())
                                    ? entry.result : "Child device responded with an error.";
                            TextView failedView = addAssistantMessageDirect("Failed to apply: " + resultMsg);
                            animateIfNew(failedView, baseSignature + "_FAILED");
                        }
                    }
                    break;
            }
        }
    }

    private void renderProactiveSuggestions() {
        if (activeSuggestions != null) {
            for (online.monarchlabs.sentinel.assistant.suggestions.AssistantSuggestion suggestion : activeSuggestions) {
                addSuggestionCard(suggestion);
            }
        }
    }

    private void resumePendingHistoryListeners() {
        cleanupPendingHistoryListeners();
        for (AssistantActivityHistoryStore.HistoryEntry entry : historyStore.getAll()) {
            if (isPendingStatus(entry.status)) {
                attachPendingHistoryListener(entry);
            }
        }
    }

    private void rememberClarification(online.monarchlabs.sentinel.assistant.core.AssistantErrorCode errorCode,
                                       String originalInput) {
        conversationState.setPendingClarification(errorCode, originalInput, System.currentTimeMillis());
        conversationStore.save(conversationState);
    }

    private void refreshLiveAssistantState() {
        liveStateRepository.refresh(selectedChildId, snapshot -> {
            latestLiveState = snapshot;
            if (planner != null) {
                planner.setInstalledApps(snapshot.installedApps);
            }
            
            // Update Dashboard UI values
            runOnUiThread(() -> {
                if (snapshot != null) {
                    // Calculate basic metrics from snapshot if possible, or leave as default.
                    // Assuming totalScreenTime, blocked count, currentApp are derivable or default values:
                    long totalUsageMillis = 0;
                    if (snapshot.appUsageMillis != null) {
                        for (Long usage : snapshot.appUsageMillis.values()) {
                            totalUsageMillis += usage;
                        }
                    }
                    long minutes = totalUsageMillis / (60 * 1000);
                    long hours = minutes / 60;
                    long remainingMinutes = minutes % 60;
                    String formattedScreenTime;
                    if (hours > 0) {
                        formattedScreenTime = hours + "h " + remainingMinutes + "m";
                    } else {
                        formattedScreenTime = remainingMinutes + "m";
                    }
                    tvScreenTimeValue.setText(formattedScreenTime);

                    if (pbScreenTime != null) {
                        pbScreenTime.setVisibility(android.view.View.GONE);
                    }
                    android.view.View tvLimitRemainingView = findViewById(R.id.tvLimitRemaining);
                    if (tvLimitRemainingView != null) {
                        tvLimitRemainingView.setVisibility(android.view.View.GONE);
                    }

                    int blockedCount = snapshot.blockedPackages != null ? snapshot.blockedPackages.size() : 0;
                    tvBlockedAppsCount.setText(String.valueOf(blockedCount));
                    
                    if (snapshot.foregroundApp != null && !snapshot.foregroundApp.isEmpty()) {
                        String currentAppName = getReadableAppName(snapshot.foregroundApp, snapshot);
                        tvCurrentApp.setText(currentAppName);
                    } else {
                        tvCurrentApp.setText("None");
                    }
                }
            });

            if (suggestionRepository != null && suggestionPipeline != null) {
                suggestionRepository.loadDismissals(() -> {
                    online.monarchlabs.sentinel.assistant.suggestions.SuggestionContext context =
                            new online.monarchlabs.sentinel.assistant.suggestions.SuggestionContext(
                                    snapshot,
                                    System.currentTimeMillis(),
                                    online.monarchlabs.sentinel.assistant.suggestions.SuggestionConfig.defaultConfig()
                            );
                    activeSuggestions = suggestionPipeline.process(context);
                    runOnUiThread(this::rebuildAssistantFeed);
                });
            } else {
                runOnUiThread(this::rebuildAssistantFeed);
            }
        });
    }

    private String getReadableAppName(String packageName, online.monarchlabs.sentinel.assistant.state.AssistantLiveStateRepository.LiveStateSnapshot snapshot) {
        if (packageName == null || packageName.isEmpty()) {
            return "None";
        }
        if (packageName.startsWith("online.monarchlabs.sentinel")) {
            return "Sentinel";
        }
        if (packageName.toLowerCase(java.util.Locale.US).contains("launcher") || 
            packageName.equals("com.android.systemui") ||
            packageName.equals("com.google.android.apps.nexuslauncher") ||
            packageName.equals("com.sec.android.app.launcher") ||
            packageName.equals("com.oppo.launcher") ||
            packageName.equals("com.huawei.android.launcher") ||
            packageName.equals("com.miui.home")) {
            return "Home Screen";
        }
        if (packageName.equals("com.android.settings")) {
            return "Settings";
        }
        if (packageName.equals("com.google.android.googlequicksearchbox")) {
            return "Google Search";
        }
        if (packageName.equals("com.android.chrome")) {
            return "Chrome";
        }
        if (packageName.equals("com.android.phone") || 
            packageName.equals("com.android.server.telecom") || 
            packageName.equals("com.android.dialer")) {
            return "Phone";
        }
        if (packageName.equals("com.android.contacts") || 
            packageName.equals("com.google.android.contacts")) {
            return "Contacts";
        }
        if (snapshot != null) {
            if (snapshot.packageToAppName != null && snapshot.packageToAppName.containsKey(packageName)) {
                return snapshot.packageToAppName.get(packageName);
            }
            if (snapshot.appNameToPackage != null) {
                for (Map.Entry<String, String> entry : snapshot.appNameToPackage.entrySet()) {
                    if (entry.getValue().equals(packageName)) {
                        return entry.getKey();
                    }
                }
            }
        }
        return packageName;
    }

    // Deprecated addSuggestionRow and addChip methods removed as they are now static in XML

    private void executePlan(AssistantPlan plan, SentinelCommand command, String userRequest, String actionableRequest) {
        conversationState.rememberActionableRequest(actionableRequest);
        rememberConversationSubject(plan);
        conversationStore.save(conversationState);
        long now = System.currentTimeMillis();
        AssistantActivityHistoryStore.HistoryEntry historyEntry =
            AssistantActivityHistoryStore.HistoryEntry.pending(
                command.getCommandId(),
                command.getChildId(),
                userRequest,
                plan.getSummary(),
                now);
        historyStore.upsert(historyEntry);
        conversationState.rememberAssistantAction(command.getAssistantActionId(), "pending_sync");
        conversationMessages.add(ChatMessage.historyEvent(command.getCommandId()));
        attachPendingHistoryListener(historyEntry);
        refreshClearHistoryVisibility();
        runOnUiThread(this::rebuildAssistantFeed);
        commandExecutor.enqueue(command, new AssistantCommandExecutor.ExecutionCallback() {
            @Override
            public void onQueued(SentinelCommand queuedCommand) {
                conversationState.rememberAssistantAction(queuedCommand.getAssistantActionId(), "queued");
                conversationStore.save(conversationState);
                runOnUiThread(() -> updateHistoryEntry(
                    queuedCommand.getCommandId(),
                    "Pending",
                    "Applying on child's device...",
                    "queued"));
            }

            @Override
            public void onAck(java.util.Map<String, Object> ack) {
                String status = String.valueOf(ack.get("status"));
                String message = ack.get("message") != null
                        ? String.valueOf(ack.get("message"))
                        : "Child device responded.";
                conversationState.rememberAssistantAction(command.getAssistantActionId(), status);
                conversationStore.save(conversationState);
                runOnUiThread(() -> {
                    android.util.Log.d("AssistantActivity", "ACK for " + command.getCommandType()
                            + " status=" + status + " childId=" + command.getChildId()
                            + " targets=" + command.getTargetPackages());
                    mirrorBlockedStateFromAssistantAck(command, ack);
                    if ("APPLIED".equalsIgnoreCase(status)
                            || "PARTIALLY_APPLIED".equalsIgnoreCase(status)) {
                        updateHistoryEntry(command.getCommandId(), "Success", message, status);
                    } else if ("FAILED".equalsIgnoreCase(status)) {
                        updateHistoryEntry(command.getCommandId(), "Failed", message, status);
                    } else if ("PENDING_PERMISSION".equalsIgnoreCase(status)
                            || "EXPIRED".equalsIgnoreCase(status)
                            || "DUPLICATE_IGNORED".equalsIgnoreCase(status)) {
                        updateHistoryEntry(command.getCommandId(), "Failed", message, status);
                    } else {
                        updateHistoryEntry(command.getCommandId(),
                                "Pending",
                                "Applying on child's device...",
                                status);
                    }
                });
            }

            @Override
            public void onError(String message) {
                conversationState.rememberAssistantAction(command.getAssistantActionId(), "failed");
                conversationStore.save(conversationState);
                runOnUiThread(() -> updateHistoryEntry(
                        command.getCommandId(),
                        "Failed",
                        message,
                        "failed"));
            }
        });
    }

    private TextView actionButton(String text, int colorRes, int backgroundRes) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(ContextCompat.getColor(this, colorRes));
        button.setBackgroundResource(backgroundRes);
        button.setPadding(dp(14), dp(9), dp(14), dp(9));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(8), 0, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private void addUserMessage(String text) {
        conversationMessages.add(ChatMessage.user(text));
        runOnUiThread(this::rebuildAssistantFeed);
    }

    private void addUserMessageDirect(ChatMessage msg, String signature) {
        TextView bubble = bubble(msg.text, true);
        messageList.addView(bubble);
        animateIfNew(bubble, signature + "_USER");
        scrollToBottom();
    }

    private void addAssistantMessage(String text) {
        conversationMessages.add(ChatMessage.assistant(text));
        runOnUiThread(this::rebuildAssistantFeed);
    }

    private void addAssistantMessageDirect(ChatMessage msg, String signature) {
        TextView bubble = bubble(msg.text, false);
        messageList.addView(bubble);
        animateIfNew(bubble, signature + "_AST");
        scrollToBottom();
    }

    private TextView addAssistantMessageDirect(String text) {
        TextView bubble = bubble(text, false);
        messageList.addView(bubble);
        // Note: Used for status updates that are generated dynamically and not stored in a persistent ChatMessage
        scrollToBottom();
        return bubble;
    }

    private TextView bubble(String text, boolean user) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setLineSpacing(dp(2), 1f);
        view.setTextColor(ContextCompat.getColor(this, user ? android.R.color.white : R.color.neutral_800));
        view.setBackgroundResource(user ? R.drawable.bg_button_primary : R.drawable.bg_white_rounded);
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = user ? Gravity.END : Gravity.START;
        params.setMargins(user ? dp(48) : 0, dp(6), user ? 0 : dp(48), dp(6));
        view.setLayoutParams(params);
        return view;
    }

    private void addStatusCard(String title, String message, int titleColor) {
        conversationMessages.add(ChatMessage.statusCard(title, message, titleColor));
        runOnUiThread(this::rebuildAssistantFeed);
    }

    private void addStatusCardDirect(String title, String message, int titleColor, String signature) {
        LinearLayout card = baseCard();
        addCardTitle(card, title, titleColor);
        addCardLine(card, message);
        messageList.addView(card);
        animateIfNew(card, signature + "_STATUS");
        scrollToBottom();
    }

    private void addResultCard(String title, String summary, String detail) {
        conversationMessages.add(ChatMessage.resultCard(title, summary, detail));
        runOnUiThread(this::rebuildAssistantFeed);
    }

    private void addResultCardDirect(String title, String summary, String detail, String signature) {
        LinearLayout card = baseCard();
        addCardTitle(card, title, R.color.primary_700);
        addCardLine(card, summary);
        addCardLine(card, detail);
        messageList.addView(card);
        animateIfNew(card, signature + "_RESULT");
        scrollToBottom();
    }



    private LinearLayout baseCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_glass);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, dp(8));
        card.setLayoutParams(params);
        return card;
    }

    private TextView addCardTitle(LinearLayout card, String title, int colorRes) {
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(this, colorRes));
        titleView.setTextSize(15);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(titleView);
        return titleView;
    }

    private TextView addCardLine(LinearLayout card, String text) {
        TextView line = new TextView(this);
        line.setText(text);
        line.setTextColor(ContextCompat.getColor(this, R.color.neutral_700));
        line.setTextSize(13);
        line.setPadding(0, dp(6), 0, 0);
        card.addView(line);
        return line;
    }

    private String formatDuration(long millis) {
        long minutes = millis / (60L * 1000L);
        if (minutes >= 60 && minutes % 60 == 0) {
            return (minutes / 60) + " hours";
        }
        return minutes + " minutes";
    }

    private void scrollToBottom() {
        messageScroll.post(() -> messageScroll.smoothScrollTo(0, messageList.getBottom()));
    }

    private void animateIfNew(android.view.View view, String signature) {
        animateIfNew(view, signature, null);
    }

    private void animateIfNew(android.view.View view, String signature, Runnable onEnd) {
        if (!animatedSignatures.contains(signature)) {
            animatedSignatures.add(signature);
            view.setAlpha(0f);
            view.setTranslationY(dp(15));
            view.post(() -> {
                view.animate().alpha(1f).translationY(0f).setDuration(300)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .withEndAction(onEnd)
                        .start();
            });
        } else {
            if (onEnd != null) {
                onEnd.run();
            }
        }
    }

    private void applyPulseAnimation(android.view.View view) {
        Object tag = view.getTag(R.id.messageList);
        if (!(tag instanceof android.animation.ObjectAnimator)) {
            android.animation.ObjectAnimator pulse = android.animation.ObjectAnimator.ofFloat(view, "alpha", 1f, 0.4f, 1f);
            pulse.setDuration(1200);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            view.setTag(R.id.messageList, pulse);
            pulse.start();
        }
    }

    private void updateHistoryEntry(String commandId, String status,
                                    String result, String debugStatus) {
        AssistantActivityHistoryStore.HistoryEntry entry = historyStore.getByCommandId(commandId);
        if (entry == null) {
            return;
        }
        entry.status = status;
        entry.result = result;
        entry.debugStatus = debugStatus;
        entry.updatedAtMillis = System.currentTimeMillis();
        historyStore.upsert(entry);

        if (!isPendingStatus(status)) {
            detachPendingHistoryListener(commandId);
        }
        refreshClearHistoryVisibility();
        runOnUiThread(this::rebuildAssistantFeed);
    }

    private void attachPendingHistoryListener(AssistantActivityHistoryStore.HistoryEntry entry) {
        if (entry == null
                || entry.commandId == null || entry.commandId.trim().isEmpty()
                || entry.childId == null || entry.childId.trim().isEmpty()
                || !entry.childId.equals(selectedChildId)
                || pendingHistoryListeners.containsKey(entry.commandId)) {
            return;
        }

        DatabaseReference reference = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("commands")
                .child(entry.childId)
                .child(entry.commandId);
        ValueEventListener listener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    return;
                }

                String status = snapshot.child("status").getValue(String.class);
                String message = snapshot.child("message").getValue(String.class);
                if (message == null || message.trim().isEmpty()) {
                    message = snapshot.child("ackMessage").getValue(String.class);
                }
                if (status == null || status.trim().isEmpty()) {
                    return;
                }

                if ("APPLIED".equalsIgnoreCase(status)
                        || "PARTIALLY_APPLIED".equalsIgnoreCase(status)) {
                    updateHistoryEntry(entry.commandId, "Success",
                            message == null || message.trim().isEmpty() ? entry.summary : message,
                            status);
                } else if ("FAILED".equalsIgnoreCase(status)
                        || "PENDING_PERMISSION".equalsIgnoreCase(status)
                        || "EXPIRED".equalsIgnoreCase(status)
                        || "DUPLICATE_IGNORED".equalsIgnoreCase(status)) {
                    updateHistoryEntry(entry.commandId, "Failed",
                            message == null || message.trim().isEmpty()
                                    ? "Couldn't complete that request."
                                    : message,
                            status);
                } else {
                    updateHistoryEntry(entry.commandId, "Pending",
                            "Applying on child's device...",
                            status);
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.w("AssistantActivity", "Assistant history listener cancelled: "
                        + error.getMessage());
            }
        };

        reference.addValueEventListener(listener);
        pendingHistoryReferences.put(entry.commandId, reference);
        pendingHistoryListeners.put(entry.commandId, listener);
    }

    private void detachPendingHistoryListener(String commandId) {
        if (commandId == null) {
            return;
        }
        DatabaseReference reference = pendingHistoryReferences.remove(commandId);
        ValueEventListener listener = pendingHistoryListeners.remove(commandId);
        if (reference != null && listener != null) {
            reference.removeEventListener(listener);
        }
    }

    private void cleanupPendingHistoryListeners() {
        for (Map.Entry<String, ValueEventListener> entry : pendingHistoryListeners.entrySet()) {
            DatabaseReference reference = pendingHistoryReferences.get(entry.getKey());
            if (reference != null) {
                reference.removeEventListener(entry.getValue());
            }
        }
        pendingHistoryListeners.clear();
        pendingHistoryReferences.clear();
    }

    private void showClearHistoryConfirmation() {
        if (historyStore.getAll().isEmpty()) {
            Toast.makeText(this, "No assistant history to clear.", Toast.LENGTH_SHORT).show();
            return;
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear assistant history?")
                .setMessage("This removes saved assistant activity from this device.")
                .setPositiveButton("Clear", (dialog, which) -> {
                    historyStore.clear();
                    conversationMessages.clear();
                    cleanupPendingHistoryListeners();
                    rebuildAssistantFeed();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshClearHistoryVisibility() {
        if (clearHistoryButton == null) {
            return;
        }
        clearHistoryButton.setVisibility(historyStore.getAll().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private boolean isPendingStatus(String status) {
        return "Pending".equalsIgnoreCase(status);
    }

    private String buildHistoryMeta(AssistantActivityHistoryStore.HistoryEntry entry) {
        return entry.status + "  •  " + formatHistoryTimestamp(entry.createdAtMillis);
    }

    private String buildHistoryResult(AssistantActivityHistoryStore.HistoryEntry entry) {
        if (entry.result != null && !entry.result.trim().isEmpty()) {
            return entry.result;
        }
        return entry.summary == null ? "" : entry.summary;
    }

    private int colorForHistoryStatus(String status) {
        if ("Success".equalsIgnoreCase(status)) {
            return R.color.success_700;
        }
        if ("Failed".equalsIgnoreCase(status)) {
            return R.color.error_700;
        }
        return R.color.primary_700;
    }

    private String formatHistoryTimestamp(long timestampMillis) {
        if (timestampMillis <= 0L) {
            return "Just now";
        }
        return new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                .format(new Date(timestampMillis));
    }

    private boolean containsContextReferencePronoun(String lowerInput) {
        return lowerInput.contains("these apps")
                || lowerInput.contains("those apps")
                || lowerInput.contains("the same apps")
                || lowerInput.contains("the same app")
                || lowerInput.contains("them")
                || lowerInput.contains("these")
                || lowerInput.contains("those")
                || lowerInput.contains("all of")
                || lowerInput.contains("all these")
                || lowerInput.contains("all those")
                || lowerInput.contains(" it")
                || lowerInput.startsWith("it ")
                || lowerInput.equals("it");
    }

    private String resolveAppContextReferences(String input, List<String> appTargets) {
        if (appTargets == null || appTargets.isEmpty()) {
            return input;
        }
        String joined = joinAppTargetsForContext(appTargets);
        String lower = input.toLowerCase(Locale.US);
        String result = input;

        String[] references = {
            "all of the same apps", "all of the same app",
            "all of these apps", "all of those apps",
            "all the same apps", "all the same app",
            "all of these", "all of those", "all of them",
            "all these apps", "all those apps", "all them",
            "these apps", "those apps", "the same apps", "the same app",
            "them", "these", "those"
        };
        for (String ref : references) {
            if (lower.contains(ref)) {
                result = replaceIgnoreCase(result, ref, joined);
                lower = result.toLowerCase(Locale.US);
            }
        }

        if (Pattern.compile("\\bit\\b", Pattern.CASE_INSENSITIVE).matcher(result).find()) {
            result = result.replaceAll("(?i)\\bit\\b", joined);
        }

        return result;
    }

    private String joinAppTargetsForContext(List<String> appTargets) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < appTargets.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(appTargets.get(i));
        }
        return sb.toString();
    }

    private String replaceIgnoreCase(String source, String target, String replacement) {
        return source.replaceAll("(?i)" + Pattern.quote(target), replacement);
    }

    private boolean isRetryIntent(String lowerInput) {
        return "train again".equals(lowerInput)
                || "retry".equals(lowerInput)
                || "try again".equals(lowerInput)
                || "retry that".equals(lowerInput)
                || "do it again".equals(lowerInput)
                || "run again".equals(lowerInput)
                || "execute again".equals(lowerInput);
    }

    private String normalizeSpellingTypos(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("(?i)\\bunbock\\b", "unblock")
                .replaceAll("(?i)\\bblok\\b", "block")
                .replaceAll("(?i)\\btimmer\\b", "timer")
                .replaceAll("(?i)\\bminuets\\b", "minutes")
                .replaceAll("(?i)\\bminuet\\b", "minute");
    }

    private String resolveContextualInput(String input) {
        if (input == null) {
            return "";
        }
        String normalizedSpelling = normalizeSpellingTypos(input);
        String trimmed = normalizedSpelling.trim();
        String lower = trimmed.toLowerCase(Locale.US);

        if (isRetryIntent(lower)) {
            String previous = conversationState.getLastActionableRequest();
            if (previous != null && !previous.trim().isEmpty()) {
                return previous;
            }
        }

        AssistantConversationState.ConversationSubject subject = conversationState.getCurrentSubject();
        if (subject != null && subject.appTargets != null && !subject.appTargets.isEmpty() && containsContextReferencePronoun(lower)) {
            return resolveAppContextReferences(trimmed, subject.appTargets);
        }

        // Foreground-app fallback: when there's no conversation subject but the
        // user refers to "it" / "that app", resolve from the child's current
        // foreground app so commands like "block it" work even without history.
        if ((subject == null || subject.appTargets == null || subject.appTargets.isEmpty())
                && containsContextReferencePronoun(lower)
                && latestLiveState != null
                && latestLiveState.foregroundApp != null
                && !latestLiveState.foregroundApp.trim().isEmpty()) {
            String fgApp = latestLiveState.foregroundApp.trim().toLowerCase(Locale.US);
            String resolved = resolveAppContextReferences(trimmed, java.util.Collections.singletonList(fgApp));
            if (!resolved.equals(trimmed)) {
                return resolved;
            }
        }

        if (!looksLikeFollowUp(lower) && !containsContextReference(lower)) {
            return trimmed;
        }

        if (subject == null || !subject.hasSingleAppTarget()) {
            return trimmed;
        }
        String subjectApp = subject.getSingleAppTarget();

        String timerResolved = resolveTimerFollowUp(trimmed, lower, subjectApp, subject);
        if (timerResolved != null) {
            return timerResolved;
        }

        String blockResolved = resolveBlockFollowUp(trimmed, lower, subjectApp);
        if (blockResolved != null) {
            return blockResolved;
        }

        String previous = conversationState.getLastActionableRequest();
        if (previous == null || previous.trim().isEmpty()) {
            return trimmed;
        }

        String updatedDuration = replaceDuration(previous, trimmed);
        if (!updatedDuration.equals(previous)) {
            return updatedDuration;
        }

        if (lower.startsWith("change it to") || lower.startsWith("change that to")
                || lower.startsWith("set it to") || lower.startsWith("set that to")
                || lower.startsWith("make it") || lower.startsWith("make that")) {
            return previous + " " + trimmed;
        }
        return trimmed;
    }

    private String resolveTimerFollowUp(String trimmed, String lower,
                                        String subjectApp,
                                        AssistantConversationState.ConversationSubject subject) {
        if ((lower.contains("remove the timer") || lower.contains("delete the timer")
                || lower.contains("clear the timer") || lower.contains("cancel the timer")
                || lower.equals("remove timer") || lower.equals("delete timer")
                || lower.equals("clear timer") || lower.equals("cancel timer"))
                && subject.timerSubject) {
            return "remove timer for " + subjectApp;
        }

        if ((lower.startsWith("increase it") || lower.startsWith("increase that")
                || lower.startsWith("decrease it") || lower.startsWith("decrease that")
                || lower.startsWith("change it to") || lower.startsWith("change that to")
                || lower.startsWith("set it to") || lower.startsWith("set that to")
                || lower.startsWith("make it ") || lower.startsWith("make that "))
                && subject.timerSubject) {
            Matcher matcher = Pattern.compile(
                    "(\\d+\\s*(?:minute|minutes|min|hour|hours|hr|hrs|day|days|ghanta|ghante|din))",
                    Pattern.CASE_INSENSITIVE)
                    .matcher(trimmed);
            if (matcher.find()) {
                return "set " + subjectApp + " limit to " + matcher.group(1).trim();
            }
        }
        return null;
    }

    private String resolveBlockFollowUp(String trimmed, String lower, String subjectApp) {
        if (lower.startsWith("block it") || lower.startsWith("block that")) {
            return "block " + subjectApp;
        }
        if (lower.startsWith("unblock it") || lower.startsWith("unblock that")) {
            return "unblock " + subjectApp;
        }
        return null;
    }

    private boolean looksLikeFollowUp(String input) {
        return input.startsWith("change it")
                || input.startsWith("change that")
                || input.startsWith("set it")
                || input.startsWith("set that")
                || input.startsWith("increase it")
                || input.startsWith("increase that")
                || input.startsWith("decrease it")
                || input.startsWith("decrease that")
                || input.startsWith("make it")
                || input.startsWith("make that")
                || input.startsWith("instead ")
                || input.startsWith("actually ");
    }

    private boolean containsContextReference(String input) {
        return input.contains(" it")
                || input.contains(" that")
                || input.contains("the timer")
                || input.contains("timer")
                || input.contains("undo that");
    }

    private String replaceDuration(String previousRequest, String followUpInput) {
        if (previousRequest == null || previousRequest.trim().isEmpty()) {
            return followUpInput;
        }

        Matcher matcher = Pattern.compile(
                "(\\d+\\s*(?:minute|minutes|min|mins|m|hour|hours|h|hr|hrs|day|days|d|ghanta|ghante|din))",
                Pattern.CASE_INSENSITIVE)
                .matcher(followUpInput);
        if (!matcher.find()) {
            return previousRequest;
        }

        String newDuration = matcher.group(1).trim();
        String replaced = previousRequest.replaceFirst(
                "(?i)(for|to)\\s+\\d+\\s*(minute|minutes|min|mins|m|hour|hours|h|hr|hrs|day|days|d|ghanta|ghante|din)",
                "$1 " + newDuration);
        if (!replaced.equals(previousRequest)) {
            return replaced;
        }

        if (previousRequest.toLowerCase(Locale.US).contains("limit")) {
            return previousRequest + " to " + newDuration;
        }
        return previousRequest + " for " + newDuration;
    }

    private void rememberConversationSubject(AssistantPlan plan) {
        if (plan == null || plan.getSlots() == null) {
            return;
        }
        List<String> appTargets = plan.getSlots().getAppTargets();
        if (appTargets == null || appTargets.isEmpty()) {
            return;
        }
        boolean timerSubject = plan.getIntent() == AssistantIntent.SET_APP_TIMER
                || plan.getIntent() == AssistantIntent.REMOVE_APP_TIMER;
        conversationState.rememberSubject(
                plan.getChildId(),
                plan.getChildName(),
                plan.getIntent().name(),
                appTargets,
                plan.getSlots().getCategoryName(),
                timerSubject);
    }    private void mirrorBlockedStateFromAssistantAck(SentinelCommand command, java.util.Map<String, Object> ack) {
        if (command == null || ack == null) {
            return;
        }
        String status = String.valueOf(ack.get("status"));
        if (!"APPLIED".equalsIgnoreCase(status) && !"PARTIALLY_APPLIED".equalsIgnoreCase(status)) {
            return;
        }

        CommandType commandType = command.getCommandType();
        if (commandType != CommandType.ASSISTANT_BLOCK_APP && commandType != CommandType.ASSISTANT_BLOCK_CATEGORY
                && commandType != CommandType.ASSISTANT_UNBLOCK_APP && commandType != CommandType.ASSISTANT_UNBLOCK_ALL_APPS) {
            return;
        }

        android.util.Log.d("AssistantActivity", "Local mirror trigger from assistant ACK. Real-time listener will sync state.");

        Intent intent = new Intent("online.monarchlabs.sentinel.BLOCKED_APPS_UPDATED");
        intent.putExtra("source", "assistant_local_ack");
        sendBroadcast(intent);
        android.util.Log.d("AssistantActivity", "Broadcasted BLOCKED_APPS_UPDATED after assistant mirror");
    }

    private List<String> extractAppliedPackagesFromAck(Map<String, Object> ack) {
        List<String> packages = new java.util.ArrayList<>();
        if (ack == null) {
            return packages;
        }

        Object appliedPackages = ack.get("appliedPackages");
        if (appliedPackages instanceof java.util.List) {
            for (Object item : (java.util.List<?>) appliedPackages) {
                if (item != null) {
                    String pkg = String.valueOf(item).trim();
                    if (!pkg.isEmpty()) {
                        packages.add(pkg);
                    }
                }
            }
        }

        if (packages.isEmpty()) {
            Object appliedRuleIds = ack.get("appliedRuleIds");
            if (appliedRuleIds instanceof java.util.List) {
                for (Object item : (java.util.List<?>) appliedRuleIds) {
                    if (item != null) {
                        String pkg = String.valueOf(item).trim();
                        if (!pkg.isEmpty()) {
                            packages.add(pkg);
                        }
                    }
                }
            }
        }
        return packages;
    }

    private void addSuggestionCard(online.monarchlabs.sentinel.assistant.suggestions.AssistantSuggestion suggestion) {
        LinearLayout card = baseCard();
        card.setBackgroundResource(R.drawable.bg_card_glass);

        int titleColor = R.color.neutral_900;
        if (suggestion.priority == online.monarchlabs.sentinel.assistant.suggestions.AssistantSuggestion.Priority.HIGH) {
            titleColor = R.color.primary_700;
        }

        addCardTitle(card, suggestion.title, titleColor);
        addCardLine(card, suggestion.description);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(12), 0, 0);

        TextView dismiss = actionButton(suggestion.dismissLabel, R.color.neutral_700, R.drawable.bg_white_rounded);
        TextView confirm = actionButton(suggestion.actionLabel, android.R.color.white, R.drawable.bg_button_primary);

        dismiss.setOnClickListener(v -> {
            suggestionRepository.dismiss(suggestion.id, online.monarchlabs.sentinel.assistant.suggestions.SuggestionConfig.defaultConfig().defaultCooldownMillis);
            rebuildAssistantFeed();
        });

        confirm.setOnClickListener(v -> {
            suggestionRepository.dismiss(suggestion.id, java.util.concurrent.TimeUnit.DAYS.toMillis(7));
            executeSuggestionAction(suggestion);
        });

        actions.addView(dismiss);
        actions.addView(confirm);
        card.addView(actions);
        messageList.addView(card);
        scrollToBottom();
    }

    private void executeSuggestionAction(online.monarchlabs.sentinel.assistant.suggestions.AssistantSuggestion suggestion) {
        if (suggestion.actionType == online.monarchlabs.sentinel.assistant.suggestions.AssistantSuggestion.ActionType.SET_TIMER) {
            String packageName = (String) suggestion.metadata.get("packageName");
            String appName = (String) suggestion.metadata.get("appName");
            Long limitMillis = null;
            Object limitObj = suggestion.metadata.get("limitMillis");
            if (limitObj instanceof Number) {
                limitMillis = ((Number) limitObj).longValue();
            }
            if (packageName != null && limitMillis != null) {
                setTimerFromSuggestion(packageName, appName != null ? appName : packageName, limitMillis);
            }
        }
    }

    private void setTimerFromSuggestion(String packageName, String appName, long limitMillis) {
        if (selectedChildId == null) {
            return;
        }

        List<online.monarchlabs.sentinel.assistant.planner.ActionPlan> actions = new ArrayList<>();
        online.monarchlabs.sentinel.assistant.planner.ActionPlan action = new online.monarchlabs.sentinel.assistant.planner.ActionPlan(
                online.monarchlabs.sentinel.assistant.core.AssistantActionType.SET_APP_TIMER);
        action.addTarget(packageName);
        actions.add(action);

        online.monarchlabs.sentinel.assistant.parser.ExtractedSlots slots = new online.monarchlabs.sentinel.assistant.parser.ExtractedSlots();
        slots.addAppTarget(packageName);
        slots.setDurationMillis(limitMillis);

        String summary = "Set " + appName + " limit to " + formatDuration(limitMillis);

        AssistantPlan plan = new AssistantPlan(
                online.monarchlabs.sentinel.assistant.core.AssistantIntent.SET_APP_TIMER,
                sessionManager.getParentUserId(),
                selectedChildId,
                selectedChildName,
                actions,
                slots,
                new ArrayList<>(),
                online.monarchlabs.sentinel.assistant.core.RiskLevel.LOW,
                summary,
                false);

        SentinelCommand command = commandFactory.create(plan, CommandSource.PARENT_TEXT,
                latestLiveState != null ? latestLiveState.appNameToPackage : null);

        long now = System.currentTimeMillis();
        AssistantActivityHistoryStore.HistoryEntry historyEntry =
                AssistantActivityHistoryStore.HistoryEntry.pending(
                        command.getCommandId(),
                        command.getChildId(),
                        "Set limit via smart suggestion",
                        plan.getSummary(),
                        now);
        historyStore.upsert(historyEntry);
        refreshClearHistoryVisibility();

        conversationMessages.add(ChatMessage.historyEvent(command.getCommandId()));
        attachPendingHistoryListener(historyEntry);
        runOnUiThread(this::rebuildAssistantFeed);

        commandExecutor.enqueue(command, new AssistantCommandExecutor.ExecutionCallback() {
            @Override
            public void onQueued(SentinelCommand queuedCommand) {
                runOnUiThread(() -> updateHistoryEntry(
                        queuedCommand.getCommandId(),
                        "Pending",
                        "Applying on child's device...",
                        "queued"));
            }

            @Override
            public void onAck(java.util.Map<String, Object> ack) {
                String status = String.valueOf(ack.get("status"));
                String message = ack.get("message") != null
                        ? String.valueOf(ack.get("message"))
                        : "Child device responded.";
                runOnUiThread(() -> {
                    if ("APPLIED".equalsIgnoreCase(status)
                            || "PARTIALLY_APPLIED".equalsIgnoreCase(status)) {
                        updateHistoryEntry(command.getCommandId(), "Success", message, status);
                    } else {
                        updateHistoryEntry(command.getCommandId(), "Failed", message, status);
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> updateHistoryEntry(
                        command.getCommandId(),
                        "Failed",
                        message,
                        "failed"));
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
