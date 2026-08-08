package online.monarchlabs.sentinel.assistant.core;

public class AssistantPlanningRequest {
    private final String parentId;
    private final String selectedChildId;
    private final String selectedChildName;
    private final String inputText;
    private final CommandSource source;
    private final long createdAtMillis;

    public AssistantPlanningRequest(String parentId, String selectedChildId, String selectedChildName,
                                    String inputText, CommandSource source) {
        this.parentId = parentId;
        this.selectedChildId = selectedChildId;
        this.selectedChildName = selectedChildName;
        this.inputText = inputText;
        this.source = source == null ? CommandSource.PARENT_TEXT : source;
        this.createdAtMillis = System.currentTimeMillis();
    }

    public String getParentId() {
        return parentId;
    }

    public String getSelectedChildId() {
        return selectedChildId;
    }

    public String getSelectedChildName() {
        return selectedChildName;
    }

    public String getInputText() {
        return inputText;
    }

    public CommandSource getSource() {
        return source;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    private Long durationOverride;
    private Boolean confirmationOverride;
    private String appTargetOverride;

    public Long getDurationOverride() {
        return durationOverride;
    }

    public void setDurationOverride(Long durationOverride) {
        this.durationOverride = durationOverride;
    }

    public Boolean getConfirmationOverride() {
        return confirmationOverride;
    }

    public void setConfirmationOverride(Boolean confirmationOverride) {
        this.confirmationOverride = confirmationOverride;
    }

    public String getAppTargetOverride() {
        return appTargetOverride;
    }

    public void setAppTargetOverride(String appTargetOverride) {
        this.appTargetOverride = appTargetOverride;
    }
}
