package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import online.monarchlabs.sentinel.utils.AppCategorizer;

public class ChildAppUtils {

    public static List<AppInfo> getInstalledApps(Context context) {
        List<AppInfo> appInfos = new ArrayList<>();
        
        try {
            PackageManager pm = context.getPackageManager();
            
            // Get apps that have launcher activities (apps with icons that users can see)
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launcherApps = pm.queryIntentActivities(mainIntent, 0);
            
            android.util.Log.d("ChildAppUtils", "Found " + launcherApps.size() + " apps with launcher icons to process");

            for (ResolveInfo resolveInfo : launcherApps) {
                try {
                    if (resolveInfo == null || resolveInfo.activityInfo == null) {
                        android.util.Log.w("ChildAppUtils", "Null resolveInfo or activityInfo, skipping");
                        continue;
                    }
                    
                    String packageName = resolveInfo.activityInfo.packageName;
                    if (packageName == null || packageName.trim().isEmpty()) {
                        android.util.Log.w("ChildAppUtils", "Empty package name, skipping");
                        continue;
                    }
                    
                    // Skip our own app
                    if (packageName.contains("online.monarchlabs.sentinel")) {
                        android.util.Log.d("ChildAppUtils", "Skipping our own app: " + packageName);
                        continue;
                    }
                    
                    // Skip problematic packages that commonly cause issues
                    if (shouldSkipPackage(packageName)) {
                        android.util.Log.d("ChildAppUtils", "Skipping problematic package: " + packageName);
                        continue;
                    }
                    
                    String appName = null;
                    try {
                        appName = resolveInfo.loadLabel(pm).toString();
                        if (appName == null || appName.trim().isEmpty()) {
                            appName = getAppNameFromPackage(packageName); // Fallback to package name
                        }
                    } catch (Exception e) {
                        android.util.Log.w("ChildAppUtils", "Failed to load app name for " + packageName + ", using fallback");
                        appName = getAppNameFromPackage(packageName);
                    }
                    
                    // Skip if we still don't have a valid name
                    if (appName == null || appName.trim().isEmpty()) {
                        android.util.Log.w("ChildAppUtils", "No valid app name found for " + packageName + ", skipping");
                        continue;
                    }
                    
                    // Basic app info creation (safe fallback)
                    AppInfo appInfoObj = new AppInfo(appName.trim(), packageName, "", false);
                    
                    // Safely get icon (skip if fails - not critical)
                    try {
                        Drawable icon = resolveInfo.loadIcon(pm);
                        if (icon != null) {
                            appInfoObj.icon = icon;
                            appInfoObj.iconBase64 = drawableToBase64(icon);
                        }
                    } catch (Exception e) {
                        android.util.Log.w("ChildAppUtils", "Failed to load icon for " + packageName + ": " + e.getMessage());
                        appInfoObj.iconBase64 = "";
                    }
                    
                    // Safely populate additional fields
                    try {
                        android.content.pm.PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);
                        
                        // Safe version code handling
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                appInfoObj.versionCode = packageInfo.getLongVersionCode();
                            } else {
                                appInfoObj.versionCode = (long) packageInfo.versionCode;
                            }
                        } catch (Exception e) {
                            appInfoObj.versionCode = 0L;
                        }
                        
                        appInfoObj.versionName = packageInfo.versionName != null ? packageInfo.versionName : "Unknown";
                        
                        // Safe system app check
                        try {
                            appInfoObj.isSystemApp = (packageInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                        } catch (Exception e) {
                            appInfoObj.isSystemApp = false;
                        }
                        // Centralized category handling
                        try {
                            appInfoObj.category = AppCategorizer
                                    .getCategory(packageName, appName)
                                    .getDisplayName();
                        } catch (Exception e) {
                            appInfoObj.category = "Other";
                        }
                        
                    } catch (PackageManager.NameNotFoundException e) {
                        // Set safe default values
                        appInfoObj.versionCode = 0L;
                        appInfoObj.versionName = "Unknown";
                        appInfoObj.isSystemApp = false;
                        appInfoObj.category = "Other";
                        android.util.Log.w("ChildAppUtils", "Package info not found for " + packageName);
                    } catch (Exception e) {
                        // Handle other exceptions (like corrupted APK files)
                        android.util.Log.w("ChildAppUtils", "Error getting package info for " + packageName + ": " + e.getMessage());
                        appInfoObj.versionCode = 0L;
                        appInfoObj.versionName = "Unknown";
                        appInfoObj.isSystemApp = false;
                        appInfoObj.category = "Other";
                    }
                    
                    // Final validation before adding
                    if (appInfoObj.name != null && !appInfoObj.name.trim().isEmpty() && 
                        appInfoObj.packageName != null && !appInfoObj.packageName.trim().isEmpty()) {
                        appInfos.add(appInfoObj);
                    } else {
                        android.util.Log.w("ChildAppUtils", "Skipping app with invalid final data: " + packageName);
                    }
                    
                } catch (Exception e) {
                    android.util.Log.e("ChildAppUtils", "Error processing app: " + e.getMessage());
                    // Continue with next app instead of crashing
                }
        }

