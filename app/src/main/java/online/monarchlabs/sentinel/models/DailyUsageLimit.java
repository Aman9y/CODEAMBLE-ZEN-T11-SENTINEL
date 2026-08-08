package online.monarchlabs.sentinel.models;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

public class DailyUsageLimit {
    public String deviceId;
    public String deviceName;
    public long lastResetDate; // Last date when limits were reset (midnight)
    
    // Daily limits for individual apps (packageName -> limit in milliseconds)
    public Map<String, Long> appLimits;
    
    // Daily limits for categories (category -> limit in milliseconds)
    public Map<String, Long> categoryLimits;
    
    // Daily total screen time limit (in milliseconds)
    public long totalScreenTimeLimit;
    
    // 7-day rolling window usage data (date -> usage data)
    public Map<String, DayUsageData> sevenDayUsage;
    
    // Current day's usage tracking (for quick access)
    public Map<String, Long> currentAppUsage; // packageName -> current usage
    public Map<String, Long> currentCategoryUsage; // category -> current usage
    public long currentTotalUsage;
    
    // Settings
    public boolean autoBlockWhenLimitReached;
    public boolean showWarnings;
    public boolean allowOverrideRequests;
    
    // Inner class to store usage data for a specific day
    public static class DayUsageData {
        public String date; // Format: "yyyy-MM-dd"
        public Map<String, Long> appUsage;
        public Map<String, Long> categoryUsage;
        public long totalUsage;
        
        public DayUsageData() {
            this.appUsage = new HashMap<>();
            this.categoryUsage = new HashMap<>();
            this.totalUsage = 0;
        }
        
        public DayUsageData(String date) {
            this.date = date;
            this.appUsage = new HashMap<>();
            this.categoryUsage = new HashMap<>();
            this.totalUsage = 0;
        }
    }
    
    // Default constructor for Firebase
    public DailyUsageLimit() {
        this.appLimits = new HashMap<>();
        this.categoryLimits = new HashMap<>();
        this.currentAppUsage = new HashMap<>();
        this.currentCategoryUsage = new HashMap<>();
        this.sevenDayUsage = new HashMap<>();
        this.autoBlockWhenLimitReached = true;
        this.showWarnings = true;
        this.allowOverrideRequests = true;
        this.lastResetDate = getCurrentDayStart();
        
        // Initialize current day in 7-day rolling window
        initializeCurrentDay();
    }
    
    public DailyUsageLimit(String deviceId, String deviceName) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.lastResetDate = getCurrentDayStart();
        this.appLimits = new HashMap<>();
        this.categoryLimits = new HashMap<>();
        this.currentAppUsage = new HashMap<>();
        this.currentCategoryUsage = new HashMap<>();
        this.sevenDayUsage = new HashMap<>();
        this.totalScreenTimeLimit = 0; // No limit by default
        this.autoBlockWhenLimitReached = true;
        this.showWarnings = true;
        this.allowOverrideRequests = true;
        
