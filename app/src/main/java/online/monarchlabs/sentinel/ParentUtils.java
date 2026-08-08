package online.monarchlabs.sentinel;

import android.content.Context;
import android.provider.Settings;

public class ParentUtils {

    /**
     * Get the unique device ID for the parent device
     * @param context Application context
     * @return Unique device identifier
     */
    public static String getParentDeviceId(Context context) {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    /**
     * Get the device name for the parent device
     * @return Device model name with manufacturer and "(Parent)" suffix
     */
    public static String getParentDeviceName() {
        String manufacturer = android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL;
        
        // Capitalize manufacturer name
        manufacturer = manufacturer.substring(0, 1).toUpperCase() + manufacturer.substring(1).toLowerCase();
        
        // Check if model already contains manufacturer name
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return model + " (Parent)";
        } else {
            return manufacturer + " " + model + " (Parent)";
        }
    }

    /**
     * Get device manufacturer
     * @return Device manufacturer name
     */
    public static String getDeviceManufacturer() {
        return android.os.Build.MANUFACTURER;
    }

    /**
     * Get device model
     * @return Device model name
     */
    public static String getDeviceModel() {
        return android.os.Build.MODEL;
    }

    /**
     * Check if device is MIUI (Xiaomi)
     * @return true if device is MIUI, false otherwise
     */
    public static boolean isMIUI() {
        return "Xiaomi".equalsIgnoreCase(android.os.Build.MANUFACTURER) ||
                android.os.Build.MODEL.toLowerCase().contains("redmi") ||
                android.os.Build.MODEL.toLowerCase().contains("poco") ||
                System.getProperty("ro.miui.ui.version.code") != null;
    }
}