package online.monarchlabs.sentinel;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import online.monarchlabs.sentinel.databinding.ActivityLoginTypeBinding;

public class LoginTypeActivity extends BaseActivity {
    private static final String TAG = "LoginTypeActivity";
    private ActivityLoginTypeBinding binding;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        binding = ActivityLoginTypeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        sessionManager = new SessionManager(this);

        // 🔧 ROLE SWITCHING FIX: Clear any existing session when user chooses a role
        // This prevents role confusion from previous sessions
        Log.d(TAG, "LoginTypeActivity opened - ready for fresh role selection");

        // "Get Started" -> Go to Role Selection Screen (Image 3)
        binding.btnGetStarted.setOnClickListener(v -> {
            Log.d(TAG, "Get Started clicked - going to role selection screen");
            Intent intent = new Intent(LoginTypeActivity.this, RoleSelectionActivity.class);
            startActivity(intent);
        });

        // Login Link Removed per user request
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
