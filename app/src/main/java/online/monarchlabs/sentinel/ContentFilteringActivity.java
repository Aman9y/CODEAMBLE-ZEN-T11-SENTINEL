package online.monarchlabs.sentinel;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Patterns;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;

public class ContentFilteringActivity extends BaseActivity {
    public static final String EXTRA_DEVICE_ID = "device_id";
    public static final String EXTRA_DEVICE_NAME = "device_name";

    private ConnectedDevicesManager connectedDevicesManager;
    private String deviceId;
    private String deviceName;

    private TextView tvDeviceName;
    private TextView tvStatus;
    private TextInputEditText etPrivateDnsHostname;
    private SwitchMaterial switchFilteringEnabled;
    private SwitchMaterial switchPorn;
    private SwitchMaterial switchGambling;
    private SwitchMaterial switchViolence;
    private SwitchMaterial switchSafeSearch;
    private SwitchMaterial switchYouTubeRestricted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_content_filtering);

        connectedDevicesManager = new ConnectedDevicesManager(this);
        deviceId = getIntent().getStringExtra(EXTRA_DEVICE_ID);
        deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);

        if (TextUtils.isEmpty(deviceId)) {
            deviceId = connectedDevicesManager.getCurrentDeviceId();
        }
        ChildDevice currentDevice = connectedDevicesManager.getDevice(deviceId);
        if (TextUtils.isEmpty(deviceName) && currentDevice != null) {
            deviceName = currentDevice.deviceName;
        }

        initializeViews();
        setupToolbar();
        bindDefaultState();
        setupClickListeners();
        loadSavedPolicy();
    }

    private void initializeViews() {
        tvDeviceName = findViewById(R.id.tvDeviceName);
        tvStatus = findViewById(R.id.tvStatus);
        etPrivateDnsHostname = findViewById(R.id.etPrivateDnsHostname);
        switchFilteringEnabled = findViewById(R.id.switchFilteringEnabled);
        switchPorn = findViewById(R.id.switchPorn);
        switchGambling = findViewById(R.id.switchGambling);
        switchViolence = findViewById(R.id.switchViolence);
        switchSafeSearch = findViewById(R.id.switchSafeSearch);
        switchYouTubeRestricted = findViewById(R.id.switchYouTubeRestricted);
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void bindDefaultState() {
        tvDeviceName.setText(TextUtils.isEmpty(deviceName) ? "Selected child device" : deviceName);
        if (TextUtils.isEmpty(deviceId)) {
            tvStatus.setText("Select a child device before saving filtering settings.");
        } else {
            tvStatus.setText("Ready for Private DNS setup.");
        }
        switchFilteringEnabled.setChecked(true);
        switchPorn.setChecked(true);
        switchGambling.setChecked(true);
        switchViolence.setChecked(true);
        switchSafeSearch.setChecked(true);
        switchYouTubeRestricted.setChecked(true);
    }

    private void setupClickListeners() {
        MaterialButton btnSave = findViewById(R.id.btnSaveFiltering);
        MaterialButton btnCopy = findViewById(R.id.btnCopyHostname);
        MaterialButton btnOpenPrivateDns = findViewById(R.id.btnOpenPrivateDns);

        btnSave.setOnClickListener(v -> savePolicy());
        btnCopy.setOnClickListener(v -> copyHostname());
        btnOpenPrivateDns.setOnClickListener(v -> openPrivateDnsSettings());
    }

    private void loadSavedPolicy() {
        if (TextUtils.isEmpty(deviceId)) {
            return;
        }
        FirebaseDatabase.getInstance().getReference()
                .child(FirebaseSchemaV2Repository.ROOT)
                .child("device_policies")
                .child(deviceId)
                .child("content_filtering")
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (!snapshot.exists()) {
                        return;
                    }
                    Boolean enabled = snapshot.child("enabled").getValue(Boolean.class);
                    String hostname = snapshot.child("providerHostname").getValue(String.class);
                    Boolean porn = snapshot.child("categories").child("porn").getValue(Boolean.class);
                    Boolean gambling = snapshot.child("categories").child("gambling").getValue(Boolean.class);
                    Boolean violence = snapshot.child("categories").child("violenceGore").getValue(Boolean.class);
                    Boolean safeSearch = snapshot.child("safeSearch").getValue(Boolean.class);
                    Boolean youtubeRestricted = snapshot.child("youtubeRestrictedMode").getValue(Boolean.class);

                    switchFilteringEnabled.setChecked(enabled == null || enabled);
                    etPrivateDnsHostname.setText(hostname == null ? "" : hostname);
                    switchPorn.setChecked(porn == null || porn);
                    switchGambling.setChecked(gambling == null || gambling);
                    switchViolence.setChecked(violence == null || violence);
                    switchSafeSearch.setChecked(safeSearch == null || safeSearch);
                    switchYouTubeRestricted.setChecked(youtubeRestricted == null || youtubeRestricted);
                    tvStatus.setText("Saved filtering policy loaded.");
                })
                .addOnFailureListener(e -> tvStatus.setText("Could not load saved filtering policy."));
    }

    private void savePolicy() {
        if (TextUtils.isEmpty(deviceId)) {
            Toast.makeText(this, "Please select a child device first.", Toast.LENGTH_SHORT).show();
            return;
        }

        String hostname = normalizedHostname();
        if (switchFilteringEnabled.isChecked() && !isValidHostname(hostname)) {
            etPrivateDnsHostname.setError("Enter a valid Private DNS hostname");
            return;
        }

        Map<String, Object> categories = new HashMap<>();
        categories.put("porn", switchPorn.isChecked());
        categories.put("gambling", switchGambling.isChecked());
        categories.put("violenceGore", switchViolence.isChecked());

        Map<String, Object> policy = new HashMap<>();
        policy.put("enabled", switchFilteringEnabled.isChecked());
        policy.put("provider", "private_dns");
        policy.put("providerHostname", hostname);
        policy.put("categories", categories);
        policy.put("safeSearch", switchSafeSearch.isChecked());
        policy.put("youtubeRestrictedMode", switchYouTubeRestricted.isChecked());
        policy.put("setupMode", "manual_android_private_dns");
        policy.put("updatedAt", ServerValue.TIMESTAMP);

        FirebaseSchemaV2Repository.syncContentFilteringPolicy(deviceId, policy)
                .addOnSuccessListener(unused -> {
                    tvStatus.setText("Filtering policy saved. Copy the hostname and set it in Android Private DNS.");
                    Toast.makeText(this, "Content filtering saved", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> Toast.makeText(this, "Could not save filtering policy", Toast.LENGTH_LONG).show());
    }

    private void copyHostname() {
        String hostname = normalizedHostname();
        if (!isValidHostname(hostname)) {
            etPrivateDnsHostname.setError("Enter a valid hostname first");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Private DNS hostname", hostname));
        Toast.makeText(this, "Private DNS hostname copied", Toast.LENGTH_SHORT).show();
    }

    private void openPrivateDnsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            Toast.makeText(this, "Open Private DNS and paste the hostname.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private String normalizedHostname() {
        if (etPrivateDnsHostname.getText() == null) {
            return "";
        }
        String value = etPrivateDnsHostname.getText().toString().trim().toLowerCase(Locale.US);
        value = value.replace("https://", "").replace("http://", "");
        int slash = value.indexOf('/');
        return slash >= 0 ? value.substring(0, slash) : value;
    }

    private boolean isValidHostname(String hostname) {
        return !TextUtils.isEmpty(hostname)
                && hostname.contains(".")
                && !hostname.contains(" ")
                && Patterns.DOMAIN_NAME.matcher(hostname).matches();
    }
}
