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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;
import online.monarchlabs.sentinel.services.NextDnsWorkerService;

public class ContentFilteringActivity extends BaseActivity {
    public static final String EXTRA_DEVICE_ID = "device_id";
    public static final String EXTRA_DEVICE_NAME = "device_name";

    private ConnectedDevicesManager connectedDevicesManager;
    private String deviceId;
    private String deviceName;

    private TextView tvDeviceName;
    private TextView tvStatus;
    private TextInputEditText etPrivateDnsHostname;
    private TextInputEditText etCustomDomain;
    private TextView tvCustomDenylist;
    private SwitchMaterial switchFilteringEnabled;
    private SwitchMaterial switchPorn;
    private SwitchMaterial switchGambling;
    private SwitchMaterial switchViolence;
    private SwitchMaterial switchSafeSearch;
    private SwitchMaterial switchYouTubeRestricted;
    private final List<String> customDenylist = new ArrayList<>();

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
        etCustomDomain = findViewById(R.id.etCustomDomain);
        tvCustomDenylist = findViewById(R.id.tvCustomDenylist);
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
        MaterialButton btnAddDomain = findViewById(R.id.btnAddDomain);
        MaterialButton btnRemoveLastDomain = findViewById(R.id.btnRemoveLastDomain);

        btnSave.setOnClickListener(v -> savePolicy());
        btnCopy.setOnClickListener(v -> copyHostname());
        btnOpenPrivateDns.setOnClickListener(v -> openPrivateDnsSettings());
        btnAddDomain.setOnClickListener(v -> addCustomDomain());
        btnRemoveLastDomain.setOnClickListener(v -> removeLastCustomDomain());
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
                    customDenylist.clear();
                    for (com.google.firebase.database.DataSnapshot domainSnapshot
                            : snapshot.child("customDenylist").getChildren()) {
                        String domain = domainSnapshot.child("domain").getValue(String.class);
                        if (!TextUtils.isEmpty(domain)) {
                            customDenylist.add(domain);
                        }
                    }
                    Collections.sort(customDenylist);
                    renderCustomDenylist();
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
        policy.put("nextDnsProfileId", extractNextDnsProfileId(hostname));
        policy.put("categories", categories);
        policy.put("customDenylist", customDenylistMap());
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

    private void addCustomDomain() {
        if (TextUtils.isEmpty(deviceId)) {
            Toast.makeText(this, "Please select a child device first.", Toast.LENGTH_SHORT).show();
            return;
        }
        String profileId = extractNextDnsProfileId(normalizedHostname());
        if (TextUtils.isEmpty(profileId)) {
            etPrivateDnsHostname.setError("Enter a NextDNS hostname first");
            return;
        }
        String domain = normalizedDomain();
        if (!isValidHostname(domain)) {
            etCustomDomain.setError("Enter a valid domain");
            return;
        }

        tvStatus.setText("Adding domain to NextDNS...");
        new NextDnsWorkerService(this).addDenylistDomain(deviceId, profileId, domain)
                .thenAccept(result -> runOnUiThread(() -> {
                    if (!result.success) {
                        tvStatus.setText(result.message.isEmpty() ? "Could not update NextDNS." : result.message);
                        Toast.makeText(this, tvStatus.getText(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    addDomainLocally(domain);
                    savePolicy();
                    etCustomDomain.setText("");
                    tvStatus.setText(domain + " added to the custom block list.");
                }));
    }

    private void removeLastCustomDomain() {
        if (customDenylist.isEmpty()) {
            Toast.makeText(this, "No custom domains to remove.", Toast.LENGTH_SHORT).show();
            return;
        }
        String profileId = extractNextDnsProfileId(normalizedHostname());
        if (TextUtils.isEmpty(profileId)) {
            etPrivateDnsHostname.setError("Enter a NextDNS hostname first");
            return;
        }
        String domain = customDenylist.get(customDenylist.size() - 1);
        tvStatus.setText("Removing domain from NextDNS...");
        new NextDnsWorkerService(this).removeDenylistDomain(deviceId, profileId, domain)
                .thenAccept(result -> runOnUiThread(() -> {
                    if (!result.success) {
                        tvStatus.setText(result.message.isEmpty() ? "Could not update NextDNS." : result.message);
                        Toast.makeText(this, tvStatus.getText(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    customDenylist.remove(domain);
                    renderCustomDenylist();
                    savePolicy();
                    tvStatus.setText(domain + " removed from the custom block list.");
                }));
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

    private String normalizedDomain() {
        if (etCustomDomain.getText() == null) {
            return "";
        }
        String value = etCustomDomain.getText().toString().trim().toLowerCase(Locale.US);
        value = value.replace("https://", "").replace("http://", "");
        int slash = value.indexOf('/');
        return slash >= 0 ? value.substring(0, slash) : value;
    }

    private String extractNextDnsProfileId(String hostname) {
        if (TextUtils.isEmpty(hostname) || !hostname.endsWith(".dns.nextdns.io")) {
            return "";
        }
        return hostname.substring(0, hostname.indexOf(".dns.nextdns.io"));
    }

    private void addDomainLocally(String domain) {
        LinkedHashSet<String> domains = new LinkedHashSet<>(customDenylist);
        domains.add(domain);
        customDenylist.clear();
        customDenylist.addAll(domains);
        Collections.sort(customDenylist);
        renderCustomDenylist();
    }

    private void renderCustomDenylist() {
        if (customDenylist.isEmpty()) {
            tvCustomDenylist.setText("No custom domains added yet.");
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (String domain : customDenylist) {
            builder.append(domain).append('\n');
        }
        tvCustomDenylist.setText(builder.toString().trim());
    }

    private Map<String, Object> customDenylistMap() {
        Map<String, Object> values = new HashMap<>();
        for (String domain : customDenylist) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("domain", domain);
            entry.put("active", true);
            values.put(domain.replace(".", "_"), entry);
        }
        return values;
    }

    private boolean isValidHostname(String hostname) {
        return !TextUtils.isEmpty(hostname)
                && hostname.contains(".")
                && !hostname.contains(" ")
                && Patterns.DOMAIN_NAME.matcher(hostname).matches();
    }
}
