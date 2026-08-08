package online.monarchlabs.sentinel;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Helper for the optional Device Admin flow used by uninstall protection.
 */
public class DeviceAdminHelper {
    private static final String TAG = "DeviceAdminHelper";

    private final Context context;
    private final DevicePolicyManager devicePolicyManager;
    private final ComponentName adminComponent;

    public static final int REQUEST_CODE_ENABLE_ADMIN = 2001;

    public DeviceAdminHelper(Context context) {
        this.context = context;
        this.devicePolicyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.adminComponent = new ComponentName(context, AppDeviceAdminReceiver.class);
    }

    public boolean isAdminActive() {
        if (devicePolicyManager == null) {
            return false;
        }
        return devicePolicyManager.isAdminActive(adminComponent);
    }

    public Intent getActivationIntent() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Sentinel uses Device Admin for Uninstall Protection. " +
                        "When active, Android adds a Device Admin verification step before Sentinel can be uninstalled. " +
                        "Sentinel does not use Device Admin to wipe data, reset passwords, lock the device, or disable the camera.");
        return intent;
    }

    public void requestAdminActivation(android.app.Activity activity) {
        if (isAdminActive()) {
            Log.d(TAG, "Device Admin already active");
            android.widget.Toast.makeText(context, "Uninstall Protection is already active",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "Launching Device Admin activation screen");
        activity.startActivityForResult(getActivationIntent(), REQUEST_CODE_ENABLE_ADMIN);
    }
}
