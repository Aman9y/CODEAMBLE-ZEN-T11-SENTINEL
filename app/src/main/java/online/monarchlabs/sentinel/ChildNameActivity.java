package online.monarchlabs.sentinel;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Activity to collect child's name before proceeding to permissions and QR
 * scanning.
 * The child name will be stored and displayed on parent dashboard instead of
 * device name.
 */
public class ChildNameActivity extends BaseActivity {
    private static final String TAG = "ChildNameActivity";

    private ScrollView childNameScrollView;
    private EditText etChildName;
    private Button btnContinue;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_name);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        sessionManager = new SessionManager(this);

        initViews();
        setupKeyboardHandling();
        setupListeners();
    }

    private void initViews() {
        childNameScrollView = findViewById(R.id.childNameScrollView);
        etChildName = findViewById(R.id.etChildName);
        btnContinue = findViewById(R.id.btnContinue);
    }

    private void setupKeyboardHandling() {
        ViewCompat.setOnApplyWindowInsetsListener(childNameScrollView, (view, insets) -> {
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int bottomPadding = Math.max(imeBottom, systemBottom);
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), bottomPadding);
            return insets;
        });

        View.OnFocusChangeListener scrollIntoView = (view, hasFocus) -> {
            if (hasFocus) {
                view.post(() -> {
                    if (childNameScrollView != null) {
                        childNameScrollView.smoothScrollTo(0, Math.max(0, view.getBottom() - 64));
                    }
                });
            }
        };

        etChildName.setOnFocusChangeListener(scrollIntoView);
        ViewCompat.requestApplyInsets(childNameScrollView);
    }

    private void setupListeners() {
        btnContinue.setOnClickListener(v -> {
            String childName = etChildName.getText().toString().trim();

            if (TextUtils.isEmpty(childName)) {
                etChildName.setError("Please enter your name");
                etChildName.requestFocus();
                return;
            }

            if (childName.length() < 2) {
                etChildName.setError("Name must be at least 2 characters");
                etChildName.requestFocus();
                return;
            }

            // Save child name to session
            sessionManager.saveChildName(childName);
            getSharedPreferences("child_onboarding_state", MODE_PRIVATE)
                    .edit()
                    .putBoolean("permission_setup_active", true)
                    .apply();

            Toast.makeText(this, "Hello, " + childName + "!", Toast.LENGTH_SHORT).show();

            // Show the standalone monitoring disclosure before requesting permissions.
            Intent intent = new Intent(this, ChildMonitoringDisclosureActivity.class);
            startActivity(intent);
            finish();
        });
    }

    @Override
    public void onBackPressed() {
        // Go back to login type selection
        super.onBackPressed();
    }
}
