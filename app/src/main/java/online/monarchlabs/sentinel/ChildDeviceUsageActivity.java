package online.monarchlabs.sentinel;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import online.monarchlabs.sentinel.adapters.SUsageAppAdapter;
import online.monarchlabs.sentinel.models.SUsageAppInfo;
import online.monarchlabs.sentinel.models.SUsageDailyData;
import online.monarchlabs.sentinel.utils.SUsageDataManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Activity to display usage data on the child device.
 * Uses SUSAGE methodology for accurate per-day usage tracking.
 */
public class ChildDeviceUsageActivity extends BaseActivity {

    private static final String TAG = "ChildDeviceUsage";

    // Views
    private TextView tvDateLabel;
    private TextView tvUsagePercentage;
    private TextView tvTotalScreenTime;
    private ProgressBar progressUsage;
    private TextView tvSelectedDayTime;
    private TextView tvSelectedDayLabel;
    private SimpleBarChart weeklyChart;
    private RecyclerView rvAppsUsage;
    private TextView tvAppCount;
    private TextView tvEmptyApps;
    private ImageView btnPrevDay;
    private ImageView btnNextDay;
    private ImageView btnRefresh;
    private ImageView btnBack;
    private View loadingOverlay;

    // Data
    private SUsageDataManager usageDataManager;
    private SUsageAppAdapter appAdapter;
    private List<SUsageDailyData> weeklyUsage = new ArrayList<>();
    private int selectedDayIndex = 6; // Today (last day in 7-day window)
    private String deviceId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_device_usage);

        // Get device ID from session
        SessionManager sessionManager = new SessionManager(this);
        deviceId = sessionManager.getChildDeviceId();

        Log.d(TAG, "ChildDeviceUsageActivity started for device: " + deviceId);

        initViews();
        setupRecyclerView();
        setupClickListeners();

        usageDataManager = SUsageDataManager.getInstance(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadUsageData();
    }

    private void initViews() {
        tvDateLabel = findViewById(R.id.tvDateLabel);
        tvUsagePercentage = findViewById(R.id.tvUsagePercentage);
        tvTotalScreenTime = findViewById(R.id.tvTotalScreenTime);
        progressUsage = findViewById(R.id.progressUsage);
        tvSelectedDayTime = findViewById(R.id.tvSelectedDayTime);
        tvSelectedDayLabel = findViewById(R.id.tvSelectedDayLabel);
        weeklyChart = findViewById(R.id.weeklyChart);
        rvAppsUsage = findViewById(R.id.rvAppsUsage);
        tvAppCount = findViewById(R.id.tvAppCount);
        tvEmptyApps = findViewById(R.id.tvEmptyApps);
        btnPrevDay = findViewById(R.id.btnPrevDay);
        btnNextDay = findViewById(R.id.btnNextDay);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnBack = findViewById(R.id.btnBack);
        loadingOverlay = findViewById(R.id.loadingOverlay);

        // Set today's date
        SimpleDateFormat sdf = new SimpleDateFormat("'Today,' d MMMM", Locale.getDefault());
        tvDateLabel.setText(sdf.format(Calendar.getInstance().getTime()));
    }

    private void setupRecyclerView() {
        appAdapter = new SUsageAppAdapter();
        appAdapter.setPackageManager(getPackageManager());
        rvAppsUsage.setLayoutManager(new LinearLayoutManager(this));
        rvAppsUsage.setAdapter(appAdapter);
        rvAppsUsage.setNestedScrollingEnabled(false);
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnRefresh.setOnClickListener(v -> {
            showLoading(true);
            // Reload data and upload to Firebase
            loadUsageData();
            uploadToFirebase();
        });

        btnPrevDay.setOnClickListener(v -> navigateDay(-1));
        btnNextDay.setOnClickListener(v -> navigateDay(1));

        // Chart bar click listener
        weeklyChart.setOnBarClickListener((index, valueMinutes) -> {
            selectedDayIndex = index;
            updateSelectedDayDisplay();
        });
    }

    private void loadUsageData() {
        showLoading(true);

        new Thread(() -> {
            try {
                weeklyUsage = usageDataManager.getWeeklyUsage();

                runOnUiThread(() -> {
                    showLoading(false);
                    selectedDayIndex = 6; // Reset to today
                    updateTodayCard();
                    updateWeeklyChart();
                    updateSelectedDayDisplay();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading usage data: " + e.getMessage());
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(this, "Error loading usage data", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void uploadToFirebase() {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, "No device ID available for upload");
            return;
        }

        usageDataManager.uploadToFirebase(deviceId, new SUsageDataManager.OnUploadCompleteListener() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(ChildDeviceUsageActivity.this,
                            "Usage data synced", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Log.e(TAG, "Upload failed: " + error);
                    Toast.makeText(ChildDeviceUsageActivity.this,
                            "Sync failed: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void updateTodayCard() {
        if (weeklyUsage == null || weeklyUsage.isEmpty())
            return;

        SUsageDailyData todayUsage = weeklyUsage.get(6); // Today is index 6

        int percentage = todayUsage.getUsagePercentage();
        tvUsagePercentage.setText(percentage + "%");
        progressUsage.setProgress(percentage);
        tvTotalScreenTime.setText(todayUsage.getShortFormattedTime());
    }

    private void updateWeeklyChart() {
        if (weeklyUsage == null || weeklyUsage.isEmpty())
            return;

        List<Float> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        for (SUsageDailyData daily : weeklyUsage) {
            // Convert ms to minutes for chart
            float minutes = daily.getTotalScreenTimeMillis() / (1000f * 60f);
            values.add(minutes);
            labels.add(daily.getShortDayName());
        }

        weeklyChart.setData(values, labels);
    }

    private void navigateDay(int direction) {
        int newIndex = selectedDayIndex + direction;
        if (newIndex >= 0 && newIndex < 7 && weeklyUsage != null && !weeklyUsage.isEmpty()) {
            selectedDayIndex = newIndex;
            updateSelectedDayDisplay();
        }
    }

    private void updateSelectedDayDisplay() {
        if (weeklyUsage == null || weeklyUsage.isEmpty() || selectedDayIndex < 0
                || selectedDayIndex >= weeklyUsage.size()) {
            return;
        }

        SUsageDailyData selectedDay = weeklyUsage.get(selectedDayIndex);

        // Update time display
        tvSelectedDayTime.setText(selectedDay.getShortFormattedTime());
        tvSelectedDayLabel.setText(selectedDay.getFormattedDate());

        // Update apps list
        List<SUsageAppInfo> appList = selectedDay.getAppList();

        // Sort by usage time descending
        Collections.sort(appList, (a, b) -> Long.compare(b.getUsageTimeMillis(), a.getUsageTimeMillis()));

        appAdapter.updateData(appList);
        tvAppCount.setText(appList.size() + " apps");

        // Show/hide empty state
        if (appList.isEmpty()) {
            tvEmptyApps.setVisibility(View.VISIBLE);
            rvAppsUsage.setVisibility(View.GONE);
        } else {
            tvEmptyApps.setVisibility(View.GONE);
            rvAppsUsage.setVisibility(View.VISIBLE);
        }

        // Update navigation button visibility
        btnPrevDay.setAlpha(selectedDayIndex > 0 ? 1.0f : 0.3f);
        btnNextDay.setAlpha(selectedDayIndex < 6 ? 1.0f : 0.3f);
    }

    private void showLoading(boolean show) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }
}
