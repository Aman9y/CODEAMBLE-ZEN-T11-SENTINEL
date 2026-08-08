package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

/** Handles the optional battery-optimization exemption used by monitoring services. */
public class BatteryOptimizationManager {
    private static final String TAG = "BatteryOptimizationManager";
    private static final int REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 1001;

    private final Context context;

    public BatteryOptimizationManager(Context context) {
        this.context = context;
    }

    public boolean isBatteryOptimizationDisabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean ignored = powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
        Log.d(TAG, "Battery optimization ignored: " + ignored);
        return ignored;
    }

    public void requestBatteryOptimizationExemption(AppCompatActivity activity) {
        if (!isBatteryOptimizationDisabled()) {
            openBatteryOptimizationSettings(activity);
        }
    }

    public void checkAndRequestAllPermissions(AppCompatActivity activity) {
        if (!isBatteryOptimizationDisabled()) {
            requestBatteryOptimizationExemption(activity);
        } else {
            Log.d(TAG, "Battery optimization exemption already granted");
        }
    }

    private void openBatteryOptimizationSettings(AppCompatActivity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            activity.startActivityForResult(intent, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            Log.d(TAG, "Opened battery optimization settings");
        } catch (Exception error) {
            Log.e(TAG, "Failed to open battery optimization settings", error);
            openGeneralBatterySettings(activity);
        }
    }

    private void openGeneralBatterySettings(AppCompatActivity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception error) {
            Log.e(TAG, "Failed to open general battery settings", error);
            openAppSettings(activity);
        }
    }

    private void openAppSettings(AppCompatActivity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception error) {
            Log.e(TAG, "Failed to open app settings", error);
        }
    }

    public String getPermissionStatus() {
        return "=== Timer Permissions Status ===\n"
                + "Battery Optimization Disabled: " + isBatteryOptimizationDisabled() + "\n"
                + "Android Version: " + Build.VERSION.SDK_INT + "\n"
                + "Device Manufacturer: " + Build.MANUFACTURER + "\n"
                + "Device Model: " + Build.MODEL + "\n";
    }

    public void logPermissionStatus() {
        Log.d(TAG, getPermissionStatus());
    }
}
