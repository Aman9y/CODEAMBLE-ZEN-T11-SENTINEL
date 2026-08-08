package online.monarchlabs.sentinel.models;

import com.google.firebase.database.Exclude;
import com.google.firebase.database.IgnoreExtraProperties;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Firebase-serializable model for daily usage data.
 * Adapted from SUSAGE DailyUsage - stores data per day for 7-day rolling
 * window.
 */
@IgnoreExtraProperties
public class SUsageDailyData {
    private String dateKey; // yyyy-MM-dd format
    private long totalScreenTimeMillis;
    private long communicationTimeMillis;
    private long entertainmentTimeMillis;
    private long gamesTimeMillis;
    private long otherTimeMillis;
    private Map<String, SUsageAppInfo> apps; // packageName -> app info
    private long lastUpdated;

    // Required empty constructor for Firebase
    public SUsageDailyData() {
        this.dateKey = "";
        this.totalScreenTimeMillis = 0;
        this.communicationTimeMillis = 0;
        this.entertainmentTimeMillis = 0;
        this.gamesTimeMillis = 0;
        this.otherTimeMillis = 0;
        this.apps = new HashMap<>();
        this.lastUpdated = System.currentTimeMillis();
    }

    public SUsageDailyData(Calendar date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        this.dateKey = sdf.format(date.getTime());
        this.totalScreenTimeMillis = 0;
        this.communicationTimeMillis = 0;
        this.entertainmentTimeMillis = 0;
        this.gamesTimeMillis = 0;
        this.otherTimeMillis = 0;
        this.apps = new HashMap<>();
        this.lastUpdated = System.currentTimeMillis();
    }

    // Getters and Setters
    public String getDateKey() {
        return dateKey;
    }

    public void setDateKey(String dateKey) {
        this.dateKey = dateKey;
    }

    public long getTotalScreenTimeMillis() {
        return totalScreenTimeMillis;
    }

    public void setTotalScreenTimeMillis(long totalScreenTimeMillis) {
        this.totalScreenTimeMillis = totalScreenTimeMillis;
    }

    public long getCommunicationTimeMillis() {
        return communicationTimeMillis;
    }

    public void setCommunicationTimeMillis(long communicationTimeMillis) {
        this.communicationTimeMillis = communicationTimeMillis;
    }

    public long getEntertainmentTimeMillis() {
        return entertainmentTimeMillis;
    }

    public void setEntertainmentTimeMillis(long entertainmentTimeMillis) {
        this.entertainmentTimeMillis = entertainmentTimeMillis;
    }

    public long getGamesTimeMillis() {
        return gamesTimeMillis;
    }

    public void setGamesTimeMillis(long gamesTimeMillis) {
        this.gamesTimeMillis = gamesTimeMillis;
    }

    public long getOtherTimeMillis() {
        return otherTimeMillis;
    }

    public void setOtherTimeMillis(long otherTimeMillis) {
        this.otherTimeMillis = otherTimeMillis;
    }

    public Map<String, SUsageAppInfo> getApps() {
        return apps;
    }

    public void setApps(Map<String, SUsageAppInfo> apps) {
        this.apps = apps != null ? apps : new HashMap<>();
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    /**
     * Get apps as a list (for RecyclerView)
     */
    @Exclude
    public List<SUsageAppInfo> getAppList() {
        return new ArrayList<>(apps.values());
    }

    /**
     * Add an app's usage data
     */
    @Exclude
    public void addAppUsage(SUsageAppInfo appInfo) {
        if (appInfo != null && appInfo.getPackageName() != null) {
            apps.put(appInfo.getPackageName(), appInfo);
        }
    }

    /**
     * Returns formatted total screen time (e.g., "1 hrs 15 min")
     */
    @Exclude
    public String getFormattedTotalTime() {
        long totalMinutes = totalScreenTimeMillis / (1000 * 60);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0) {
            return hours + " hrs " + minutes + " min";
        }
        return minutes + " min";
    }

    /**
     * Returns short formatted time (e.g., "1h 30m")
     */
    @Exclude
    public String getShortFormattedTime() {
        long totalMinutes = totalScreenTimeMillis / (1000 * 60);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    /**
     * Returns short day name (e.g., "Sun", "Mon")
     */
    @Exclude
    public String getShortDayName() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar cal = Calendar.getInstance();
            cal.setTime(sdf.parse(dateKey));
            String[] days = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
            return days[cal.get(Calendar.DAY_OF_WEEK) - 1];
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Returns formatted date (e.g., "Today, 18 Oct")
     */
    @Exclude
    public String getFormattedDate() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar date = Calendar.getInstance();
            date.setTime(sdf.parse(dateKey));

            Calendar today = Calendar.getInstance();
            if (isSameDay(date, today)) {
                return "Today, " + getMonthDay(date);
            }

            Calendar yesterday = Calendar.getInstance();
            yesterday.add(Calendar.DAY_OF_YEAR, -1);
            if (isSameDay(date, yesterday)) {
                return "Yesterday, " + getMonthDay(date);
            }

            return getShortDayName() + ", " + getMonthDay(date);
        } catch (Exception e) {
            return dateKey;
        }
    }

    @Exclude
    private String getMonthDay(Calendar date) {
        String[] months = { "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
        return date.get(Calendar.DAY_OF_MONTH) + " " + months[date.get(Calendar.MONTH)];
    }

    @Exclude
    private boolean isSameDay(Calendar cal1, Calendar cal2) {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * Returns screen time in hours for chart display
     */
    @Exclude
    public float getScreenTimeHours() {
        return totalScreenTimeMillis / (1000f * 60f * 60f);
    }

    /**
     * Returns usage percentage against a full 24-hour day.
     */
    @Exclude
    public int getUsagePercentage() {
        long targetMillis = 24L * 60L * 60L * 1000L;
        int percentage = Math.round((totalScreenTimeMillis * 100f) / targetMillis);
        return Math.min(Math.max(percentage, 0), 100);
    }

    /**
     * Get app count
     */
    @Exclude
    public int getAppCount() {
        return apps != null ? apps.size() : 0;
    }
}
