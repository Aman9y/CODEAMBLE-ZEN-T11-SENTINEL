package online.monarchlabs.sentinel;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

/**
 * 🔧 OEM COMPATIBILITY MANAGER
 * 
 * Handles OEM-specific quirks for:
 * - MIUI (Xiaomi) - Very aggressive background killer
 * - OPPO/ColorOS - Very aggressive background killer  
 * - Vivo/FuntouchOS - Aggressive background killer
 * - Samsung/OneUI - Moderate background killer
 * - Stock Android - Generally safe
 * 
 * Provides utilities for:
 * ✅ Detecting OEM type
 * ✅ Requesting battery optimization exemption
 * ✅ Opening OEM-specific auto-start settings
 * ✅ Service restart strategies
 * ✅ Wake lock management
 */
public class OEMCompatibilityManager {
    private static final String TAG = "OEMCompatibility";
    private static final String PREFS_NAME = "oem_compat_prefs";
    
    // OEM Types
    public enum OEMType {
        XIAOMI,     // MIUI - Most aggressive
        OPPO,       // ColorOS - Very aggressive
        VIVO,       // FuntouchOS - Aggressive
        HUAWEI,     // EMUI - Aggressive
        SAMSUNG,    // OneUI - Moderate
        ONEPLUS,    // OxygenOS - Moderate
        REALME,     // Realme UI (based on ColorOS) - Aggressive
        STOCK       // Stock Android - Safe
    }
    
    private final Context context;
    private final SharedPreferences prefs;
    private final OEMType oemType;
    
    public OEMCompatibilityManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.oemType = detectOEM();
        
