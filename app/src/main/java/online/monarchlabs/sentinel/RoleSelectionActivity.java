package online.monarchlabs.sentinel;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import android.widget.LinearLayout;

public class RoleSelectionActivity extends BaseActivity {
    private static final String TAG = "RoleSelectionActivity";
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_role_selection);

        sessionManager = new SessionManager(this);

        LinearLayout cardParent = findViewById(R.id.cardParent);
        LinearLayout cardChild = findViewById(R.id.cardChild);


        cardParent.setOnClickListener(v -> {
            Log.d(TAG, "Parent role selected");
            handleRoleSelection("parent");
            startActivity(new Intent(this, ParentAuthActivity.class));
        });

        cardChild.setOnClickListener(v -> {
            Log.d(TAG, "Child role selected");
            handleRoleSelection("child");
            startActivity(new Intent(this, ChildNameActivity.class));
        });


    }

    private void handleRoleSelection(String selectedRole) {
        try {
            String currentRole = sessionManager != null ? sessionManager.getUserType() : null;

            if (currentRole != null && !currentRole.isEmpty() && !selectedRole.equals(currentRole)) {
                // Changing roles - clear session
                Log.d(TAG, "Switching role from " + currentRole + " to " + selectedRole + ". Clearing session.");
                sessionManager.logoutUser();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling role selection", e);
        }
    }
}
