package online.monarchlabs.sentinel;

import online.monarchlabs.sentinel.models.AppUsage;

import java.util.List;

public class UsageSnapshot {
    public String deviceId; // Device identifier for proper data isolation
    public long timestamp;
    public List<Float> bars; // most recent chart values
    public List<AppUsage> apps; // legacy: today's app list (kept for backward compatibility)
    public String totalText; // legacy: today's formatted total time
    
    // New: per-day data aligned with bars list (index 0 → oldest)
    public List<List<AppUsage>> dailyApps;  // each entry is a day's app list
    public List<String> totalTexts;      // formatted totals per day
    public Long totalScreenTimeMillis; // canonical numeric total for the latest day (optional)
    
    // Default constructor for Firebase
    public UsageSnapshot() {
    }
    
    // Legacy constructor (today only)
    public UsageSnapshot(long ts, List<Float> bars, List<AppUsage> apps, String total) {
        this.timestamp = ts;
        this.bars = bars;
        this.apps = apps;
        this.totalText = total;
    }
    
    // New constructor with multi-day data
    public UsageSnapshot(long ts, List<Float> bars, List<List<AppUsage>> dailyApps, List<String> totalTexts) {
        this.timestamp = ts;
        this.bars = bars;
        this.dailyApps = dailyApps;
        this.totalTexts = totalTexts;
        
        // Populate legacy fields with today (latest index) for backward compatibility
        if (dailyApps != null && !dailyApps.isEmpty()) {
            this.apps = dailyApps.get(dailyApps.size() - 1);
        }
        if (totalTexts != null && !totalTexts.isEmpty()) {
            this.totalText = totalTexts.get(totalTexts.size() - 1);
        }
    }
} 