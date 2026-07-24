package online.monarchlabs.sentinel;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;

import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;
import online.monarchlabs.sentinel.data.StudyModeContract;
import online.monarchlabs.sentinel.data.StudyModePolicyRepository;
import online.monarchlabs.sentinel.models.StudyModePolicy;
import online.monarchlabs.sentinel.utils.AppCategorizer;
import online.monarchlabs.sentinel.utils.StudyModeDraftStore;
import online.monarchlabs.sentinel.utils.StudyModeScheduleEvaluator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class StudyModeEditActivity extends BaseActivity {
    public static final String EXTRA_CHILD_DEVICE_ID = "childDeviceId";
    public static final String EXTRA_CHILD_NAME = "childName";

    private static final String[] DAY_VALUES = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
    private static final String[] DAY_LABELS = {"S", "M", "T", "W", "T", "F", "S"};

    private final Map<String, List<CategoryApp>> categoryApps = new LinkedHashMap<>();

    private String childDeviceId;
    private StudyModePolicy policy;
    private SwitchCompat switchStudyEnabled;
    private TextView btnSave;
    private LinearLayout layoutTimeSlots;
    private LinearLayout layoutDayChips;
    private LinearLayout layoutRestrictions;
    private LinearLayout layoutReview;
    private boolean inventoryLoaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_study_mode_edit);

        childDeviceId = getIntent().getStringExtra(EXTRA_CHILD_DEVICE_ID);
        policy = StudyModeDraftStore.load(this, childDeviceId);
        initializeCategoryApps();

        switchStudyEnabled = findViewById(R.id.switchStudyEnabled);
        layoutTimeSlots = findViewById(R.id.layoutTimeSlots);
        layoutDayChips = findViewById(R.id.layoutDayChips);
        layoutRestrictions = findViewById(R.id.layoutRestrictions);
        layoutReview = findViewById(R.id.layoutReview);

        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        btnSave = findViewById(R.id.btnSave);
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> saveDraft());
        }

        View btnAddSlot = findViewById(R.id.btnAddSlot);
        if (btnAddSlot != null) {
            btnAddSlot.setOnClickListener(v -> addSlot());
        }

        if (switchStudyEnabled != null) {
            switchStudyEnabled.setChecked(policy.enabled);
            switchStudyEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                policy.enabled = isChecked;
                renderReview();
            });
        }

        ensureCategoryState();
        renderAll();
        loadRemotePolicy();
        loadChildInventory();
    }

    private void loadRemotePolicy() {
        if (isBlank(childDeviceId)) {
            return;
        }
        StudyModePolicyRepository.read(childDeviceId)
                .addOnSuccessListener(snapshot -> {
                    StudyModePolicy remotePolicy = StudyModePolicyRepository.fromSnapshot(snapshot);
                    if (remotePolicy == null) {
                        return;
                    }
                    policy = remotePolicy;
                    ensureCategoryState();
                    if (switchStudyEnabled != null) {
                        switchStudyEnabled.setChecked(policy.enabled);
                    }
                    syncEnabledCategoriesWithInventory();
                    StudyModeDraftStore.save(this, childDeviceId, policy);
                    renderAll();
                });
    }

    private void loadChildInventory() {
        if (isBlank(childDeviceId)) {
            inventoryLoaded = true;
            renderRestrictions();
            renderReview();
            return;
        }

        FirebaseDatabase.getInstance().getReference()
                .child(FirebaseSchemaV2Repository.ROOT)
                .child("device_installs")
                .child(childDeviceId)
                .child("apps")
                .get()
                .addOnSuccessListener(snapshot -> {
                    initializeCategoryApps();
                    for (DataSnapshot appSnapshot : snapshot.getChildren()) {
                        CategoryApp app = parseInventoryApp(appSnapshot);
                        if (app == null) {
                            continue;
                        }
                        String categoryId = toStudyCategoryId(app.category, app.packageName, app.name);
                        if (categoryId == null) {
                            continue;
                        }
                        categoryApps.get(categoryId).add(app);
                    }
                    sortCategoryApps();
                    inventoryLoaded = true;
                    syncEnabledCategoriesWithInventory();
                    renderRestrictions();
                    renderReview();
                })
                .addOnFailureListener(error -> {
                    inventoryLoaded = true;
                    Toast.makeText(this, "Could not load child apps", Toast.LENGTH_SHORT).show();
                    renderRestrictions();
                    renderReview();
                });
    }

    private CategoryApp parseInventoryApp(DataSnapshot appSnapshot) {
        Map<?, ?> data = asMap(appSnapshot.getValue());
        if (data == null) {
            return null;
        }
        String packageName = stringValue(data.get("packageName"));
        if (isBlank(packageName)) {
            packageName = appSnapshot.getKey();
        }
        String appName = stringValue(data.get("appName"));
        if (isBlank(appName)) {
            appName = stringValue(data.get("name"));
        }
        if (isBlank(packageName) || isBlank(appName) || AppBlockingPolicy.isUnblockable(packageName)) {
            return null;
        }
        return new CategoryApp(
                appName.trim(),
                packageName.trim(),
                stringValue(data.get("category")),
                stringValue(data.get("iconBase64")));
    }

    private Map<?, ?> asMap(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : null;
    }

    private String toStudyCategoryId(String storedCategory, String packageName, String appName) {
        String normalized = storedCategory == null ? "" : storedCategory.toLowerCase(Locale.US);
        if (normalized.contains("social")) {
            return StudyModeContract.CATEGORY_SOCIAL;
        }
        if (normalized.contains("game")) {
            return StudyModeContract.CATEGORY_GAMES;
        }
        if (normalized.contains("entertainment")) {
            return StudyModeContract.CATEGORY_ENTERTAINMENT;
        }

        AppCategorizer.AppCategory category = AppCategorizer.getCategory(packageName, appName);
        switch (category) {
            case SOCIAL:
                return StudyModeContract.CATEGORY_SOCIAL;
            case GAMES:
                return StudyModeContract.CATEGORY_GAMES;
            case ENTERTAINMENT:
                return StudyModeContract.CATEGORY_ENTERTAINMENT;
            default:
                return null;
        }
    }

    private void sortCategoryApps() {
        for (List<CategoryApp> apps : categoryApps.values()) {
            Collections.sort(apps, (first, second) -> first.name.compareToIgnoreCase(second.name));
        }
    }
    private void renderAll() {
        renderSlots();
        renderDays();
        renderRestrictions();
        renderReview();
    }

    private void renderSlots() {
        layoutTimeSlots.removeAllViews();
        if (policy.timeSlots == null) {
            policy.timeSlots = new ArrayList<>();
        }
        if (policy.timeSlots.isEmpty()) {
            policy.timeSlots.add(new StudyModePolicy.TimeSlot("15:00", "18:00"));
        }
        for (int i = 0; i < policy.timeSlots.size(); i++) {
            layoutTimeSlots.addView(createSlotRow(i));
        }
    }

    private View createSlotRow(int index) {
        StudyModePolicy.TimeSlot slot = policy.timeSlots.get(index);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, index == 0 ? 0 : dp(10), 0, 0);

        TextView start = createTimeButton("START", formatTime(slot.start));
        start.setOnClickListener(v -> pickTime(slot.start, value -> {
            slot.start = value;
            renderSlots();
            renderReview();
        }));

        TextView arrow = new TextView(this);
        arrow.setText("->");
        arrow.setGravity(Gravity.CENTER);
        arrow.setTextColor(getColor(R.color.modern_grey_600));
        arrow.setTextSize(18);
        row.addView(start, new LinearLayout.LayoutParams(0, dp(64), 1));
        row.addView(arrow, new LinearLayout.LayoutParams(dp(36), dp(64)));

        TextView end = createTimeButton("END", formatTime(slot.end));
        end.setOnClickListener(v -> pickTime(slot.end, value -> {
            slot.end = value;
            renderSlots();
            renderReview();
        }));
        row.addView(end, new LinearLayout.LayoutParams(0, dp(64), 1));

        ImageButton delete = new ImageButton(this);
        delete.setImageResource(R.drawable.ic_delete);
        delete.setColorFilter(getColor(R.color.modern_red_500));
        delete.setBackgroundColor(getColor(android.R.color.transparent));
        delete.setContentDescription("Delete time slot");
        delete.setVisibility(policy.timeSlots.size() > 1 ? View.VISIBLE : View.INVISIBLE);
        delete.setOnClickListener(v -> {
            policy.timeSlots.remove(index);
            renderSlots();
            renderReview();
        });
        row.addView(delete, new LinearLayout.LayoutParams(dp(42), dp(64)));
        return row;
    }

    private TextView createTimeButton(String label, String time) {
        TextView view = new TextView(this);
        view.setBackgroundResource(R.drawable.bg_mode_time_pill);
        view.setGravity(Gravity.CENTER);
        view.setText(label + "\n" + time);
        view.setTextColor(getColor(R.color.modern_blue_700));
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private void addSlot() {
        if (policy.timeSlots.size() >= StudyModeContract.MAX_TIME_SLOTS) {
            Toast.makeText(this, "Maximum 4 time slots allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        policy.timeSlots.add(new StudyModePolicy.TimeSlot("17:00", "18:00"));
        renderSlots();
        renderReview();
    }

    private void renderDays() {
        layoutDayChips.removeAllViews();
        if (policy.days == null) {
            policy.days = new ArrayList<>();
        }
        for (int i = 0; i < DAY_VALUES.length; i++) {
            String day = DAY_VALUES[i];
            TextView chip = new TextView(this);
            chip.setText(DAY_LABELS[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(14);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            updateDayChip(chip, policy.days.contains(day));
            chip.setOnClickListener(v -> {
                if (policy.days.contains(day)) {
                    policy.days.remove(day);
                } else {
                    policy.days.add(day);
                    sortDays();
                }
                updateDayChip(chip, policy.days.contains(day));
                renderReview();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(38), dp(38));
            params.setMargins(0, 0, dp(8), 0);
            layoutDayChips.addView(chip, params);
        }
    }

    private void updateDayChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.bg_mode_day_selected : R.drawable.bg_mode_day_unselected);
        chip.setTextColor(getColor(selected ? R.color.white : R.color.modern_grey_700));
    }

    private void renderRestrictions() {
        layoutRestrictions.removeAllViews();
        addCategoryRow(StudyModeContract.CATEGORY_SOCIAL, "Block Social Media",
                categorySubtitle(StudyModeContract.CATEGORY_SOCIAL));
        addCategoryRow(StudyModeContract.CATEGORY_GAMES, "Block Games",
                categorySubtitle(StudyModeContract.CATEGORY_GAMES));
        addCategoryRow(StudyModeContract.CATEGORY_ENTERTAINMENT, "Block Entertainment",
                categorySubtitle(StudyModeContract.CATEGORY_ENTERTAINMENT));
    }

    private String categorySubtitle(String categoryId) {
        List<CategoryApp> apps = categoryApps.get(categoryId);
        if (!inventoryLoaded) {
            return "Loading child apps...";
        }
        if (apps == null || apps.isEmpty()) {
            return "No matching apps installed yet";
        }
        if (apps.size() == 1) {
            return apps.get(0).name;
        }
        return apps.size() + " apps found";
    }

    private void addCategoryRow(String categoryId, String title, String subtitle) {
        StudyModePolicy.CategorySelection selection = policy.categories.get(categoryId);
        boolean enabled = selection != null && selection.enabled;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, layoutRestrictions.getChildCount() == 0 ? 0 : dp(14), 0, 0);
        row.setOnClickListener(v -> showCategoryAppsDialog(categoryId, title));

        FrameLayout iconWrap = new FrameLayout(this);
        iconWrap.setBackgroundResource(R.drawable.bg_mode_icon_blue);
        ImageView icon = new ImageView(this);
        icon.setImageResource(categoryId.equals(StudyModeContract.CATEGORY_GAMES)
                ? R.drawable.ic_gamepad
                : R.drawable.ic_share_nodes);
        icon.setColorFilter(getColor(R.color.modern_blue_700));
        iconWrap.addView(icon, centeredParams(22, 22));
        row.addView(iconWrap, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(dp(12), 0, dp(12), 0);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColor(R.color.modern_grey_900));
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(getColor(R.color.modern_grey_600));
        subtitleView.setTextSize(12);
        textCol.addView(subtitleView);
        row.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        SwitchCompat toggle = new SwitchCompat(this);
        toggle.setChecked(enabled);
        toggle.setOnClickListener(v -> {
            setCategoryEnabled(categoryId, toggle.isChecked());
            renderRestrictions();
            renderReview();
        });
        row.addView(toggle);

        layoutRestrictions.addView(row);
    }
    private void showCategoryAppsDialog(String categoryId, String title) {
        List<CategoryApp> apps = categoryApps.get(categoryId);
        if (apps == null || apps.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage("No installed apps found in this category. If this category stays ON, future matching apps will still be blocked during Study Mode.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(6), dp(8), 0);

        for (CategoryApp app : apps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            ImageView appIcon = new ImageView(this);
            Bitmap bitmap = decodeIcon(app.iconBase64);
            if (bitmap != null) {
                appIcon.setImageBitmap(bitmap);
            } else {
                appIcon.setImageResource(R.drawable.ic_app);
                appIcon.setColorFilter(getColor(R.color.modern_blue_700));
            }
            appIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(36), dp(36));
            iconParams.setMargins(0, 0, dp(10), 0);
            row.addView(appIcon, iconParams);

            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(app.name);
            checkBox.setTextSize(15);
            checkBox.setTextColor(getColor(R.color.modern_grey_900));
            checkBox.setChecked(isAppSelected(app));
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                setAppSelected(app, isChecked);
                renderReview();
            });
            row.addView(checkBox, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            content.addView(row);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scrollView)
                .setPositiveButton("Done", (dialog, which) -> renderReview())
                .show();
    }

    private void renderReview() {
        layoutReview.removeAllViews();

        TextView summary = new TextView(this);
        summary.setText(buildReviewSummary());
        summary.setTextColor(getColor(R.color.modern_grey_700));
        summary.setTextSize(13);
        summary.setLineSpacing(dp(2), 1f);
        layoutReview.addView(summary);

        List<CategoryApp> blocked = new ArrayList<>();
        List<CategoryApp> allowed = new ArrayList<>();
        collectReviewApps(blocked, allowed);

        addReviewSection("Blocked apps", blocked, true);
        addReviewSection("Allowed exceptions", allowed, false);
    }

    private void collectReviewApps(List<CategoryApp> blocked, List<CategoryApp> allowed) {
        Set<String> knownPackages = new HashSet<>();
        for (List<CategoryApp> apps : categoryApps.values()) {
            for (CategoryApp app : apps) {
                knownPackages.add(app.packageName);
                if (isAppSelected(app)) {
                    blocked.add(app);
                } else if (Boolean.TRUE.equals(policy.allowedOverrides.get(app.packageName))) {
                    allowed.add(app);
                }
            }
        }

        if (policy.blockedPackages != null) {
            for (Map.Entry<String, Boolean> entry : policy.blockedPackages.entrySet()) {
                String packageName = entry.getKey();
                if (!Boolean.TRUE.equals(entry.getValue()) || knownPackages.contains(packageName)) {
                    continue;
                }
                if (Boolean.TRUE.equals(policy.allowedOverrides.get(packageName))) {
                    allowed.add(CategoryApp.placeholder(packageName));
                } else if (!AppBlockingPolicy.isUnblockable(packageName)) {
                    blocked.add(CategoryApp.placeholder(packageName));
                }
            }
        }

        if (policy.allowedOverrides != null) {
            for (Map.Entry<String, Boolean> entry : policy.allowedOverrides.entrySet()) {
                String packageName = entry.getKey();
                if (Boolean.TRUE.equals(entry.getValue()) && !knownPackages.contains(packageName)
                        && !containsPackage(allowed, packageName)) {
                    allowed.add(CategoryApp.placeholder(packageName));
                }
            }
        }
    }

    private boolean containsPackage(List<CategoryApp> apps, String packageName) {
        for (CategoryApp app : apps) {
            if (app.packageName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private String buildReviewSummary() {
        String state = policy.enabled ? "Enabled" : "Off";
        return state + " on " + formatDays() + " during " + formatSlots() + ".";
    }

    private void addReviewSection(String label, List<CategoryApp> apps, boolean blocked) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(getColor(R.color.modern_grey_900));
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(14), 0, dp(6));
        layoutReview.addView(title, titleParams);

        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(blocked ? "No apps selected yet." : "No exceptions.");
            empty.setTextColor(getColor(R.color.modern_grey_500));
            empty.setTextSize(13);
            layoutReview.addView(empty);
            return;
        }

        for (CategoryApp app : apps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));

            FrameLayout iconStack = new FrameLayout(this);
            ImageView appIcon = new ImageView(this);
            Bitmap bitmap = decodeIcon(app.iconBase64);
            if (bitmap != null) {
                appIcon.setImageBitmap(bitmap);
            } else {
                appIcon.setImageResource(R.drawable.ic_app);
                appIcon.setColorFilter(getColor(R.color.modern_blue_700));
            }
            appIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iconStack.addView(appIcon, centeredParams(34, 34));

            TextView badge = new TextView(this);
            badge.setText(blocked ? "+" : "-");
            badge.setGravity(Gravity.CENTER);
            badge.setTextColor(getColor(R.color.white));
            badge.setTextSize(12);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setBackgroundResource(blocked
                    ? R.drawable.bg_mode_review_block
                    : R.drawable.bg_mode_review_allow);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(20), dp(20));
            badgeParams.gravity = Gravity.BOTTOM | Gravity.END;
            iconStack.addView(badge, badgeParams);
            row.addView(iconStack, new LinearLayout.LayoutParams(dp(44), dp(44)));

            TextView name = new TextView(this);
            name.setText(app.name);
            name.setTextColor(getColor(R.color.modern_grey_800));
            name.setTextSize(14);
            name.setPadding(dp(10), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            layoutReview.addView(row);
        }
    }

    private Bitmap decodeIcon(String iconBase64) {
        if (isBlank(iconBase64)) {
            return null;
        }
        try {
            String encoded = iconBase64;
            int comma = encoded.indexOf(',');
            if (comma >= 0) {
                encoded = encoded.substring(comma + 1);
            }
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) {
            return null;
        }
    }
    private void saveDraft() {
        if (policy.days == null || policy.days.isEmpty()) {
            Toast.makeText(this, "Select at least one day", Toast.LENGTH_SHORT).show();
            return;
        }
        for (StudyModePolicy.TimeSlot slot : policy.timeSlots) {
            if (!StudyModeScheduleEvaluator.isValidSameDaySlot(slot)) {
                Toast.makeText(this, "Each time slot must start before it ends", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (StudyModeScheduleEvaluator.hasOverlappingSlots(policy.timeSlots)) {
            Toast.makeText(this, "Time slots cannot overlap", Toast.LENGTH_SHORT).show();
            return;
        }
        syncEnabledCategoriesWithInventory();
        if (isBlank(childDeviceId)) {
            StudyModeDraftStore.save(this, childDeviceId, policy);
            Toast.makeText(this, "Study Mode saved locally", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setSaveBusy(true);
        StudyModePolicyRepository.save(childDeviceId, policy)
                .addOnSuccessListener(unused -> {
                    StudyModeDraftStore.save(this, childDeviceId, policy);
                    Toast.makeText(this, "Study Mode saved", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(error -> {
                    setSaveBusy(false);
                    Toast.makeText(this, "Could not save Study Mode", Toast.LENGTH_SHORT).show();
                });
    }

    private void pickTime(String current, TimePicked picked) {
        int hour = 15;
        int minute = 0;
        try {
            String[] parts = current.split(":");
            hour = Integer.parseInt(parts[0]);
            minute = Integer.parseInt(parts[1]);
        } catch (Exception ignored) {
        }
        new TimePickerDialog(this, (view, selectedHour, selectedMinute) ->
                picked.onPicked(String.format(Locale.US, "%02d:%02d", selectedHour, selectedMinute)),
                hour, minute, false).show();
    }

    private void setCategoryEnabled(String categoryId, boolean enabled) {
        StudyModePolicy.CategorySelection selection = policy.categories.get(categoryId);
        if (selection == null) {
            selection = new StudyModePolicy.CategorySelection(enabled);
            policy.categories.put(categoryId, selection);
        }
        selection.enabled = enabled;
        List<CategoryApp> apps = categoryApps.get(categoryId);
        if (apps == null) {
            return;
        }
        for (CategoryApp app : apps) {
            if (enabled) {
                policy.blockedPackages.put(app.packageName, true);
                if (!Boolean.TRUE.equals(policy.allowedOverrides.get(app.packageName))) {
                    policy.allowedOverrides.put(app.packageName, false);
                }
            } else {
                policy.blockedPackages.remove(app.packageName);
                policy.allowedOverrides.remove(app.packageName);
            }
        }
    }

    private void setAppSelected(CategoryApp app, boolean selected) {
        if (selected) {
            policy.blockedPackages.put(app.packageName, true);
            policy.allowedOverrides.put(app.packageName, false);
        } else {
            policy.blockedPackages.remove(app.packageName);
            policy.allowedOverrides.put(app.packageName, true);
        }
    }

    private boolean isAppSelected(CategoryApp app) {
        if (app == null || isBlank(app.packageName)) {
            return false;
        }
        if (Boolean.TRUE.equals(policy.allowedOverrides.get(app.packageName))) {
            return false;
        }
        if (Boolean.TRUE.equals(policy.blockedPackages.get(app.packageName))) {
            return true;
        }
        String categoryId = toStudyCategoryId(app.category, app.packageName, app.name);
        StudyModePolicy.CategorySelection selection = categoryId != null
                ? policy.categories.get(categoryId) : null;
        return selection != null && selection.enabled;
    }

    private void syncEnabledCategoriesWithInventory() {
        ensureCategoryState();
        for (Map.Entry<String, List<CategoryApp>> entry : categoryApps.entrySet()) {
            StudyModePolicy.CategorySelection selection = policy.categories.get(entry.getKey());
            if (selection == null || !selection.enabled) {
                continue;
            }
            for (CategoryApp app : entry.getValue()) {
                if (!Boolean.TRUE.equals(policy.allowedOverrides.get(app.packageName))) {
                    policy.blockedPackages.put(app.packageName, true);
                }
            }
        }
    }

    private void ensureCategoryState() {
        if (policy.categories == null) {
            policy.categories = new LinkedHashMap<>();
        }
        if (policy.blockedPackages == null) {
            policy.blockedPackages = new LinkedHashMap<>();
        }
        if (policy.allowedOverrides == null) {
            policy.allowedOverrides = new LinkedHashMap<>();
        }
        for (String category : Arrays.asList(
                StudyModeContract.CATEGORY_SOCIAL,
                StudyModeContract.CATEGORY_GAMES,
                StudyModeContract.CATEGORY_ENTERTAINMENT)) {
            if (!policy.categories.containsKey(category)) {
                policy.categories.put(category, new StudyModePolicy.CategorySelection(false));
            }
        }
    }

    private void initializeCategoryApps() {
        categoryApps.clear();
        categoryApps.put(StudyModeContract.CATEGORY_SOCIAL, new ArrayList<>());
        categoryApps.put(StudyModeContract.CATEGORY_GAMES, new ArrayList<>());
        categoryApps.put(StudyModeContract.CATEGORY_ENTERTAINMENT, new ArrayList<>());
    }

    private void sortDays() {
        List<String> sorted = new ArrayList<>();
        for (String day : DAY_VALUES) {
            if (policy.days.contains(day)) {
                sorted.add(day);
            }
        }
        policy.days = sorted;
    }

    private String formatDays() {
        if (policy.days == null || policy.days.isEmpty()) {
            return "no days";
        }
        if (policy.days.size() == 5
                && policy.days.contains("MON")
                && policy.days.contains("TUE")
                && policy.days.contains("WED")
                && policy.days.contains("THU")
                && policy.days.contains("FRI")) {
            return "weekdays";
        }
        return String.join(", ", policy.days);
    }

    private String formatSlots() {
        if (policy.timeSlots == null || policy.timeSlots.isEmpty()) {
            return "no time slots";
        }
        List<String> result = new ArrayList<>();
        for (StudyModePolicy.TimeSlot slot : policy.timeSlots) {
            result.add(formatTime(slot.start) + " - " + formatTime(slot.end));
        }
        return String.join(", ", result);
    }

    private String formatTime(String value) {
        if (value == null || !value.contains(":")) {
            return "";
        }
        try {
            String[] parts = value.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            String suffix = hour >= 12 ? "PM" : "AM";
            int displayHour = hour % 12;
            if (displayHour == 0) {
                displayHour = 12;
            }
            return String.format(Locale.US, "%d:%02d %s", displayHour, minute, suffix);
        } catch (Exception ignored) {
            return value;
        }
    }

    private FrameLayout.LayoutParams centeredParams(int widthDp, int heightDp) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(widthDp), dp(heightDp));
        params.gravity = Gravity.CENTER;
        return params;
    }

    private void setSaveBusy(boolean busy) {
        if (btnSave == null) {
            return;
        }
        btnSave.setEnabled(!busy);
        btnSave.setAlpha(busy ? 0.55f : 1f);
        btnSave.setText(busy ? "Saving..." : "Save");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface TimePicked {
        void onPicked(String value);
    }

    private static class CategoryApp {
        final String name;
        final String packageName;
        final String category;
        final String iconBase64;

        CategoryApp(String name, String packageName, String category, String iconBase64) {
            this.name = name;
            this.packageName = packageName;
            this.category = category;
            this.iconBase64 = iconBase64;
        }

        static CategoryApp placeholder(String packageName) {
            return new CategoryApp(packageName, packageName, "", "");
        }
    }
}