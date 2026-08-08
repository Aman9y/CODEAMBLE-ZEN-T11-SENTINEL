package online.monarchlabs.sentinel;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class LoadingActivity extends BaseActivity {

    @Override
    protected boolean shouldApplyWindowInsets() {
        return false;
    }
    private static final String TAG = "LoadingActivity";
    private SessionManager sessionManager;
    private TextView tvLoadingText, tvLoadingSubtext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            setContentView(R.layout.activity_loading);
            
            sessionManager = new SessionManager(this);
            
            // Initialize views with null checks
            tvLoadingText = findViewById(R.id.tvLoadingText);
            tvLoadingSubtext = findViewById(R.id.tvLoadingSubtext);
            
            if (tvLoadingText == null || tvLoadingSubtext == null) {
                Log.e(TAG, "Failed to initialize views - layout problem");
                finish();
                return;
            }
            
            // Set loading messages based on user type
            String userType = sessionManager != null ? sessionManager.getUserType() : null;
            
            if ("parent".equals(userType)) {
                tvLoadingText.setText("Welcome back!");
                tvLoadingSubtext.setText("Opening dashboard...");
            } else if ("child".equals(userType)) {
                String parentName = sessionManager.getParentName();
                tvLoadingText.setText("Opening...");
                tvLoadingSubtext.setText(parentName != null ? 
                    "Connected to " + parentName : 
                    "Loading your dashboard...");
            } else {
                tvLoadingText.setText("Loading...");
                tvLoadingSubtext.setText("Please wait...");
            }
            
            // Proceed to appropriate activity after loading
            proceedToDestination();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in LoadingActivity onCreate: " + e.getMessage());
            // Fallback - go back to MainActivity
            Intent fallbackIntent = new Intent(this, MainActivity.class);
            startActivity(fallbackIntent);
            finish();
        }
    }
    
    private void proceedToDestination() {
        try {
            String userType = sessionManager != null ? sessionManager.getUserType() : null;
            
            // 🔧 ROLE VALIDATION: Double-check session validity before proceeding
            if (sessionManager != null && !sessionManager.isSessionDataComplete()) {
                Log.w(TAG, "⚠️ INCOMPLETE SESSION DATA detected in LoadingActivity");
                Log.w(TAG, "  UserType: " + userType);
                Log.w(TAG, "  SessionInfo: " + sessionManager.getSessionInfo());
                
                // Clear invalid session and redirect to main
                sessionManager.logoutUser();
                Intent fallbackIntent = new Intent(this, MainActivity.class);
                fallbackIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(fallbackIntent);
                finish();
                return;
            }
            
            // 🔧 SPEED FIX: Minimal loading delay - just enough for smooth transition
            Handler handler = new Handler(Looper.getMainLooper());
            
            // Proceed to destination QUICKLY - no unnecessary waiting
            handler.postDelayed(() -> {
                try {
                    if ("parent".equals(userType)) {
                        Log.d(TAG, "✅ Quick load - parent dashboard");
                        Intent intent = new Intent(LoadingActivity.this, ParentDashboardActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                        
                    } else if ("child".equals(userType)) {
                        Log.d(TAG, "✅ Quick load - child dashboard");
                        
                        String parentName = sessionManager.getParentName();
                        String childDeviceId = sessionManager.getChildDeviceId();
                        String shareKey = sessionManager.getQRShareKey();
                        
                        Intent intent = new Intent(LoadingActivity.this, ChildDashboardActivity.class);
                        intent.putExtra("parentName", parentName);
                        intent.putExtra("shareKey", shareKey);
                        intent.putExtra("deviceId", childDeviceId);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                        
                    } else {
                        Log.w(TAG, "⚠️ Unknown user type, redirecting to main");
                        
                        if (sessionManager != null) {
                            sessionManager.logoutUser();
                        }
                        
                        Intent intent = new Intent(LoadingActivity.this, MainActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in redirect: " + e.getMessage());
                    Intent fallbackIntent = new Intent(LoadingActivity.this, MainActivity.class);
                    startActivity(fallbackIntent);
                    finish();
                }
            }, 500); // 🔧 SPEED: Only 0.5 seconds - just enough for smooth transition
            
        } catch (Exception e) {
            Log.e(TAG, "Error in proceedToDestination: " + e.getMessage());
            // Immediate fallback to MainActivity
            Intent fallbackIntent = new Intent(this, MainActivity.class);
            startActivity(fallbackIntent);
            finish();
        }
    }
}
