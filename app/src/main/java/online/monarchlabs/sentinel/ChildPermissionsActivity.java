package online.monarchlabs.sentinel;

import androidx.appcompat.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import android.content.res.ColorStateList;

public class ChildPermissionsActivity extends BaseActivity {
    private static final String TAG = "ChildPermissions";

    // UI Components
    private LinearLayout accessibilitySection, usageAccessSection, notificationSection, batteryOptimizationSection,
            deviceAdminSection;
    private ImageView accessibilityIcon, usageAccessIcon, notificationIcon, batteryOptimizationIcon, deviceAdminIcon;
    private TextView accessibilityStatus, usageAccessStatus, notificationStatus, batteryOptimizationStatus,
            deviceAdminStatus;
    private Button btnAccessibility, btnUsageAccess, btnNotification, btnBatteryOptimization, btnDeviceAdmin;
    private Button btnProceed;

    // Battery Optimization Manager
    private BatteryOptimizationManager batteryOptimizationManager;

    // 🛡️ Device Admin Helper for uninstall protection
    private DeviceAdminHelper deviceAdminHelper;

    // Permission status
    private boolean hasAccessibilityPermission = false;
    private boolean hasUsageAccessPermission = false;
    private boolean hasNotificationPermission = false;
    private boolean hasBatteryOptimizationPermission = false;
    private boolean hasDeviceAdminPermission = false;