        // Initialize current day in 7-day rolling window
        initializeCurrentDay();
    }
    
    // Helper method to get current day start (midnight)
    private long getCurrentDayStart() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }
    
    // Helper method to get date string from timestamp
    private String getDateString(long timestamp) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        return String.format("%04d-%02d-%02d", 
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH));
    }
    
    // Initialize current day in 7-day rolling window
    private void initializeCurrentDay() {
        String today = getDateString(getCurrentDayStart());
        if (!sevenDayUsage.containsKey(today)) {
            sevenDayUsage.put(today, new DayUsageData(today));
            maintainSevenDayWindow();
        }
    }
    
    // Maintain only 7 days of data, remove older entries
    private void maintainSevenDayWindow() {
        if (sevenDayUsage.size() <= 7) return;
        
        // Find the oldest entries and remove them
        List<String> dates = new ArrayList<>(sevenDayUsage.keySet());
        dates.sort(String::compareTo); // Sort chronologically
        
        // Keep only the latest 7 days
        while (dates.size() > 7) {
            String oldestDate = dates.remove(0);
            sevenDayUsage.remove(oldestDate);
        }
    }
    
    // Check if limits need to be reset (new day)
    public boolean needsReset() {
        long currentDayStart = getCurrentDayStart();
        return lastResetDate < currentDayStart;
    }
    
    // Reset all usage counters for new day and update 7-day rolling window
    public void resetDailyUsage() {
        // Save current day's data to 7-day rolling window before resetting
        String previousDay = getDateString(lastResetDate);
        if (sevenDayUsage.containsKey(previousDay)) {
            DayUsageData prevDayData = sevenDayUsage.get(previousDay);
            prevDayData.appUsage.putAll(currentAppUsage);
            prevDayData.categoryUsage.putAll(currentCategoryUsage);
            prevDayData.totalUsage = currentTotalUsage;
        }
        
        // Clear current counters
        currentAppUsage.clear();
        currentCategoryUsage.clear();
        currentTotalUsage = 0;
        lastResetDate = getCurrentDayStart();
        
        // Initialize new day in 7-day rolling window
        initializeCurrentDay();
        
        // Maintain 7-day window (remove data older than 7 days)
        maintainSevenDayWindow();
    }
    
    // Add usage time for an app and update 7-day rolling window
    public void addAppUsage(String packageName, long usageTime) {
        // Update current day counters
        long currentUsage = currentAppUsage.getOrDefault(packageName, 0L);
        currentAppUsage.put(packageName, currentUsage + usageTime);
        currentTotalUsage += usageTime;
        
        // Update 7-day rolling window for current day
        String today = getDateString(getCurrentDayStart());
        DayUsageData todayData = sevenDayUsage.get(today);
        if (todayData != null) {
            long todayAppUsage = todayData.appUsage.getOrDefault(packageName, 0L);
            todayData.appUsage.put(packageName, todayAppUsage + usageTime);
            todayData.totalUsage += usageTime;
        }
    }
    
    // Add usage time for a category and update 7-day rolling window
    public void addCategoryUsage(String category, long usageTime) {
        // Update current day counters
        long currentUsage = currentCategoryUsage.getOrDefault(category, 0L);
        currentCategoryUsage.put(category, currentUsage + usageTime);
        
        // Update 7-day rolling window for current day
        String today = getDateString(getCurrentDayStart());
        DayUsageData todayData = sevenDayUsage.get(today);
        if (todayData != null) {
            long todayCategoryUsage = todayData.categoryUsage.getOrDefault(category, 0L);
            todayData.categoryUsage.put(category, todayCategoryUsage + usageTime);
        }
    }
    
    // Check if app has reached its daily limit
    public boolean isAppLimitReached(String packageName) {
        Long limit = appLimits.get(packageName);
        if (limit == null || limit <= 0) return false; // No limit set
        
        Long currentUsage = currentAppUsage.get(packageName);
        if (currentUsage == null) return false;
        
        return currentUsage >= limit;
    }
    
    // Check if category has reached its daily limit
    public boolean isCategoryLimitReached(String category) {
        Long limit = categoryLimits.get(category);
        if (limit == null || limit <= 0) return false; // No limit set
        
        Long currentUsage = currentCategoryUsage.get(category);
        if (currentUsage == null) return false;
        
        return currentUsage >= limit;
    }
    
    // Check if total screen time limit is reached
    public boolean isTotalLimitReached() {
        if (totalScreenTimeLimit <= 0) return false; // No limit set
        return currentTotalUsage >= totalScreenTimeLimit;
    }
    
    // Get remaining time for an app
    public long getAppRemainingTime(String packageName) {
        Long limit = appLimits.get(packageName);
        if (limit == null || limit <= 0) return Long.MAX_VALUE; // No limit
        
        Long currentUsage = currentAppUsage.get(packageName);
        if (currentUsage == null) return limit;
        
        return Math.max(0, limit - currentUsage);
    }
    
    // Get remaining time for a category
    public long getCategoryRemainingTime(String category) {
        Long limit = categoryLimits.get(category);
        if (limit == null || limit <= 0) return Long.MAX_VALUE; // No limit
        
        Long currentUsage = currentCategoryUsage.get(category);
        if (currentUsage == null) return limit;
        
        return Math.max(0, limit - currentUsage);
    }
    
    // Get remaining total screen time
    public long getTotalRemainingTime() {
        if (totalScreenTimeLimit <= 0) return Long.MAX_VALUE; // No limit
        return Math.max(0, totalScreenTimeLimit - currentTotalUsage);
    }
    
    // Format time in human readable format
    public String formatTime(long milliseconds) {
        long minutes = milliseconds / 60000;
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        
        if (hours > 0) {
            return hours + "h " + remainingMinutes + "m";
        } else {
            return remainingMinutes + "m";
        }
    }
    
    // Get 7-day usage data in chronological order (oldest to newest)
    public List<DayUsageData> getSevenDayUsageOrdered() {
        List<String> dates = new ArrayList<>(sevenDayUsage.keySet());
        dates.sort(String::compareTo); // Sort chronologically
        
        List<DayUsageData> orderedData = new ArrayList<>();
        for (String date : dates) {
            orderedData.add(sevenDayUsage.get(date));
        }
        return orderedData;
    }
    
    // Get usage for a specific date
    public DayUsageData getUsageForDate(String date) {
        return sevenDayUsage.get(date);
    }
    
    // Get today's usage data (should match current* fields)
    public DayUsageData getTodayUsageData() {
        String today = getDateString(getCurrentDayStart());
        return sevenDayUsage.get(today);
    }
    
    // Get yesterday's usage data
    public DayUsageData getYesterdayUsageData() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1);
        String yesterday = getDateString(cal.getTimeInMillis());
        return sevenDayUsage.get(yesterday);
    }
    
    // Sync current usage with today's 7-day data (in case of inconsistencies)
    public void syncCurrentUsageWithToday() {
        String today = getDateString(getCurrentDayStart());
        DayUsageData todayData = sevenDayUsage.get(today);
        
        if (todayData != null) {
            // Update current usage from 7-day data
            currentAppUsage.clear();
            currentAppUsage.putAll(todayData.appUsage);
            
            currentCategoryUsage.clear();
            currentCategoryUsage.putAll(todayData.categoryUsage);
            
            currentTotalUsage = todayData.totalUsage;
        }
    }
} 