        // Sort by app name
        Collections.sort(appInfos, Comparator.comparing(app -> app.name.toLowerCase()));
            android.util.Log.d("ChildAppUtils", "Successfully processed " + appInfos.size() + " apps");

        } catch (Exception e) {
            android.util.Log.e("ChildAppUtils", "Critical error in getInstalledApps: " + e.getMessage());
            // Return at least an empty list instead of crashing
        }

        return appInfos;
    }

    public static String drawableToBase64(Drawable drawable) {
        try {
            if (drawable == null) {
                android.util.Log.w("ChildAppUtils", "Drawable is null");
                return "";
            }
            
            Bitmap bitmap = drawableToBitmap(drawable);
            if (bitmap == null) {
                android.util.Log.w("ChildAppUtils", "Failed to convert drawable to bitmap");
                return "";
            }
            
            // Keep Firebase payloads and parent-side decoding lightweight. PNG ignores
            // the quality argument, so resize before encoding instead of uploading the
            // drawable at its original launcher-icon resolution.
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, 48, 48, true);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            resized.compress(Bitmap.CompressFormat.PNG, 100, baos);
            byte[] bytes = baos.toByteArray();
            if (resized != bitmap) {
                resized.recycle();
            }
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            android.util.Log.e("ChildAppUtils", "Error converting drawable to base64: " + e.getMessage());
            return "";
        }
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        try {
            if (drawable == null) {
                return null;
            }
            
        if (drawable instanceof BitmapDrawable) {
                BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
                if (bitmapDrawable.getBitmap() != null) {
                    return bitmapDrawable.getBitmap();
                }
            }

            int width = drawable.getIntrinsicWidth();
            int height = drawable.getIntrinsicHeight();
            
            // Use default size if intrinsic dimensions are invalid
            if (width <= 0) width = 48;
            if (height <= 0) height = 48;
            
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
        } catch (Exception e) {
            android.util.Log.e("ChildAppUtils", "Error in drawableToBitmap: " + e.getMessage());
            return null;
        }
    }

    public static String getChildDeviceId(Context context) {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    public static String getChildDeviceName() {
        String manufacturer = android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL;
        
        // Capitalize manufacturer name
        manufacturer = manufacturer.substring(0, 1).toUpperCase() + manufacturer.substring(1).toLowerCase();
        
        // Check if model already contains manufacturer name
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return model + " (Child)";
        } else {
            return manufacturer + " " + model + " (Child)";
        }
    }

    private static boolean shouldSkipPackage(String packageName) {
        if (packageName == null) return true;
        
        // Skip our own app
        if (packageName.contains("online.monarchlabs.sentinel")) {
            return true;
        }
        
        // Skip only problematic technical packages, but ALLOW popular apps
        if (packageName.contains("base.apk") || 
            packageName.contains(".test") ||
            packageName.contains(".debug") ||
            packageName.contains("android.test") ||
            packageName.endsWith(".test") ||
            packageName.contains(".splits")) {
            return true;
        }
        
        // Skip only core Android system packages that users never interact with
        if (packageName.equals("android") ||
            packageName.equals("com.android.systemui") ||
            packageName.equals("com.android.launcher") ||
            packageName.startsWith("com.android.internal")) {
            return true;
        }
        
        return false;
    }

    private static String getAppNameFromPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "Unknown App";
        }
        
        // Extract the last part of the package name and make it readable
        String[] parts = packageName.split("\\.");
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            // Capitalize first letter and remove common suffixes
            lastPart = lastPart.replace("_", " ");
            if (lastPart.length() > 0) {
                return lastPart.substring(0, 1).toUpperCase() + lastPart.substring(1);
            }
        }
        
        return packageName; // Return original as fallback
    }
}