    // Request codes
    private static final int REQUEST_DEVICE_ADMIN = 2001;
    private final Handler permissionRefreshHandler = new Handler(Looper.getMainLooper());
    private boolean returningFromSystemSettings = false;
    private long lastSettingsLaunchTime = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ChildMonitoringDisclosureActivity.hasAcceptedDisclosure(this)) {
            startActivity(new Intent(this, ChildMonitoringDisclosureActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_child_permissions);
        getSharedPreferences("child_onboarding_state", MODE_PRIVATE)
                .edit()
                .putBoolean("permission_setup_active", true)
                .apply();

        Log.d(TAG, "ChildPermissionsActivity created");

        // Initialize Battery Optimization Manager
        batteryOptimizationManager = new BatteryOptimizationManager(this);

        // 🛡️ Initialize Device Admin Helper
        deviceAdminHelper = new DeviceAdminHelper(this);

        initializeViews();
        setupClickListeners();
    }

    private void initializeViews() {
        // Permission sections
        accessibilitySection = findViewById(R.id.accessibilitySection);
        usageAccessSection = findViewById(R.id.usageAccessSection);
        notificationSection = findViewById(R.id.notificationSection);
        batteryOptimizationSection = findViewById(R.id.batteryOptimizationSection);

        // Status icons
        accessibilityIcon = findViewById(R.id.accessibilityIcon);
        usageAccessIcon = findViewById(R.id.usageAccessIcon);
        notificationIcon = findViewById(R.id.notificationIcon);
        batteryOptimizationIcon = findViewById(R.id.batteryOptimizationIcon);

        // Status texts
        accessibilityStatus = findViewById(R.id.accessibilityStatus);
        usageAccessStatus = findViewById(R.id.usageAccessStatus);
        notificationStatus = findViewById(R.id.notificationStatus);
        batteryOptimizationStatus = findViewById(R.id.batteryOptimizationStatus);

        // Permission buttons
        btnAccessibility = findViewById(R.id.btnAccessibility);
        btnUsageAccess = findViewById(R.id.btnUsageAccess);
        btnNotification = findViewById(R.id.btnNotification);
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);

        // 🛡️ Device Admin section (uninstall protection)
        deviceAdminSection = findViewById(R.id.deviceAdminSection);
        deviceAdminIcon = findViewById(R.id.deviceAdminIcon);
        deviceAdminStatus = findViewById(R.id.deviceAdminStatus);
        btnDeviceAdmin = findViewById(R.id.btnDeviceAdmin);

        // Proceed button
        btnProceed = findViewById(R.id.btnProceed);

        Log.d(TAG, "Views initialized successfully");
    }

    private void setupClickListeners() {
        btnAccessibility.setOnClickListener(v -> requestAccessibilityPermission());
        btnUsageAccess.setOnClickListener(v -> showUsageAccessSteps());
        btnNotification.setOnClickListener(v -> showNotificationSteps());
        btnBatteryOptimization.setOnClickListener(v -> showBatteryOptimizationSteps());

        // 🛡️ Device Admin button
        if (btnDeviceAdmin != null) {
            btnDeviceAdmin.setOnClickListener(v -> showUninstallProtectionSteps());
        }

        btnProceed.setOnClickListener(v -> proceedToQRScanner());

        Log.d(TAG, "Click listeners setup complete");
    }

    @Override
    protected void onResume() {
        super.onResume();
        online.monarchlabs.sentinel.services.PermissionMonitorService
                .requestImmediateCheck(this);
        permissionRefreshHandler.removeCallbacksAndMessages(null);
        if (returningFromSystemSettings) {
            returningFromSystemSettings = false;
            permissionRefreshHandler.postDelayed(this::checkAllPermissions, 350L);
        } else {
            checkAllPermissions();
        }
    }

    private void checkAllPermissions() {
        Log.d(TAG, "Checking all permissions...");

        hasAccessibilityPermission = isAccessibilityServiceEnabled();
        hasUsageAccessPermission = hasUsageStatsPermission();
        hasNotificationPermission = hasNotificationPermission();
        hasBatteryOptimizationPermission = batteryOptimizationManager.isBatteryOptimizationDisabled();
        hasDeviceAdminPermission = deviceAdminHelper != null && deviceAdminHelper.isAdminActive();

        updateUI();
    }

    private void updateUI() {
        // Update Accessibility Permission with detailed explanation
        String accessibilityDescription = "• Observes page views and taps in app to detect restricted apps\n" +
                "• Observes other actions (window changes) to enforce blocks and screen-time limits\n" +
                "• Does not collect passwords, private messages, or unrelated screen content\n\n" +
                "How to enable: Tap Grant Permission > find Sentinel > turn ON the service > return here.";
        updatePermissionUI(
                accessibilityIcon, accessibilityStatus, btnAccessibility,
                hasAccessibilityPermission, "Accessibility Service", accessibilityDescription, false);

        // Update Usage Access Permission with detailed explanation
        String usageDescription = "• Reads app usage duration and foreground-app events\n" +
                "• Creates screen-time reports and timer status\n" +
                "• Sends usage summaries to the linked parent account\n\n" +
                "How to enable: Tap Grant Permission > find Sentinel in Usage Access > turn it ON > return here.";
        updatePermissionUI(
                usageAccessIcon, usageAccessStatus, btnUsageAccess,
                hasUsageAccessPermission, "Usage Access", usageDescription, false);

        // Update Notification Permission with detailed explanation
        String notificationDescription = "• Shows ongoing monitoring and timer status notices\n" +
                "• Alerts for monitoring and restriction status\n" +
                "• Does not read notifications from other apps\n\n" +
                "How to enable: Tap Grant Permission > choose Allow when Android asks.";
        updatePermissionUI(
                notificationIcon, notificationStatus, btnNotification,
                hasNotificationPermission, "Notifications", notificationDescription, false);

        // Update Battery Optimization Permission with detailed explanation
        String batteryDescription = "• Helps timer resets run at midnight\n" +
                "• Helps monitoring and optional location services stay reliable\n" +
                "• Can be changed later in Android battery settings\n\n" +
                "How to enable: Tap Grant Permission > allow Sentinel to run in the background.";
        updatePermissionUI(
                batteryOptimizationIcon, batteryOptimizationStatus, btnBatteryOptimization,
                hasBatteryOptimizationPermission, "Battery Optimization", batteryDescription, false);



        // 🛡️ Update Device Admin Permission (parent-controlled uninstall protection)
        String deviceAdminDescription = "• Adds Android's Device Admin verification step before uninstall\n" +
                "• Used only for Uninstall Protection status and verification\n" +
                "• Parent can request protection setup from the dashboard\n\n" +
                "How to enable: Tap Enable Protection > tap Activate on the Android screen > return here.";
        if (deviceAdminIcon != null && deviceAdminStatus != null && btnDeviceAdmin != null) {
            updatePermissionUI(
                    deviceAdminIcon, deviceAdminStatus, btnDeviceAdmin,
                    hasDeviceAdminPermission, "Uninstall Protection", deviceAdminDescription, false);
        }

        // Update Proceed button - ALL mandatory permissions required (Uninstall Protection/Device Admin is now REQUIRED)
        boolean allMandatoryGranted = hasAccessibilityPermission &&
                hasUsageAccessPermission &&
                hasNotificationPermission &&
                hasBatteryOptimizationPermission &&
                hasDeviceAdminPermission;

        btnProceed.setEnabled(allMandatoryGranted);
        btnProceed.setAlpha(allMandatoryGranted ? 1.0f : 0.5f);

        if (allMandatoryGranted) {
            btnProceed.setText("Continue");
            // Remove tint to show gradient
            btnProceed.setBackgroundTintList(null);
        } else {
            btnProceed.setText("Grant Permissions First");
            btnProceed.setBackgroundTintList(null);
        }

        Log.d(TAG, "UI updated - Mandatory permissions granted: " + allMandatoryGranted);
    }

    private void updatePermissionUI(ImageView icon, TextView status, Button button,
            boolean granted, String permissionName, String description, boolean isOptional) {
        if (granted) {
            // Keep original icon, just tint it Green
            icon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_dark));

            status.setText("✅ " + permissionName + " - Granted\n\n" + description);
            status.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));

            button.setText("Granted");
            button.setEnabled(false);
            button.setAlpha(0.6f);

            // Revert to outline/secondary style if possible, or just dim it
            button.setBackgroundResource(R.drawable.bg_surface_soft);
        } else {
            if (isOptional) {
                // Keep original icon, tint it Orange for Optional
                icon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_orange_dark));

                status.setText("⚠️ " + permissionName + " - Optional\n\n" + description);
                status.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));

                button.setText("Grant Permission");
                button.setEnabled(true);
                button.setAlpha(1.0f);
                button.setBackgroundResource(R.drawable.bg_primary_pill);
            } else {
                // Keep original icon, tint it Red for Required
                icon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_red_dark));

                status.setText("❌ " + permissionName + " - Required\n\n" + description);
                status.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));

                button.setText("Grant Permission");
                button.setEnabled(true);
                button.setAlpha(1.0f);
                button.setBackgroundResource(R.drawable.bg_primary_pill);
            }
        }

        // Improve text formatting for readability
        status.setTextSize(13f);
        status.setLineSpacing(4f, 1.2f);
    }

    // Permission checking methods
    private boolean isAccessibilityServiceEnabled() {
        String settingValue = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        if (settingValue != null) {
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(settingValue);
            while (splitter.hasNext()) {
                String service = splitter.next();
                if (service.equalsIgnoreCase(getPackageName() + "/" + BlockService.class.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow("android:get_usage_stats",
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true; // Pre-Android 13 doesn't need this permission
    }



    // Permission request methods
    private void requestAccessibilityPermission() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🛡️ Enable Accessibility Service")
                .setMessage("To enforce parent-selected app blocks and screen time limits, Sentinel requires the Accessibility Service permission.\n\n" +
                        "Data observed:\n" +
                        "• Page views and taps in app (to detect when restricted apps are opened and enforce blocks)\n" +
                        "• Other actions (window state and package changes to verify system settings status)\n\n" +
                        "Use and sharing:\n" +
                        "App package names and enforcement events are synchronized with the linked parent account to show usage statistics and apply limits. Sentinel DOES NOT collect or share passwords, payment details, private messages, personal details, or unrelated screen content.\n\n" +
                        "📋 Steps to enable:\n" +
                        "1. Tap 'Open Settings'\n" +
                        "2. Find 'Sentinel' under Installed Services\n" +
                        "3. Turn ON the service and tap 'Allow'\n" +
                        "4. Return to this app\n\n" +
                        "⚠️ This permission is required to prevent bypass and keep your child safe.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    openAccessibilitySettings();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestUsageAccessPermission() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📊 Grant Usage Access")
                .setMessage("Sentinel uses Usage Access to read which apps are used and for how long on this child device.\n\n" +
                        "Package names, app names, usage duration, and timestamps are used for screen-time reports, app timers, and parent-selected limits. This information may be uploaded to Sentinel services and shown to the linked parent account.\n\n" +
                        "📋 Steps to enable:\n" +
                        "1. Tap 'Open Settings'\n" +
                        "2. Find 'Sentinel' in the list\n" +
                        "3. Turn ON 'Permit usage access'\n" +
                        "4. Return to this app\n\n" +
                        "⚠️ This permission is REQUIRED for monitoring functionality.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    try {
                        openUsageAccessSettingsIntent();
                    } catch (Exception e) {
                        Log.e(TAG, "Error opening usage access settings: " + e.getMessage());
                        Toast.makeText(this, "Please grant usage access in Settings",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("🔔 Enable Notifications")
                        .setMessage("Sentinel uses notifications to show ongoing monitoring status, timer status, restriction changes, and important parental-control alerts.\n\n" +
                                "This permission lets Sentinel send its own notifications. It does not let Sentinel read notifications from other apps.\n\n" +
                                "⚠️ This permission is REQUIRED for the app to function properly.")
                        .setPositiveButton("Grant Permission", (dialog, which) -> {
                            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1001);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1001);
            }
        }
    }

    private void requestBatteryOptimizationPermission() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        android.view.LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_battery_optimization, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();

        // Make dialog background transparent to show rounded corners
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        // Setup Buttons
        TextView btnCancel = dialogView.findViewById(R.id.btnCancel);
        TextView btnSettings = dialogView.findViewById(R.id.btnSettings);

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSettings.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                batteryOptimizationManager.requestBatteryOptimizationExemption(this);
                Log.d(TAG, "Opened battery optimization settings");
            } catch (Exception e) {
                Log.e(TAG, "Error opening battery optimization settings: " + e.getMessage());
                Toast.makeText(this, "Please disable battery optimization in Settings",
                        Toast.LENGTH_LONG).show();
            }
        });

        dialog.show();
    }

    /**
     * 🛡️ Request Device Admin permission for uninstall protection
     */
    private void requestDeviceAdminPermission() {
        if (deviceAdminHelper == null) {
            deviceAdminHelper = new DeviceAdminHelper(this);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🛡️ Enable Parent-Controlled Uninstall Protection")
                .setMessage("Sentinel uses Android Device Admin for Uninstall Protection:\n\n" +
                        "• Android will require Device Admin to be deactivated before Sentinel can be uninstalled\n" +
                        "• The linked parent can turn Uninstall Protection on or off from the parent dashboard\n" +
                        "• Sentinel does not use Device Admin to wipe data, reset passwords, lock the device, or disable the camera\n\n" +
                        "📋 Steps to enable:\n" +
                        "1. Tap 'Enable Protection'\n" +
                        "2. On the next screen, tap 'Activate'\n" +
                        "3. Return to Sentinel\n\n" +
                        "⚠️ This permission is REQUIRED for the app to function properly.")
                .setPositiveButton("Enable Protection", (dialog, which) -> {
                    try {
                        deviceAdminHelper.requestAdminActivation(this);
                        Log.d(TAG, "Launched Device Admin activation screen");
                    } catch (Exception e) {
                        Log.e(TAG, "Error launching Device Admin: " + e.getMessage());
                        Toast.makeText(this, "Failed to open Device Admin settings", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }



    private void showUsageAccessSteps() {
        new AlertDialog.Builder(this)
                .setTitle("Enable Usage Access")
                .setMessage("Steps:\n" +
                        "1. Tap Open Settings.\n" +
                        "2. Find Sentinel in Usage Access.\n" +
                        "3. Turn ON usage access.\n" +
                        "4. Return to Sentinel.\n\n" +
                        "This is needed to calculate screen time and app usage.")
                .setPositiveButton("Open Settings", (dialog, which) -> openUsageAccessSettings())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showNotificationSteps() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            checkAllPermissions();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Enable Notifications")
                .setMessage("Steps:\n" +
                        "1. Tap Grant Permission.\n" +
                        "2. When Android asks, tap Allow.\n" +
                        "3. Return to Sentinel.\n\n" +
                        "Notifications are used for monitoring status, timer alerts, and restriction updates.")
                .setPositiveButton("Grant Permission", (dialog, which) -> requestNotificationPermission())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showBatteryOptimizationSteps() {
        new AlertDialog.Builder(this)
                .setTitle("Allow Background Protection")
                .setMessage("Steps:\n" +
                        "1. Tap Open Settings.\n" +
                        "2. Allow Sentinel to run in the background.\n" +
                        "3. Return to Sentinel.\n\n" +
                        "This helps app blocking, timers, and daily resets continue working reliably.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    try {
                        batteryOptimizationManager.requestBatteryOptimizationExemption(this);
                    } catch (Exception e) {
                        Log.e(TAG, "Error opening battery optimization settings: " + e.getMessage());
                        Toast.makeText(this, "Please allow Sentinel to run in the background from Settings",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showUninstallProtectionSteps() {
        if (deviceAdminHelper == null) {
            deviceAdminHelper = new DeviceAdminHelper(this);
        }

        new AlertDialog.Builder(this)
                .setTitle("Enable Uninstall Protection")
                .setMessage("Steps:\n" +
                        "1. Tap Enable Protection.\n" +
                        "2. On the next Android screen, tap Activate.\n" +
                        "3. Return to Sentinel.\n\n" +
                        "This adds Android's Device Admin verification step before Sentinel can be uninstalled.")
                .setPositiveButton("Enable Protection", (dialog, which) -> {
                    try {
                        deviceAdminHelper.requestAdminActivation(this);
                    } catch (Exception e) {
                        Log.e(TAG, "Error launching Device Admin: " + e.getMessage());
                        Toast.makeText(this, "Failed to open Uninstall Protection settings", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openAccessibilitySettings() {
        if (!canOpenSystemSettings()) return;
        try {
            returningFromSystemSettings = true;
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            returningFromSystemSettings = false;
            Log.e(TAG, "Error opening accessibility settings: " + e.getMessage());
            Toast.makeText(this, "Please enable Accessibility in Android Settings",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openUsageAccessSettings() {
        if (!canOpenSystemSettings()) return;
        try {
            returningFromSystemSettings = true;
            openUsageAccessSettingsIntent();
        } catch (Exception e) {
            returningFromSystemSettings = false;
            Log.e(TAG, "Error opening usage access settings: " + e.getMessage());
            Toast.makeText(this, "Please grant usage access in Settings",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openUsageAccessSettingsIntent() {
        Intent directIntent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        directIntent.setData(Uri.parse("package:" + getPackageName()));

        if (directIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(directIntent);
            return;
        }

        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
    }

    private boolean canOpenSystemSettings() {
        long now = System.currentTimeMillis();
        if (now - lastSettingsLaunchTime < 800L) {
            return false;
        }
        lastSettingsLaunchTime = now;
        return true;
    }

    @Override
    protected void onDestroy() {
        permissionRefreshHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_DEVICE_ADMIN) {
            if (resultCode == RESULT_OK) {
                Log.d(TAG, "Device Admin enabled successfully");
                Toast.makeText(this, "✅ Uninstall protection enabled!", Toast.LENGTH_SHORT).show();
            } else {
                Log.d(TAG, "Device Admin NOT enabled");
                Toast.makeText(this, "⚠️ Uninstall protection not enabled", Toast.LENGTH_SHORT).show();
            }
            checkAllPermissions(); // Refresh UI
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 1001) { // Notification permission
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Notification permission granted");
                Toast.makeText(this, "✅ Notification permission granted!", Toast.LENGTH_SHORT).show();
            } else {
                Log.d(TAG, "Notification permission denied");
                Toast.makeText(this, "❌ Notification permission is required", Toast.LENGTH_SHORT).show();
            }
            checkAllPermissions(); // Refresh UI
        }
    }

    private void proceedToQRScanner() {
        Log.d(TAG, "All mandatory permissions granted, proceeding to QR scanner");

        // Show success message
        Toast.makeText(this, "✅ All permissions granted! Opening QR scanner...",
                Toast.LENGTH_SHORT).show();

        // Navigate to QR scanner (ChildLoginActivity)
        Intent intent = new Intent(this, ChildLoginActivity.class);
        startActivity(intent);
    }

    @Override
    public void onBackPressed() {
        // Show confirmation dialog before going back
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Go Back?")
                .setMessage("You need to grant all mandatory permissions to use the app. Go back to login screen?")
                .setPositiveButton("Yes, Go Back", (dialog, which) -> {
                    super.onBackPressed();
                })
                .setNegativeButton("Stay Here", null)
                .show();
    }
}