        Log.d(TAG, "🔧 OEM Compatibility Manager initialized");
        Log.d(TAG, "📱 Detected OEM: " + oemType.name());
        Log.d(TAG, "📱 Manufacturer: " + Build.MANUFACTURER);
        Log.d(TAG, "📱 Model: " + Build.MODEL);
        Log.d(TAG, "📱 Android Version: " + Build.VERSION.SDK_INT);
    }
    
    /**
     * 🔍 Detect the device OEM
     */
    public static OEMType detectOEM() {
        String manufacturer = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        String brand = Build.BRAND.toLowerCase(Locale.ROOT);
        
        if (manufacturer.contains("xiaomi") || brand.contains("xiaomi") || 
            manufacturer.contains("redmi") || brand.contains("redmi") ||
            manufacturer.contains("poco") || brand.contains("poco")) {
            return OEMType.XIAOMI;
        } else if (manufacturer.contains("oppo") || brand.contains("oppo")) {
            return OEMType.OPPO;
        } else if (manufacturer.contains("vivo") || brand.contains("vivo")) {
            return OEMType.VIVO;
        } else if (manufacturer.contains("huawei") || brand.contains("huawei") ||
                   manufacturer.contains("honor") || brand.contains("honor")) {
            return OEMType.HUAWEI;
        } else if (manufacturer.contains("samsung") || brand.contains("samsung")) {
            return OEMType.SAMSUNG;
        } else if (manufacturer.contains("oneplus") || brand.contains("oneplus")) {
            return OEMType.ONEPLUS;
        } else if (manufacturer.contains("realme") || brand.contains("realme")) {
            return OEMType.REALME;
        }
        
        return OEMType.STOCK;
    }
    
    /**
     * 🎯 Get current OEM type
     */
    public OEMType getOEMType() {
        return oemType;
    }
    
    /**
     * ⚠️ Check if this OEM is known to be aggressive with background services
     */
    public boolean isAggressiveOEM() {
        switch (oemType) {
            case XIAOMI:
            case OPPO:
            case VIVO:
            case REALME:
            case HUAWEI:
                return true;
            default:
                return false;
        }
    }
    
    /**
     * 🔋 Check if battery optimization is already disabled for our app
     */
    public boolean isBatteryOptimizationDisabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                return pm.isIgnoringBatteryOptimizations(context.getPackageName());
            }
        }
        return false;
    }
    
    /**
     * 🔋 Request battery optimization exemption
     */
    public void requestBatteryOptimizationExemption() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.d(TAG, "🔋 Opened battery optimization settings");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to open battery settings: " + e.getMessage());
            // Fallback: open general battery settings
            openBatterySettings();
        }
    }
    
    /**
     * 🔋 Open general battery settings
     */
    public void openBatterySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to open battery saver settings: " + e.getMessage());
        }
    }
    
    /**
     * 🚀 Open OEM-specific auto-start settings
     */
    public boolean openAutoStartSettings() {
        try {
            Intent intent = getAutoStartIntent();
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.d(TAG, "🚀 Opened auto-start settings for " + oemType.name());
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to open auto-start settings: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * 🔧 Get OEM-specific auto-start intent
     */
    private Intent getAutoStartIntent() {
        Intent intent = null;
        String pkg = context.getPackageName();
        
        switch (oemType) {
            case XIAOMI:
                // MIUI Auto-start
                intent = new Intent();
                intent.setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                if (!isIntentAvailable(intent)) {
                    // Alternative MIUI path
                    intent.setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.powercenter.PowerSettings"));
                }
                break;
                
            case OPPO:
                // ColorOS Auto-start
                intent = new Intent();
                intent.setComponent(new ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
                if (!isIntentAvailable(intent)) {
                    intent.setComponent(new ComponentName("com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity"));
                }
                break;
                
            case VIVO:
                // FuntouchOS Auto-start
                intent = new Intent();
                intent.setComponent(new ComponentName("com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"));
                if (!isIntentAvailable(intent)) {
                    intent.setComponent(new ComponentName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
                }
                break;
                
            case HUAWEI:
                // EMUI Startup Manager
                intent = new Intent();
                intent.setComponent(new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
                if (!isIntentAvailable(intent)) {
                    intent.setComponent(new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"));
                }
                break;
                
            case SAMSUNG:
                // Samsung Sleeping Apps
                intent = new Intent();
                intent.setComponent(new ComponentName("com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"));
                if (!isIntentAvailable(intent)) {
                    intent.setComponent(new ComponentName("com.samsung.android.sm",
                        "com.samsung.android.sm.battery.ui.BatteryActivity"));
                }
                break;
                
            case ONEPLUS:
                // OxygenOS App Auto-launch
                intent = new Intent();
                intent.setComponent(new ComponentName("com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"));
                break;
                
            case REALME:
                // Realme UI (similar to ColorOS)
                intent = new Intent();
                intent.setComponent(new ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
                break;
                
            default:
                // Stock Android - open app info
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + pkg));
                break;
        }
        
        return intent;
    }
    
    /**
     * 🔍 Check if intent is available
     */
    private boolean isIntentAvailable(Intent intent) {
        try {
            return context.getPackageManager()
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .size() > 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 📱 Get user-friendly instructions for current OEM
     */
    public String getAutoStartInstructions() {
        switch (oemType) {
            case XIAOMI:
                return "MIUI Settings:\n" +
                       "1. Go to Settings > Apps > Manage apps\n" +
                       "2. Find this app and tap on it\n" +
                       "3. Enable 'Autostart'\n" +
                       "4. Under Battery Saver, select 'No restrictions'\n" +
                       "5. Lock the app in Recent Apps by pulling down on it";
                       
            case OPPO:
                return "ColorOS Settings:\n" +
                       "1. Go to Settings > Battery > App Power Saver\n" +
                       "2. Find this app and select 'Allow background activity'\n" +
                       "3. Go to Settings > App Management > App List\n" +
                       "4. Find this app > Enable 'Allow Auto Startup'";
                       
            case VIVO:
                return "Vivo Settings:\n" +
                       "1. Go to Settings > Battery > High background power consumption\n" +
                       "2. Find this app and enable it\n" +
                       "3. Go to Settings > More Settings > Applications > Autostart\n" +
                       "4. Enable autostart for this app";
                       
            case HUAWEI:
                return "EMUI Settings:\n" +
                       "1. Go to Settings > Apps > Apps\n" +
                       "2. Find this app > Battery > Enable 'Launch manually'\n" +
                       "3. Go to Settings > Battery > App Launch\n" +
                       "4. Find this app > Enable 'Manage Manually' and enable all options";
                       
            case SAMSUNG:
                return "Samsung Settings:\n" +
                       "1. Go to Settings > Device Care > Battery\n" +
                       "2. Tap 'App power management' or 'Background usage limits'\n" +
                       "3. Tap 'Sleeping apps' and remove this app from the list\n" +
                       "4. Add this app to 'Never sleeping apps'";
                       
            case ONEPLUS:
                return "OnePlus Settings:\n" +
                       "1. Go to Settings > Apps > Special app access\n" +
                       "2. Find 'Battery optimization' and disable for this app\n" +
                       "3. Long press the app icon > App info > Battery > Don't optimize";
                       
            case REALME:
                return "Realme Settings:\n" +
                       "1. Go to Settings > Battery > More battery settings\n" +
                       "2. Find 'Optimize battery usage' and disable for this app\n" +
                       "3. Go to Settings > App Management > Auto-startup\n" +
                       "4. Enable auto-startup for this app";
                       
            default:
                return "Android Settings:\n" +
                       "1. Go to Settings > Apps\n" +
                       "2. Find this app > Battery\n" +
                       "3. Select 'Unrestricted' or 'Don't optimize'\n" +
                       "4. Make sure background activity is enabled";
        }
    }
    
    /**
     * 🔄 Get recommended restart interval based on OEM
     */
    public long getRecommendedRestartInterval() {
        switch (oemType) {
            case XIAOMI:
            case OPPO:
            case REALME:
                return 60 * 1000L; // 1 minute - most aggressive
            case VIVO:
            case HUAWEI:
                return 2 * 60 * 1000L; // 2 minutes - aggressive
            case SAMSUNG:
            case ONEPLUS:
                return 5 * 60 * 1000L; // 5 minutes - moderate
            default:
                return 10 * 60 * 1000L; // 10 minutes - stock Android
        }
    }
    
    /**
     * ✅ Check if app needs special handling prompt
     */
    public boolean needsSpecialHandlingPrompt() {
        // Only prompt once per day for aggressive OEMs
        if (!isAggressiveOEM()) {
            return false;
        }
        
        long lastPrompt = prefs.getLong("last_oem_prompt", 0);
        long dayMs = 24 * 60 * 60 * 1000L;
        
        return (System.currentTimeMillis() - lastPrompt) > dayMs;
    }
    
    /**
     * ✅ Mark that we've shown the special handling prompt
     */
    public void markPromptShown() {
        prefs.edit().putLong("last_oem_prompt", System.currentTimeMillis()).apply();
    }
    
    /**
     * 🔧 Log detailed OEM info for debugging
     */
    public void logOEMInfo() {
        Log.d(TAG, "═══════════════════════════════════════");
        Log.d(TAG, "📱 OEM COMPATIBILITY INFO");
        Log.d(TAG, "═══════════════════════════════════════");
        Log.d(TAG, "OEM Type: " + oemType.name());
        Log.d(TAG, "Manufacturer: " + Build.MANUFACTURER);
        Log.d(TAG, "Brand: " + Build.BRAND);
        Log.d(TAG, "Model: " + Build.MODEL);
        Log.d(TAG, "Product: " + Build.PRODUCT);
        Log.d(TAG, "Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        Log.d(TAG, "Aggressive OEM: " + isAggressiveOEM());
        Log.d(TAG, "Battery Opt Disabled: " + isBatteryOptimizationDisabled());
        Log.d(TAG, "Restart Interval: " + (getRecommendedRestartInterval() / 1000) + "s");
        Log.d(TAG, "═══════════════════════════════════════");
    }
}

