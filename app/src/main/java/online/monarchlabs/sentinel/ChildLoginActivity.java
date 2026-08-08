package online.monarchlabs.sentinel;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import online.monarchlabs.sentinel.databinding.ActivityChildLoginBinding;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import java.util.ArrayList;
import java.util.List;

public class ChildLoginActivity extends BaseActivity {
    private ActivityChildLoginBinding binding;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;

    // QR Code scanning components
    private DecoratedBarcodeView barcodeScanner;
    private TextView tvScanStatus;
    private ChildConnectionManager connectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Enable hardware acceleration for better performance
        getWindow().setFlags(
            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );
        
        binding = ActivityChildLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        initViews();
        connectionManager = new ChildConnectionManager(this);

        // Check and request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startQRScanning();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    private void initViews() {
        barcodeScanner = binding.barcodeScanner;
        tvScanStatus = binding.tvScanStatus;

        if (barcodeScanner == null) {
            Toast.makeText(this, "Error: Scanner not found in layout", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (binding.btnBack != null) {
            binding.btnBack.setOnClickListener(v -> onBackPressed());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startQRScanning();
            } else {
                Toast.makeText(this, "Camera permission is required to scan QR code", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startQRScanning() {
        if (barcodeScanner == null) {
            Toast.makeText(this, "Scanner not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        tvScanStatus.setText("Scanning for QR code...");

        barcodeScanner.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                handleQRScan(result.getText());
            }

            @Override
            public void possibleResultPoints(List<ResultPoint> resultPoints) {
                // Optional: Handle possible result points for UI feedback
            }
        });

        Toast.makeText(this, "Point camera at parent's QR code", Toast.LENGTH_LONG).show();
    }

    private void handleQRScan(String scannedData) {
        // Pause scanning to prevent multiple scans
        barcodeScanner.pause();
        tvScanStatus.setText("QR Code detected! Connecting...");

        try {
            // Parse QR data format: shareKey|parentDeviceId|parentDeviceName
            String[] parts = scannedData.split("\\|");

            if (parts.length >= 3) {
                String shareKey = parts[0];
                String parentDeviceId = parts[1];
                String parentDeviceName = parts[2];
                String sessionId = parts.length >= 4 ? parts[3] : null;

                connectToParent(shareKey, parentDeviceId, parentDeviceName, sessionId);
            } else {
                tvScanStatus.setText("Invalid QR code format");
                Toast.makeText(this, "Invalid QR code format", Toast.LENGTH_SHORT).show();

                // Resume scanning after 2 seconds
                barcodeScanner.postDelayed(() -> {
                    tvScanStatus.setText("Scanning for QR code...");
                    barcodeScanner.resume();
                }, 2000);
            }
        } catch (Exception e) {
            tvScanStatus.setText("Error reading QR code");
            Toast.makeText(this, "Error reading QR code", Toast.LENGTH_SHORT).show();

            // Resume scanning after 2 seconds
            barcodeScanner.postDelayed(() -> {
                tvScanStatus.setText("Scanning for QR code...");
                barcodeScanner.resume();
            }, 2000);
        }
    }

    private void connectToParent(final String shareKey, final String parentDeviceId,
            final String parentDeviceName, final String sessionId) {
        tvScanStatus.setText("Connecting to " + parentDeviceName + "...");
        Toast.makeText(this, "Connecting to " + parentDeviceName + "...", Toast.LENGTH_SHORT).show();

        // Remove usage stats permission check/request here
        // Permission will be requested in ChildDashboardActivity after connection

        // Start connection process in background thread
        new Thread(() -> {
            try {
                Log.d("ChildLogin", "Starting connection process...");
                
                // Get this child device's app list with safety checks
                List<AppInfo> childApps = null;
                String childDeviceId = null;
                String childDeviceName = null;
                String childUserName = null; // 🔧 FIX: User-entered name from setup
                
                // 🔧 FIX: Get userName from SessionManager (entered in ChildNameActivity)
                SessionManager sessionManager = new SessionManager(ChildLoginActivity.this);
                childUserName = sessionManager.getChildName();
                Log.d("ChildLogin", "🔧 User-entered child name: " + childUserName);
                
                try {
                    childApps = ChildAppUtils.getInstalledApps(ChildLoginActivity.this);
                    Log.d("ChildLogin", "Successfully got " + childApps.size() + " apps");
                } catch (Exception e) {
                    Log.e("ChildLogin", "Error getting apps: " + e.getMessage());
                    childApps = new ArrayList<>(); // Use empty list as fallback
                }
                
                try {
                    childDeviceId = ChildAppUtils.getChildDeviceId(ChildLoginActivity.this);
                    childDeviceName = ChildAppUtils.getChildDeviceName();
                    Log.d("ChildLogin", "Device info: ID=" + childDeviceId + ", Name=" + childDeviceName);
                    
                    // 🔧 FIX: Use userName if available, otherwise fallback to deviceName
                    if (childUserName != null && !childUserName.isEmpty()) {
                        Log.d("ChildLogin", "🔧 Using user-entered name: " + childUserName);
                    } else {
                        childUserName = childDeviceName; // Fallback to device name
                        Log.d("ChildLogin", "⚠️ No user name found, using device name: " + childDeviceName); 
                    }
                } catch (Exception e) {
                    Log.e("ChildLogin", "Error getting device info: " + e.getMessage());
                    runOnUiThread(() -> {
                        tvScanStatus.setText("Error getting device info");
                        Toast.makeText(ChildLoginActivity.this,
                                "❌ Error getting device info: " + e.getMessage() + "\n\nTrying again in 3 seconds...", Toast.LENGTH_LONG).show();
                        
                        barcodeScanner.postDelayed(() -> {
                            tvScanStatus.setText("Scanning for QR code...");
                            barcodeScanner.resume();
                        }, 3000);
                    });
                    return;
                }

                // Make variables effectively final for use in inner lambdas
                final String finalChildDeviceId = childDeviceId;
                final String finalChildDeviceName = childDeviceName;
                final String finalChildUserName = childUserName; // 🔧 FIX: Pass user name
                final List<AppInfo> finalChildApps = childApps;

                Log.d("ChildLogin", "Child device info: ID=" + finalChildDeviceId + ", DeviceName=" + finalChildDeviceName + ", UserName=" + finalChildUserName + ", Apps=" + finalChildApps.size());

                // 🔧 FIX: Pass userName instead of deviceName so parent sees actual child name
                connectionManager.connectToParent(
                        shareKey,
                        sessionId,
                        parentDeviceId,
                        parentDeviceName,
                        finalChildDeviceId,
                        finalChildUserName, // 🔧 Pass user-entered name, not device model
                        finalChildApps,
                        new ChildConnectionManager.OnConnectionListener() {
                            @Override
                            public void onSuccess(String parentUserId) {
                                runOnUiThread(() -> {
                                    try {
                                        // Handle different success states
                                        if ("CONNECTING".equals(parentUserId)) {
                                            tvScanStatus.setText("Connecting...");
                                            Toast.makeText(ChildLoginActivity.this,
                                                    "🔄 Connecting to " + parentDeviceName + "...",
                                                    Toast.LENGTH_SHORT).show();
                                            return; // Don't proceed to dashboard yet
                                        }
                                        
                                        String resolvedParentUserId = parentUserId;
                                        String connectionId = null;
                                        long connectionLinkedAt = 0L;
                                        boolean isFastConnection = false;
                                        if (parentUserId != null && parentUserId.startsWith("CONNECTED_FAST:")) {
                                            isFastConnection = true;
                                            String payload = parentUserId.substring("CONNECTED_FAST:".length());
                                            String[] payloadParts = payload.split("\\|", -1);
                                            resolvedParentUserId = payloadParts.length > 0 ? payloadParts[0] : null;
                                            connectionId = payloadParts.length > 1 ? payloadParts[1] : null;
                                            if (payloadParts.length > 2) {
                                                try {
                                                    connectionLinkedAt = Long.parseLong(payloadParts[2]);
                                                } catch (NumberFormatException ignored) {
                                                    connectionLinkedAt = 0L;
                                                }
                                            }
                                        } else if ("CONNECTED_FAST".equals(parentUserId)) {
                                            isFastConnection = true;
                                            resolvedParentUserId = null;
                                        }

                                        if (isFastConnection) {
                                            tvScanStatus.setText("Connected! Loading dashboard...");
                                            Toast.makeText(ChildLoginActivity.this,
                                                    "⚡ Connected to " + parentDeviceName + "!\n📱 Apps uploading in background...",
                                                    Toast.LENGTH_SHORT).show();
                                        } else {
                                            tvScanStatus.setText("Connected successfully!");
                                            Toast.makeText(ChildLoginActivity.this,
                                                    "✅ Successfully connected to " + parentDeviceName + "!\n📱 Sent " + finalChildApps.size() + " apps\n🔒 Remote blocking is now active!",
                                                    Toast.LENGTH_LONG).show();
                                        }

                                        // CRITICAL: Save session data immediately after successful connection
                                        SessionManager sessionManager = new SessionManager(ChildLoginActivity.this);
                                        sessionManager.saveChildSession(finalChildDeviceId, parentDeviceName, shareKey,
                                                connectionId, connectionLinkedAt);
                                        if (resolvedParentUserId != null && !resolvedParentUserId.startsWith("CONNECTED")) {
                                            sessionManager.saveParentUserId(resolvedParentUserId);
                                            Log.d("ChildLogin", "✅ Parent user ID saved: " + resolvedParentUserId);
                                        } else {
                                            Log.w("ChildLogin", "⚠️ Parent user ID is null or special value, not saved to session!");
                                        }
                                        online.monarchlabs.sentinel.workers.UsageUploadScheduler
                                                .triggerBootstrapUpload(
                                                        ChildLoginActivity.this,
                                                        connectionId);                                        getSharedPreferences("child_onboarding_state", MODE_PRIVATE)
                                                .edit()
                                                .putBoolean("permission_setup_active", false)
                                                .apply();
                                        Log.d("ChildLogin", "✅ Session saved: DeviceID=" + finalChildDeviceId + 
                                              ", Parent=" + parentDeviceName + ", ShareKey=" + shareKey);

                                        // Navigate to child dashboard
                                        Intent intent = new Intent(ChildLoginActivity.this, ChildDashboardActivity.class);
                                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                        intent.putExtra("parentName", parentDeviceName);
                                        intent.putExtra("shareKey", shareKey);
                                        intent.putExtra("deviceId", finalChildDeviceId);
                                        if (resolvedParentUserId != null) {
                                            intent.putExtra("parentUserId", resolvedParentUserId);
                                        }
                                        if (connectionId != null) {
                                            intent.putExtra("connectionId", connectionId);
                                        }
                                        if (connectionLinkedAt > 0) {
                                            intent.putExtra("connectionLinkedAt", connectionLinkedAt);
                                        }
                                        startActivity(intent);
                                        finish();
                                    } catch (Exception e) {
                                        Log.e("ChildLogin", "Error in onSuccess: " + e.getMessage());
                                    }
                                });
                            }

                            @Override
                            public void onError(String error) {
                                runOnUiThread(() -> {
                                    Log.e("ChildLogin", "Connection error: " + error);
                                    tvScanStatus.setText("Connection failed: " + error);
                                    Toast.makeText(ChildLoginActivity.this,
                                            "❌ Connection failed: " + error + "\n\nTrying again in 3 seconds...", Toast.LENGTH_LONG).show();

                                    // Resume scanning after 3 seconds
                                    barcodeScanner.postDelayed(() -> {
                                        tvScanStatus.setText("Scanning for QR code...");
                                        barcodeScanner.resume();
                                    }, 3000);
                                });
                            }
                        }
                );

            } catch (Exception e) {
                Log.e("ChildLogin", "Critical error in connection thread: " + e.getMessage());
                runOnUiThread(() -> {
                    tvScanStatus.setText("Critical error: " + e.getMessage());
                    Toast.makeText(ChildLoginActivity.this,
                            "❌ Critical error: " + e.getMessage() + "\n\nTrying again in 3 seconds...", Toast.LENGTH_LONG).show();

                    // Resume scanning after 3 seconds
                    barcodeScanner.postDelayed(() -> {
                        tvScanStatus.setText("Scanning for QR code...");
                        barcodeScanner.resume();
                    }, 3000);
                });
            }
        }).start();
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void requestUsageStatsPermission() {
        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("Usage access permission is required to track app usage for parental controls.\n\nPlease grant access to this app on the next screen.")
                    .setPositiveButton("Open Settings", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                        startActivity(intent);
                    })
                    .setCancelable(false)
                    .show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        SessionManager sessionManager = new SessionManager(this);
        if (shouldStayOnQrAfterRemoval()) {
            Log.d("ChildLogin", "Parent removal QR mode active; blocking dashboard auto-redirect");
            sessionManager.logoutUser();
            if (barcodeScanner != null) {
                barcodeScanner.resume();
            }
            return;
        }

        if (sessionManager.isLoggedIn()) {
            // Already connected, go to dashboard
            Intent intent = new Intent(this, ChildDashboardActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
            return;
        }

        // else, stay on QR scan screen
        if (barcodeScanner != null) {
            barcodeScanner.resume();
        }
    }

    private boolean shouldStayOnQrAfterRemoval() {
        Intent intent = getIntent();
        if (intent != null && (intent.getBooleanExtra("require_qr_reconnection", false)
                || intent.getBooleanExtra("device_was_removed", false))) {
            return true;
        }

        android.content.SharedPreferences disconnectionPrefs = getSharedPreferences("disconnection_state", MODE_PRIVATE);
        boolean removed = disconnectionPrefs.getBoolean("device_was_removed", false);
        boolean requiresQr = disconnectionPrefs.getBoolean("require_qr_reconnection", false);
        return removed && requiresQr;
    }
    @Override
    protected void onPause() {
        super.onPause();
        if (barcodeScanner != null) {
            barcodeScanner.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (barcodeScanner != null) {
            barcodeScanner.pause();
        }
        binding = null;
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}
