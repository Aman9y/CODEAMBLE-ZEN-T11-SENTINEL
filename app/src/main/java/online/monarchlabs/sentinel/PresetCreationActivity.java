package online.monarchlabs.sentinel;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import online.monarchlabs.sentinel.models.ChildDeviceManager;
import java.util.ArrayList;
import java.util.List;

public class PresetCreationActivity extends BaseActivity {
    private Button btnBack, btnSavePreset;
    private TextView tvTitle, tvDeviceName, tvSelectedCount, tvLoadingStatus;
    private RecyclerView recyclerView;

    private SimplePresetAdapter adapter;
    private PresetManager presetManager;
    private ChildDeviceManager childDeviceManager;
    private List<AppInfo> allApps;
    private String deviceId, deviceName, shareKey;
    private boolean isEditMode = false;
    private FocusModePreset existingPreset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_preset);

        initViews();
        getIntentData();
        setupClickListeners();

        presetManager = new PresetManager(this);
        childDeviceManager = new ChildDeviceManager(this);

        // Load apps from Firebase
        loadAppsFromFirebase();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        btnSavePreset = findViewById(R.id.btnSavePreset);
        tvTitle = findViewById(R.id.tvTitle);
        tvDeviceName = findViewById(R.id.tvDeviceName);
        tvSelectedCount = findViewById(R.id.tvSelectedCount);
        tvLoadingStatus = findViewById(R.id.tvLoadingStatus);
        recyclerView = findViewById(R.id.recyclerView);
    }

    private void getIntentData() {
        deviceId = getIntent().getStringExtra("device_id");
        deviceName = getIntent().getStringExtra("device_name");
        shareKey = getIntent().getStringExtra("share_key");
        int appCount = getIntent().getIntExtra("app_count", 0);
        isEditMode = getIntent().getBooleanExtra("edit_mode", false);

        if (isEditMode) {
            tvTitle.setText("Edit Focus Mode Preset");
            btnSavePreset.setText("Update Preset");
            existingPreset = presetManager.getPreset(deviceId);
        } else {
            tvTitle.setText("Create Focus Mode Preset");
            btnSavePreset.setText("Save & Activate Preset");
        }

        tvDeviceName.setText("Device: " + deviceName + " (" + appCount + " apps)");
        tvSelectedCount.setText("Loading apps...");
    }

    private void loadAppsFromFirebase() {
        tvLoadingStatus.setText("Loading apps from device...");
        btnSavePreset.setEnabled(false);

        childDeviceManager.getDeviceAppsFromShareKey(shareKey, deviceId, new ChildDeviceManager.OnAppsLoadedListener() {
            @Override
            public void onAppsLoaded(List<AppInfo> apps) {
                runOnUiThread(() -> {
                    allApps = apps;
                    tvLoadingStatus.setText("");
                    setupRecyclerView();

                    // If edit mode, load existing selections
                    if (isEditMode && existingPreset != null) {
                        loadExistingSelections();
                    }

                    updateSelectedCount();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    tvLoadingStatus.setText("Failed to load apps: " + error);
                    Toast.makeText(PresetCreationActivity.this,
                            "Failed to load apps: " + error, Toast.LENGTH_LONG).show();

                    setResult(RESULT_CANCELED);
                    finish();
                });
            }
        });
    }

    private void setupRecyclerView() {
        if (allApps == null || allApps.isEmpty()) {
            tvSelectedCount.setText("No apps found");
            return;
        }

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SimplePresetAdapter(this, allApps);
        adapter.setOnSelectionChangedListener(this::updateSelectedCount);
        recyclerView.setAdapter(adapter);
    }

    private void loadExistingSelections() {
        if (existingPreset == null || adapter == null) return;

        // Pre-select apps that were previously blocked
        for (String blockedPackage : existingPreset.blockedAppPackages) {
            adapter.selectApp(blockedPackage);
        }

        Toast.makeText(this, "Loaded existing preset with " +
                        existingPreset.blockedAppPackages.size() + " blocked apps",
                Toast.LENGTH_SHORT).show();
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        btnSavePreset.setOnClickListener(v -> savePreset());
    }

    private void updateSelectedCount() {
        if (adapter == null) {
            btnSavePreset.setEnabled(false);
            return;
        }

        int selectedCount = adapter.getSelectedCount();
        int totalCount = allApps != null ? allApps.size() : 0;

        if (isEditMode) {
            tvSelectedCount.setText("Selected: " + selectedCount + " / " + totalCount + " apps to block");
        } else {
            tvSelectedCount.setText("Selected: " + selectedCount + " / " + totalCount + " apps to block");
        }

        btnSavePreset.setEnabled(selectedCount > 0);
    }

    private void savePreset() {
        List<String> appsToBlock = adapter.getSelectedAppPackages();

        if (appsToBlock.isEmpty()) {
            Toast.makeText(this, "Please select at least one app to block", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSavePreset.setEnabled(false);
        btnSavePreset.setText(isEditMode ? "Updating..." : "Saving...");

        FocusModePreset preset = new FocusModePreset(deviceId, deviceName, appsToBlock);

        if (isEditMode) {
            // Update existing preset
            presetManager.updatePreset(preset, new PresetManager.OnPresetSavedListener() {
                @Override
                public void onSuccess() {
                    runOnUiThread(() -> {
                        Toast.makeText(PresetCreationActivity.this,
                                "Preset updated successfully!\n" + appsToBlock.size() + " apps will be blocked",
                                Toast.LENGTH_LONG).show();

                        Intent resultIntent = new Intent();
                        resultIntent.putExtra("preset_created", true);
                        resultIntent.putExtra("blocked_apps", appsToBlock.size());
                        setResult(RESULT_OK, resultIntent);
                        finish();
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        Toast.makeText(PresetCreationActivity.this,
                                "Failed to update preset: " + error, Toast.LENGTH_SHORT).show();
                        btnSavePreset.setEnabled(true);
                        btnSavePreset.setText("Update Preset");
                    });
                }
            });
        } else {
            // Create new preset
            presetManager.savePreset(preset, new PresetManager.OnPresetSavedListener() {
                @Override
                public void onSuccess() {
                    // Activate the preset immediately for new presets
                    presetManager.activatePreset(deviceId, new PresetManager.OnPresetAppliedListener() {
                        @Override
                        public void onSuccess(int blockedApps, int totalApps) {
                            runOnUiThread(() -> {
                                Toast.makeText(PresetCreationActivity.this,
                                        "Preset created and activated!\n" + blockedApps + " apps will be blocked",
                                        Toast.LENGTH_LONG).show();

                                Intent resultIntent = new Intent();
                                resultIntent.putExtra("preset_created", true);
                                resultIntent.putExtra("blocked_apps", blockedApps);
                                setResult(RESULT_OK, resultIntent);
                                finish();
                            });
                        }

                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> {
                                Toast.makeText(PresetCreationActivity.this,
                                        "Preset saved but activation failed: " + error,
                                        Toast.LENGTH_LONG).show();

                                Intent resultIntent = new Intent();
                                resultIntent.putExtra("preset_created", false);
                                setResult(RESULT_OK, resultIntent);
                                finish();
                            });
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        Toast.makeText(PresetCreationActivity.this,
                                "Failed to save preset: " + error, Toast.LENGTH_SHORT).show();
                        btnSavePreset.setEnabled(true);
                        btnSavePreset.setText("Save & Activate Preset");
                    });
                }
            });
        }
    }
}
