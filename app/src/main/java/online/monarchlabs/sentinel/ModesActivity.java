package online.monarchlabs.sentinel;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import online.monarchlabs.sentinel.models.StudyModePolicy;
import online.monarchlabs.sentinel.utils.StudyModeDraftStore;

import java.util.Locale;

public class ModesActivity extends BaseActivity {
    public static final String EXTRA_CHILD_DEVICE_ID = "childDeviceId";
    public static final String EXTRA_CHILD_NAME = "childName";

    private String childDeviceId;
    private TextView tvStudyStatus;
    private TextView tvStudySchedule;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_modes);

        childDeviceId = getIntent().getStringExtra(EXTRA_CHILD_DEVICE_ID);
        tvStudyStatus = findViewById(R.id.tvStudyStatus);
        tvStudySchedule = findViewById(R.id.tvStudySchedule);

        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        View cardStudyMode = findViewById(R.id.cardStudyMode);
        if (cardStudyMode != null) {
            cardStudyMode.setOnClickListener(v -> {
                Intent intent = new Intent(this, StudyModeEditActivity.class);
                intent.putExtra(StudyModeEditActivity.EXTRA_CHILD_DEVICE_ID, childDeviceId);
                intent.putExtra(StudyModeEditActivity.EXTRA_CHILD_NAME,
                        getIntent().getStringExtra(EXTRA_CHILD_NAME));
                startActivity(intent);
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderStudySummary();
    }

    private void renderStudySummary() {
        StudyModePolicy policy = StudyModeDraftStore.load(this, childDeviceId);
        if (tvStudyStatus != null) {
            tvStudyStatus.setText(policy.enabled ? "SCHEDULED" : "OFF");
            tvStudyStatus.setBackgroundResource(policy.enabled
                    ? R.drawable.bg_mode_status_scheduled
                    : R.drawable.bg_mode_status_off);
            tvStudyStatus.setTextColor(getColor(policy.enabled
                    ? R.color.modern_blue_700
                    : R.color.modern_grey_600));
        }
        if (tvStudySchedule != null) {
            tvStudySchedule.setText(buildScheduleSummary(policy));
        }
    }

    private String buildScheduleSummary(StudyModePolicy policy) {
        String days = formatDays(policy);
        String slots = "";
        if (policy.timeSlots != null && !policy.timeSlots.isEmpty()) {
            StudyModePolicy.TimeSlot first = policy.timeSlots.get(0);
            slots = formatTime(first.start) + " - " + formatTime(first.end);
            if (policy.timeSlots.size() > 1) {
                slots = slots + String.format(Locale.US, " +%d", policy.timeSlots.size() - 1);
            }
        }
        if (days.isEmpty()) {
            return slots.isEmpty() ? "No schedule selected" : slots;
        }
        return slots.isEmpty() ? days : days + ", " + slots;
    }

    private String formatDays(StudyModePolicy policy) {
        if (policy.days == null || policy.days.isEmpty()) {
            return "";
        }
        if (policy.days.size() == 5
                && policy.days.contains("MON")
                && policy.days.contains("TUE")
                && policy.days.contains("WED")
                && policy.days.contains("THU")
                && policy.days.contains("FRI")) {
            return "Mon-Fri";
        }
        return String.join(", ", policy.days);
    }

    private String formatTime(String value) {
        if (value == null || !value.contains(":")) {
            return "";
        }
        String[] parts = value.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        String suffix = hour >= 12 ? "PM" : "AM";
        int displayHour = hour % 12;
        if (displayHour == 0) {
            displayHour = 12;
        }
        return String.format(Locale.US, "%d:%02d %s", displayHour, minute, suffix);
    }
}
