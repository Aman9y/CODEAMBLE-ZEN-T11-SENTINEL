package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import online.monarchlabs.sentinel.utils.InfoContentRepository;
import com.google.android.material.checkbox.MaterialCheckBox;

/** Standalone prominent disclosure shown before child-device permission requests. */
public class ChildMonitoringDisclosureActivity extends BaseActivity {
    public static final String EXTRA_VIEW_ONLY = "view_only";
    public static final String EXTRA_RETURN_TO_DASHBOARD = "return_to_dashboard";

    private static final String PREFS_NAME = "child_monitoring_disclosure";
    private static final String KEY_VERSION = "accepted_version";
    private static final String KEY_ACCEPTED_AT = "accepted_at";
    private static final String DISCLOSURE_VERSION = "child-monitoring-2026-06-13";

    public static boolean hasAcceptedDisclosure(Context context) {
        return DISCLOSURE_VERSION.equals(context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_VERSION, null));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_monitoring_disclosure);

        MaterialCheckBox acknowledgement = findViewById(R.id.cbMonitoringAcknowledgement);
        TextView privacyLink = findViewById(R.id.tvMonitoringPrivacyLink);
        Button continueButton = findViewById(R.id.btnMonitoringContinue);
        boolean viewOnly = getIntent().getBooleanExtra(EXTRA_VIEW_ONLY, false);

        setupPrivacyLink(privacyLink);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        if (viewOnly) {
            acknowledgement.setVisibility(View.GONE);
            continueButton.setEnabled(true);
            continueButton.setText("Close");
            continueButton.setOnClickListener(v -> finish());
            return;
        }

        final int normalTextColor = ContextCompat.getColor(this, R.color.text_primary);
        final int errorTextColor = ContextCompat.getColor(this, R.color.error_600);

        acknowledgement.setOnCheckedChangeListener((buttonView, checked) -> {
            if (checked) {
                acknowledgement.setTextColor(normalTextColor);
            }
        });
        continueButton.setOnClickListener(v -> {
            if (!acknowledgement.isChecked()) {
                acknowledgement.setTextColor(errorTextColor);
                return;
            }

            SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            preferences.edit()
                    .putString(KEY_VERSION, DISCLOSURE_VERSION)
                    .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
                    .apply();

            Class<?> destination = getIntent().getBooleanExtra(EXTRA_RETURN_TO_DASHBOARD, false)
                    ? ChildDashboardActivity.class
                    : ChildPermissionsActivity.class;
            startActivity(new Intent(this, destination));
            finish();
        });
    }

    private void setupPrivacyLink(TextView privacyLink) {
        String text = "Read the Privacy Policy for full details.";
        String label = "Privacy Policy";
        int start = text.indexOf(label);
        SpannableString spannable = new SpannableString(text);
        spannable.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                Intent intent = new Intent(ChildMonitoringDisclosureActivity.this,
                        InfoDetailActivity.class);
                intent.putExtra(InfoDetailActivity.EXTRA_CONTENT_KEY,
                        InfoContentRepository.KEY_PRIVACY);
                startActivity(intent);
            }
        }, start, start + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        privacyLink.setText(spannable);
        privacyLink.setMovementMethod(LinkMovementMethod.getInstance());
        privacyLink.setHighlightColor(android.graphics.Color.TRANSPARENT);
    }
}
