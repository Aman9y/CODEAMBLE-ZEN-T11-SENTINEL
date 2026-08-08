package online.monarchlabs.sentinel;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PresetManagementActivity extends BaseActivity {
    private Button btnBack;
    private TextView tvTitle, tvStatus;
    private RecyclerView recyclerView;

    private PresetManager presetManager;
    private PresetListAdapter adapter;
    private List<FocusModePreset> allPresets;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preset_management);

        initViews();
        setupClickListeners();
        presetManager = new PresetManager(this);
        loadAllPresets();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        tvTitle = findViewById(R.id.tvTitle);
        tvStatus = findViewById(R.id.tvStatus);
        recyclerView = findViewById(R.id.recyclerView);

        tvTitle.setText("Preset Management");
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());
    }

    private void loadAllPresets() {
        allPresets = presetManager.getAllPresets();

        if (allPresets.isEmpty()) {
            tvStatus.setText("No presets found. Create presets for your child devices.");
            return;
        }

        tvStatus.setText("Found " + allPresets.size() + " preset(s)");
        setupRecyclerView();
    }

    private void setupRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PresetListAdapter(this, allPresets);

        adapter.setOnPresetActionListener(new PresetListAdapter.OnPresetActionListener() {
            @Override
            public void onTogglePreset(FocusModePreset preset) {
                togglePreset(preset);
            }

            @Override
            public void onDeletePreset(FocusModePreset preset) {
                confirmDeletePreset(preset);
            }

            @Override
            public void onViewDetails(FocusModePreset preset) {
                showPresetDetails(preset);
            }
        });
        recyclerView.setAdapter(adapter);
    }

    private void togglePreset(FocusModePreset preset) {
        boolean isCurrentlyActive = presetManager.isFocusModeActive(preset.deviceId);

        if (isCurrentlyActive) {
            // Deactivate
            presetManager.deactivatePreset(preset.deviceId, new PresetManager.OnPresetAppliedListener() {
                @Override
                public void onSuccess(int unblockedApps, int totalApps) {
                    runOnUiThread(() -> {
                        Toast.makeText(PresetManagementActivity.this,
                                "Deactivated preset for " + preset.deviceName,
                                Toast.LENGTH_SHORT).show();
                        refreshPresets();
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        Toast.makeText(PresetManagementActivity.this,
                                "Failed to deactivate: " + error, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        } else {
            // Activate
            presetManager.activatePreset(preset.deviceId, new PresetManager.OnPresetAppliedListener() {
                @Override
                public void onSuccess(int blockedApps, int totalApps) {
                    runOnUiThread(() -> {
                        Toast.makeText(PresetManagementActivity.this,
                                "Activated preset for " + preset.deviceName + " - " + blockedApps + " apps blocked",
                                Toast.LENGTH_SHORT).show();
                        refreshPresets();
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        Toast.makeText(PresetManagementActivity.this,
                                "Failed to activate: " + error, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        }
    }

    private void confirmDeletePreset(FocusModePreset preset) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Preset")
                .setMessage("Are you sure you want to delete the preset for " + preset.deviceName + "?\n\n" +
                        "This will remove " + preset.blockedAppPackages.size() + " app restrictions.\n" +
                        "This action cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> deletePreset(preset))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deletePreset(FocusModePreset preset) {
        presetManager.deletePreset(preset.deviceId, new PresetManager.OnPresetSavedListener() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(PresetManagementActivity.this,
                            "Preset deleted for " + preset.deviceName, Toast.LENGTH_SHORT).show();
                    refreshPresets();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(PresetManagementActivity.this,
                            "Failed to delete preset: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showPresetDetails(FocusModePreset preset) {
        PresetManager.PresetStats stats = presetManager.getPresetStats(preset.deviceId);
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());

        StringBuilder details = new StringBuilder();
        details.append("Device: ").append(preset.deviceName).append("\n");
        details.append("Device ID: ").append(preset.deviceId).append("\n");
        details.append("Blocked Apps: ").append(preset.blockedAppPackages.size()).append("\n");
        details.append("Status: ").append(stats.isActive ? "🟢 Active" : "🔴 Inactive").append("\n");
        details.append("Created: ").append(sdf.format(new Date(preset.createdTimestamp))).append("\n");

        if (preset.lastActivatedTimestamp > 0) {
            details.append("Last Activated: ").append(sdf.format(new Date(preset.lastActivatedTimestamp))).append("\n");
        }

        details.append("\nBlocked Apps:\n");
        for (int i = 0; i < Math.min(preset.blockedAppPackages.size(), 10); i++) {
            details.append("• ").append(preset.blockedAppPackages.get(i)).append("\n");
        }

        if (preset.blockedAppPackages.size() > 10) {
            details.append("... and ").append(preset.blockedAppPackages.size() - 10).append(" more");
        }

        new AlertDialog.Builder(this)
                .setTitle("Preset Details")
                .setMessage(details.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void refreshPresets() {
        loadAllPresets();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPresets();
    }
}
