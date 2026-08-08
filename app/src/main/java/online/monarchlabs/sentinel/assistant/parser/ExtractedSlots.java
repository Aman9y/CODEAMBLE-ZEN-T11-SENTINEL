package online.monarchlabs.sentinel.assistant.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ExtractedSlots {
    private String appName;
    private final List<String> appTargets = new ArrayList<>();
    private String categoryName;
    private String childName;
    private Long durationMillis;
    private TimeRange timeRange;
    private String repeatRule;
    private final List<String> exceptions = new ArrayList<>();
    private String modeName;
    private String routineName;
    private boolean ambiguousTime;

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    /**
     * All distinct apps targeted by the command, in order of appearance. The
     * first entry mirrors {@link #appName} for backward compatibility.
     */
    public List<String> getAppTargets() {
        if (appTargets.isEmpty() && appName != null) {
            return Collections.singletonList(appName);
        }
        return Collections.unmodifiableList(appTargets);
    }

    /** Add a resolved app target, also seeding {@link #appName} on first use. */
    public void addAppTarget(String app) {
        if (app == null || app.trim().isEmpty()) {
            return;
        }
        String name = app.trim();
        if (appName == null) {
            appName = name;
        }
        if (!appTargets.contains(name)) {
            appTargets.add(name);
        }
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public String getChildName() {
        return childName;
    }

    public void setChildName(String childName) {
        this.childName = childName;
    }

    public Long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(Long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public TimeRange getTimeRange() {
        return timeRange;
    }

    public void setTimeRange(TimeRange timeRange) {
        this.timeRange = timeRange;
    }

    public String getRepeatRule() {
        return repeatRule;
    }

    public void setRepeatRule(String repeatRule) {
        this.repeatRule = repeatRule;
    }

    public void addException(String exception) {
        if (exception != null && !exception.trim().isEmpty()) {
            exceptions.add(exception.trim());
        }
    }

    public List<String> getExceptions() {
        return Collections.unmodifiableList(exceptions);
    }

    public String getModeName() {
        return modeName;
    }

    public void setModeName(String modeName) {
        this.modeName = modeName;
    }

    public String getRoutineName() {
        return routineName;
    }

    public void setRoutineName(String routineName) {
        this.routineName = routineName;
    }

    public boolean isAmbiguousTime() {
        return ambiguousTime;
    }

    public void setAmbiguousTime(boolean ambiguousTime) {
        this.ambiguousTime = ambiguousTime;
    }
}
