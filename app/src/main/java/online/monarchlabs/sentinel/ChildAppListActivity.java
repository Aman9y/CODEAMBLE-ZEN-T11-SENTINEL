package online.monarchlabs.sentinel;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import androidx.appcompat.app.AlertDialog;
import android.content.pm.ActivityInfo;
import java.util.Collections;

public class ChildAppListActivity extends BaseActivity {
    private static final String TAG = "ChildAppListActivity";
    
    private RecyclerView recyclerView;
    private ChildAppListAdapter adapter;
    private List<AppInfo> appList = new ArrayList<>();
    private List<AppInfo> selectedApps = new ArrayList<>();
    private List<AppInfo> preselectedApps = new ArrayList<>(); // Apps that should be pre-selected
    private Set<String> preselectedPackageNames = new HashSet<>(); // Package names that should be pre-selected
    private FloatingActionButton fabConfirm;
    private EditText etSearchApps;
    private DatabaseReference deviceRef;
    private ValueEventListener deviceListener;
    private String deviceId;
    private String deviceName;
    private boolean blockingSelection;
    
    // 🆕 Search functionality
    private List<AppInfo> allAppsList = new ArrayList<>(); // Full list for filtering
    private Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;
    private static final int SEARCH_DEBOUNCE_MS = 300;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_app_list);
        
        // Get device info from intent
        deviceId = getIntent().getStringExtra("deviceId");
        deviceName = getIntent().getStringExtra("deviceName");
        blockingSelection = "blocking".equals(
                getIntent().getStringExtra("selectionPurpose"));
        
        // Get preselected apps from intent (for timer/blocking selection)
        ArrayList<AppInfo> preselected = getIntent().getParcelableArrayListExtra("preselected_apps");
        if (preselected != null) {
            for (AppInfo app : preselected) {
                if (!blockingSelection
                        || !AppBlockingPolicy.isUnblockable(app.packageName)) {
                    preselectedApps.add(app);
                    selectedApps.add(app);
                }
            }
            Log.d(TAG, "Got " + preselectedApps.size() + " preselected apps");
        }
        
        // Also support preselected package names (for timer management)
        ArrayList<String> preselectedPackages = getIntent().getStringArrayListExtra("preselected_packages");
        if (preselectedPackages != null && !preselectedPackages.isEmpty()) {
            for (String packageName : preselectedPackages) {
                if (!blockingSelection
                        || !AppBlockingPolicy.isUnblockable(packageName)) {
                    preselectedPackageNames.add(packageName);
                }
            }
            Log.d(TAG, "Got " + preselectedPackages.size() + " preselected package names: " + preselectedPackages.toString());
        }
        
        if (deviceId == null || deviceId.isEmpty()) {
            Toast.makeText(this, "No device selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        initViews();
        setupRecyclerView();
        loadChildDeviceApps();
        
        Log.d(TAG, "Loading apps for device: " + deviceId + " (" + deviceName + ")");
    }
    
    private void initViews() {
        recyclerView = findViewById(R.id.recyclerViewApps);
        fabConfirm = findViewById(R.id.fabConfirm);
        etSearchApps = findViewById(R.id.etSearchApps);
        
        // Set title
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(deviceName + " - Apps");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        
        fabConfirm.setOnClickListener(v -> confirmSelection());
        
        // 🆕 Setup search functionality with debounce
        if (etSearchApps != null) {
            etSearchApps.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    // Cancel previous search request
                    if (searchRunnable != null) {
                        searchHandler.removeCallbacks(searchRunnable);
                    }
                    
                    // Debounce - wait before filtering
                    searchRunnable = () -> filterApps(s.toString().trim());
                    searchHandler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
    }
    
    /**
     * 🆕 Filter apps based on search query
     */
    private void filterApps(String query) {
        if (allAppsList.isEmpty()) {
            // If allAppsList not populated yet, use current appList
            allAppsList.addAll(appList);
        }
        
        appList.clear();
        
        if (query.isEmpty()) {
            // Show all apps when search is empty
            appList.addAll(allAppsList);
        } else {
            String lowerQuery = query.toLowerCase();
            for (AppInfo app : allAppsList) {
                // Search in app name and package name
                boolean matchesName = app.name != null && 
                                     app.name.toLowerCase().contains(lowerQuery);
                boolean matchesPackage = app.packageName != null && 
                                        app.packageName.toLowerCase().contains(lowerQuery);
                
                if (matchesName || matchesPackage) {
                    appList.add(app);
                }
            }
        }
        
        adapter.notifyDataSetChanged();
        Log.d(TAG, "🔍 Filtered apps: " + appList.size() + " results for '" + query + "'");
    }
    
    private void setupRecyclerView() {
        adapter = new ChildAppListAdapter(appList, new ChildAppListAdapter.OnAppSelectionListener() {
            @Override
            public void onAppSelected(AppInfo appInfo, boolean isSelected) {
                if (isSelected) {
                    if (!selectedApps.contains(appInfo)) {
                        selectedApps.add(appInfo);
                    }
                } else {
                    selectedApps.remove(appInfo);
                }
                
                updateFabVisibility();
                Log.d(TAG, "App " + appInfo.name + " selection changed: " + isSelected);
            }
        });
        
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }
    
    private void updateFabVisibility() {
        if (selectedApps.size() > 0) {
            fabConfirm.setVisibility(View.VISIBLE);
        } else {
            fabConfirm.setVisibility(View.GONE);
        }
    }
    
    private void loadChildDeviceApps() {
        Log.d(TAG, "Loading v2 app inventory for device: " + deviceId);
        Toast.makeText(this, "Loading apps from " + deviceName + "...",
                Toast.LENGTH_SHORT).show();

        deviceRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_installs")
                .child(deviceId)
                .child("apps");
        deviceListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                appList.clear();
                for (DataSnapshot appSnapshot : snapshot.getChildren()) {
                    Object raw = appSnapshot.getValue();
                    if (raw instanceof Map) {
                        @SuppressWarnings("unchecked")
                        AppInfo app = createAppInfoFromMap((Map<String, Object>) raw);
                        if (app != null) {
                            appList.add(app);
                        }
                    }
                }
                if (appList.isEmpty()) {
                    showNoAppsFoundError();
                } else {
                    processLoadedApps();
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "v2 app inventory cancelled: " + error.getMessage());
                showNoAppsFoundError();
            }
        };
        deviceRef.addValueEventListener(deviceListener);
    }
    private AppInfo createAppInfoFromMap(Map<String, Object> appData) {
        try {
            String packageName = (String) appData.get("packageName");
            String name = (String) appData.get("appName");
            if (name == null) {
                name = (String) appData.get("name");
            }
            
            if (packageName == null || packageName.isEmpty() || name == null || name.isEmpty()) {
                return null;
            }
            
            AppInfo appInfo = new AppInfo();
            appInfo.packageName = packageName;
            appInfo.name = name;
            appInfo.category = (String) appData.get("category");
            appInfo.isSystemApp = Boolean.TRUE.equals(appData.get("isSystemApp"));
            appInfo.isSelected = false;
            
            // Handle versionCode - could be Long or Integer
            Object versionCodeObj = appData.get("versionCode");
            if (versionCodeObj instanceof Number) {
                appInfo.versionCode = ((Number) versionCodeObj).longValue();
            }
            
            appInfo.versionName = (String) appData.get("versionName");
            
            return appInfo;
        } catch (Exception e) {
            Log.e(TAG, "Error creating AppInfo from map: " + e.getMessage());
            return null;
        }
    }
    
    private void processLoadedApps() {
        runOnUiThread(() -> {
            if (appList.isEmpty()) {
                showNoAppsFoundError();
                return;
            }
            
            // Only filter out our own app - keep ALL other apps including system apps
            List<AppInfo> filteredApps = new ArrayList<>();
            for (AppInfo app : appList) {
                // Skip our own app only
                if (app.packageName.equals(getPackageName()) || 
                    app.packageName.contains("online.monarchlabs.sentinel")) {
                    continue;
                }
                if (blockingSelection
                        && AppBlockingPolicy.isUnblockable(app.packageName)) {
                    continue;
                }
                
                // KEEP ALL OTHER APPS - both user-installed and system apps
                // Users should be able to control ALL apps including Chrome, YouTube, Settings, etc.
                filteredApps.add(app);
            }
            
            appList.clear();
            appList.addAll(filteredApps);
            
            // Sort apps by name for better user experience
            Collections.sort(appList, (a, b) -> {
                String nameA = a.name != null ? a.name : "";
                String nameB = b.name != null ? b.name : "";
                return nameA.compareToIgnoreCase(nameB);
            });
            
            // 🆕 Populate allAppsList for search filtering
            allAppsList.clear();
            allAppsList.addAll(appList);
            
            // Mark preselected apps as selected
            markPreselectedApps();
            
            adapter.notifyDataSetChanged();
            
            Log.d(TAG, "✅ Successfully loaded and displayed " + appList.size() + " apps");
            Toast.makeText(this, "✅ Loaded " + appList.size() + " apps from " + deviceName, Toast.LENGTH_SHORT).show();
        });
    }
    
    private boolean isUserFriendlySystemApp(String packageName) {
        // Allow some user-friendly system apps that users might want to block
        return packageName.equals("com.android.chrome") ||
               packageName.equals("com.google.android.googlequicksearchbox") ||
               packageName.equals("com.android.vending") ||
               packageName.contains("youtube") ||
               packageName.contains("gmail") ||
               packageName.contains("maps");
    }
    
    private void showNoAppsFoundError() {
        runOnUiThread(() -> {
            Log.e(TAG, "❌ NO APPS FOUND FOR DEVICE: " + deviceId);
            
            String errorMessage = "❌ No apps found for " + deviceName + "\n\n" +
                                "This could happen if:\n" +
                                "• The child device hasn't connected properly\n" +
                                "• The app list hasn't been uploaded yet\n" +
                                "• There's a connection issue\n\n" +
                                "Try:\n" +
                                "1. Make sure the child device is connected\n" +
                                "2. Have the child re-scan the QR code\n" +
                                "3. Check internet connection on both devices";
            
            new AlertDialog.Builder(this)
                .setTitle("⚠️ No Apps Found")
                .setMessage(errorMessage)
                .setPositiveButton("🔄 Retry", (dialog, which) -> {
                    appList.clear();
                    adapter.notifyDataSetChanged();
                    loadChildDeviceApps();
                })
                .setNegativeButton("❌ Cancel", (dialog, which) -> {
                    finish();
                })
                .setCancelable(false)
                .show();
        });
    }
    
    private void markPreselectedApps() {
        if (preselectedApps.isEmpty() && preselectedPackageNames.isEmpty()) {
            return;
        }
        
        // Create a set of preselected package names for fast lookup
        Set<String> preselectedPackages = new HashSet<>();
        
        // Add package names from AppInfo objects
        for (AppInfo preselected : preselectedApps) {
            preselectedPackages.add(preselected.packageName);
        }
        
        // Add package names from direct package name list
        preselectedPackages.addAll(preselectedPackageNames);
        
        Log.d(TAG, "Marking apps as preselected from package names: " + preselectedPackages.toString());
        
        // Mark matching apps as selected
        for (AppInfo app : appList) {
            if (preselectedPackages.contains(app.packageName)) {
                app.isSelected = true;
                if (!selectedApps.contains(app)) {
                    selectedApps.add(app);
                }
                Log.d(TAG, "Pre-selected app: " + app.name + " (" + app.packageName + ")");
            }
        }
        
        // Update FAB visibility
        updateFabVisibility();
        
        Log.d(TAG, "Marked " + selectedApps.size() + " apps as pre-selected");
    }
    
    private void confirmSelection() {
        if (selectedApps.isEmpty()) {
            Toast.makeText(this, "Please select at least one app", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Return selected apps to parent activity
        Intent resultIntent = new Intent();
        resultIntent.putParcelableArrayListExtra("selected_apps", new ArrayList<>(selectedApps));
        resultIntent.putExtra("device_id", deviceId);
        resultIntent.putExtra("device_name", deviceName);
        setResult(RESULT_OK, resultIntent);
        
        Toast.makeText(this, "Selected " + selectedApps.size() + " apps for monitoring", Toast.LENGTH_SHORT).show();
        finish();
    }
    
    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (deviceRef != null && deviceListener != null) {
            deviceRef.removeEventListener(deviceListener);
        }
    }
}
