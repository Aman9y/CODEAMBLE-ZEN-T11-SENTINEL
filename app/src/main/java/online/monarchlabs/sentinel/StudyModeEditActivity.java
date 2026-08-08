package online.monarchlabs.sentinel;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
    private static final int MAX_PREVIEW_APPS = 5;

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
        migrateLegacySelectionDefaultsIfNeeded();
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
                        StudyModeDraftStore.clear(this, childDeviceId);
                        policy = StudyModePolicy.createDefault();
                        ensureCategoryState();
                        if (switchStudyEnabled != null) {
                            switchStudyEnabled.setChecked(policy.enabled);
                        }
                        syncEnabledCategoriesWithInventory();
                        renderAll();
                        return;
                    }
                    policy = remotePolicy;
                    ensureCategoryState();
                    migrateLegacySelectionDefaultsIfNeeded();
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
                        List<CategoryApp> apps = categoryApps.get(categoryId);
                        if (apps != null) {
                            apps.add(app);
                        }
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
        if (normalized.contains("social")
                || normalized.contains("communication")
                || normalized.contains("messaging")) {
            return StudyModeContract.CATEGORY_SOCIAL;
        }
        if (normalized.contains("game")) {
            return StudyModeContract.CATEGORY_GAMES;
        }
        if (normalized.contains("entertainment")
                || normalized.contains("video")
                || normalized.contains("audio")) {
            return StudyModeContract.CATEGORY_ENTERTAINMENT;
        }

        AppCategorizer.AppCategory category = AppCategorizer.getCategory(packageName, appName);
        switch (category) {
            case SOCIAL:
            case COMMUNICATION:
                return StudyModeContract.CATEGORY_SOCIAL;
            case GAMES:
                return StudyModeContract.CATEGORY_GAMES;
            case ENTERTAINMENT:
                return StudyModeContract.CATEGORY_ENTERTAINMENT;
            default:
                return StudyModeContract.CATEGORY_OTHER;
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
        boolean showDelete = policy.timeSlots.size() > 1;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, index == 0 ? 0 : dp(12), 0, 0);

        ImageView clock = new ImageView(this);
        clock.setImageResource(R.drawable.ic_time);
        clock.setColorFilter(getColor(R.color.modern_grey_500));
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        clockParams.setMargins(0, 0, dp(10), 0);
        row.addView(clock, clockParams);

        TextView start = createTimeButton(formatTime(slot.start));
        start.setOnClickListener(v -> pickTime(slot.start, value -> {
            slot.start = value;
            renderSlots();
            renderReview();
        }));
        row.addView(start, new LinearLayout.LayoutParams(0, dp(44), 1));

        TextView dash = new TextView(this);
        dash.setText("-");
        dash.setGravity(Gravity.CENTER);
        dash.setTextColor(getColor(R.color.modern_grey_500));
        dash.setTextSize(18);
        row.addView(dash, new LinearLayout.LayoutParams(dp(30), dp(44)));

        TextView end = createTimeButton(formatTime(slot.end));
        end.setOnClickListener(v -> pickTime(slot.end, value -> {
            slot.end = value;
            renderSlots();
            renderReview();
        }));
        row.addView(end, new LinearLayout.LayoutParams(0, dp(44), 1));

        ImageButton delete = new ImageButton(this);
        delete.setImageResource(R.drawable.ic_delete);
        delete.setColorFilter(getColor(showDelete ? R.color.modern_grey_500 : R.color.modern_grey_300));
        delete.setBackgroundResource(R.drawable.bg_study_picker_row);
        delete.setContentDescription("Delete time slot");
        delete.setEnabled(showDelete);
        delete.setAlpha(showDelete ? 1f : 0.35f);
        delete.setOnClickListener(v -> {
            if (!showDelete) {
                return;
            }
            policy.timeSlots.remove(index);
            renderSlots();
            renderReview();
        });
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(42), dp(44));
        deleteParams.setMargins(dp(10), 0, 0, 0);
        row.addView(delete, deleteParams);
        return row;
    }
    private TextView createTimeLabel(String label) {
        TextView view = new TextView(this);
        view.setGravity(Gravity.CENTER);
        view.setText(label);
        view.setTextColor(getColor(R.color.modern_grey_600));
        view.setTextSize(12);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView createTimeButton(String time) {
        TextView view = new TextView(this);
        view.setBackgroundResource(R.drawable.bg_study_picker_row);
        view.setGravity(Gravity.CENTER);
        view.setText(time);
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
        addRestrictionGroup("Blocked apps",
                "These apps will be blocked during Study Mode.",
                new String[] {
                        StudyModeContract.CATEGORY_SOCIAL,
                        StudyModeContract.CATEGORY_GAMES,
                        StudyModeContract.CATEGORY_ENTERTAINMENT
                },
                new String[] {"Social media", "Games", "Entertainment"},
                new String[] {
                        "Apps selected by default during study time",
                        "Apps selected by default during study time",
                        "Entertainment apps selected by default"
                });
        addRestrictionGroup("Unblocked apps",
                "Tap + to add apps to the blocklist.",
                new String[] {StudyModeContract.CATEGORY_OTHER},
                new String[] {"Others"},
                new String[] {"Add extra apps only if needed"});
    }

    private void addRestrictionGroup(String title, String subtitle, String[] categoryIds,
                                     String[] categoryTitles, String[] helperTexts) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(12), dp(12), dp(12), dp(12));
        group.setBackgroundResource(R.drawable.bg_study_picker_row);

        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        groupParams.setMargins(0, layoutRestrictions.getChildCount() == 0 ? 0 : dp(14), 0, 0);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColor(R.color.modern_grey_900));
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        group.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(getColor(R.color.modern_grey_600));
        subtitleView.setTextSize(12);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.setMargins(0, dp(2), 0, 0);
        group.addView(subtitleView, subtitleParams);

        for (int i = 0; i < categoryIds.length; i++) {
            addCategorySection(group, categoryIds[i], categoryTitles[i], helperTexts[i], i == 0);
        }

        layoutRestrictions.addView(group, groupParams);
    }

    private void addCategorySection(LinearLayout parent, String categoryId, String title,
                                    String helperText, boolean firstInGroup) {
        List<CategoryApp> apps = categoryApps.get(categoryId);
        if (apps == null) {
            apps = new ArrayList<>();
        }

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, firstInGroup ? dp(12) : dp(18), 0, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);


        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColor(R.color.modern_grey_900));
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText(categorySummary(categoryId, apps, helperText));
        subtitleView.setTextColor(getColor(R.color.modern_grey_600));
        subtitleView.setTextSize(12);
        titleCol.addView(subtitleView);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView viewAll = new TextView(this);
        viewAll.setText("View all");
        viewAll.setTextColor(getColor(R.color.modern_blue_700));
        viewAll.setTextSize(13);
        viewAll.setTypeface(Typeface.DEFAULT_BOLD);
        viewAll.setGravity(Gravity.CENTER);
        viewAll.setPadding(dp(10), dp(6), 0, dp(6));
        viewAll.setOnClickListener(v -> showCategoryAppsDialog(categoryId, title));
        header.addView(viewAll);
        section.addView(header);

        View strip = createCategoryStrip(categoryId, apps);
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stripParams.setMargins(0, dp(8), 0, 0);
        section.addView(strip, stripParams);

        parent.addView(section);
    }
    private String categorySummary(String categoryId, List<CategoryApp> apps, String helperText) {
        if (!inventoryLoaded) {
            return "Loading child apps...";
        }
        if (apps == null || apps.isEmpty()) {
            return "No apps found";
        }
        int selected = selectedCount(apps);
        if (StudyModeContract.CATEGORY_OTHER.equals(categoryId)) {
            if (selected == 0) {
                return apps.size() + " apps available to add";
            }
            return selected + " selected, " + Math.max(0, apps.size() - selected) + " available";
        }
        return selected + " selected of " + apps.size();
    }

    private View createCategoryStrip(String categoryId, List<CategoryApp> apps) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, dp(4), 0);

        if (!inventoryLoaded) {
            row.addView(createEmptyStripText("Loading apps..."));
        } else if (apps == null || apps.isEmpty()) {
            row.addView(createEmptyStripText("No apps in this category"));
        } else {
            List<CategoryApp> preview = previewAppsForCategory(categoryId, apps);
            if (preview.isEmpty()) {
                row.addView(createEmptyStripText("No apps selected. Tap View all to choose."));
            } else {
                for (CategoryApp app : preview) {
                    row.addView(createAppPreviewChip(app));
                }
                int hiddenCount = previewCandidateCount(categoryId, apps) - preview.size();
                if (hiddenCount > 0) {
                    row.addView(createMoreAppsChip(hiddenCount));
                }
            }
        }

        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.addView(row);
        return scrollView;
    }

    private TextView createEmptyStripText(String message) {
        TextView view = new TextView(this);
        view.setText(message);
        view.setTextColor(getColor(R.color.modern_grey_500));
        view.setTextSize(13);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private List<CategoryApp> previewAppsForCategory(String categoryId, List<CategoryApp> apps) {
        List<CategoryApp> selected = new ArrayList<>();
        List<CategoryApp> unselected = new ArrayList<>();
        for (CategoryApp app : apps) {
            if (isAppSelected(app)) {
                selected.add(app);
            } else {
                unselected.add(app);
            }
        }

        List<CategoryApp> result = new ArrayList<>();
        if (StudyModeContract.CATEGORY_OTHER.equals(categoryId)) {
            result.addAll(selected);
            result.addAll(unselected);
        } else {
            result.addAll(selected);
        }
        if (result.size() > MAX_PREVIEW_APPS) {
            return new ArrayList<>(result.subList(0, MAX_PREVIEW_APPS));
        }
        return result;
    }

    private int previewCandidateCount(String categoryId, List<CategoryApp> apps) {
        if (apps == null) {
            return 0;
        }
        if (StudyModeContract.CATEGORY_OTHER.equals(categoryId)) {
            return apps.size();
        }
        int count = 0;
        for (CategoryApp app : apps) {
            if (isAppSelected(app)) {
                count++;
            }
        }
        return count;
    }

    private View createAppPreviewChip(CategoryApp app) {
        boolean selected = isAppSelected(app);
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setOnClickListener(v -> toggleAppSelection(app));
        item.setContentDescription((selected ? "Remove " : "Add ") + app.name);

        FrameLayout iconWrap = new FrameLayout(this);
        ImageView icon = createAppIcon(app, 40);
        iconWrap.addView(icon, centeredParams(40, 40));

        TextView badge = new TextView(this);
        badge.setText(selected ? "-" : "+");
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(getColor(selected ? R.color.modern_red_500 : R.color.white));
        badge.setBackgroundResource(selected ? R.drawable.bg_remove_badge : R.drawable.bg_mode_review_block);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(18), dp(18));
        badgeParams.gravity = Gravity.TOP | Gravity.END;
        badgeParams.setMargins(0, 0, dp(3), 0);
        iconWrap.addView(badge, badgeParams);
        item.addView(iconWrap, new LinearLayout.LayoutParams(dp(48), dp(44)));

        TextView name = new TextView(this);
        name.setText(app.name);
        name.setGravity(Gravity.CENTER);
        name.setTextColor(getColor(R.color.modern_grey_700));
        name.setTextSize(10);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT);
        nameParams.setMargins(0, dp(3), 0, 0);
        item.addView(name, nameParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(6), 0);
        item.setLayoutParams(params);
        return item;
    }

    private View createMoreAppsChip(int hiddenCount) {
        TextView chip = new TextView(this);
        chip.setText("+" + hiddenCount + " more");
        chip.setTextColor(getColor(R.color.modern_blue_700));
        chip.setTextSize(12);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setBackgroundResource(R.drawable.bg_mode_day_unselected);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(74), dp(34));
        params.setMargins(dp(2), 0, 0, 0);
        chip.setLayoutParams(params);
        return chip;
    }

    private ImageView createAppIcon(CategoryApp app, int sizeDp) {
        ImageView appIcon = new ImageView(this);
        Bitmap bitmap = decodeIcon(app.iconBase64);
        if (bitmap != null) {
            appIcon.setImageBitmap(bitmap);
            appIcon.clearColorFilter();
        } else {
            appIcon.setImageResource(R.drawable.ic_app);
            appIcon.setColorFilter(getColor(R.color.modern_blue_700));
        }
        appIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        appIcon.setPadding(dp(2), dp(2), dp(2), dp(2));
        appIcon.setBackgroundResource(R.drawable.bg_mode_icon_soft);
        return appIcon;
    }

    private void toggleAppSelection(CategoryApp app) {
        setAppSelected(app, !isAppSelected(app));
        renderRestrictions();
        renderReview();
    }

    private int selectedCount(List<CategoryApp> apps) {
        int count = 0;
        if (apps == null) {
            return 0;
        }
        for (CategoryApp app : apps) {
            if (isAppSelected(app)) {
                count++;
            }
        }
        return count;
    }

    private void showCategoryAppsDialog(String categoryId, String title) {
        List<CategoryApp> apps = categoryApps.get(categoryId);
        if (apps == null || apps.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(inventoryLoaded ? "No installed apps found in this category." : "Apps are still loading.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_study_dialog_surface);
        root.setPadding(dp(12), dp(10), dp(12), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView close = new TextView(this);
        close.setText("X");
        close.setGravity(Gravity.CENTER);
        close.setTextSize(18);
        close.setTextColor(getColor(R.color.modern_grey_900));
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));

        TextView titleView = new TextView(this);
        titleView.setText(categoryDialogTitle(categoryId, title));
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(getColor(R.color.modern_grey_900));
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        header.addView(new View(this), new LinearLayout.LayoutParams(dp(36), dp(36)));
        root.addView(header);

        LinearLayout searchWrap = new LinearLayout(this);
        searchWrap.setOrientation(LinearLayout.HORIZONTAL);
        searchWrap.setGravity(Gravity.CENTER_VERTICAL);
        searchWrap.setBackgroundResource(R.drawable.bg_study_search_pill);
        searchWrap.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        searchParams.setMargins(0, dp(10), 0, dp(12));

        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageResource(android.R.drawable.ic_menu_search);
        searchIcon.setColorFilter(getColor(R.color.modern_grey_500));
        searchWrap.addView(searchIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        EditText searchInput = new EditText(this);
        searchInput.setHint("Search apps...");
        searchInput.setSingleLine(true);
        searchInput.setTextSize(14);
        searchInput.setTextColor(getColor(R.color.modern_grey_900));
        searchInput.setHintTextColor(getColor(R.color.modern_grey_500));
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setPadding(dp(10), 0, 0, 0);
        searchWrap.addView(searchInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(searchWrap, searchParams);

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.addView(rows);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        TextView done = new TextView(this);
        done.setText("Done");
        done.setGravity(Gravity.CENTER);
        done.setTextColor(getColor(R.color.white));
        done.setTextSize(14);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setBackgroundResource(R.drawable.bg_primary_pill);
        done.setOnClickListener(v -> {
            renderRestrictions();
            renderReview();
            dialog.dismiss();
        });
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        doneParams.setMargins(0, dp(14), 0, 0);
        root.addView(done, doneParams);

        Runnable renderRows = () -> renderCategoryDialogRows(rows, apps, searchInput.getText().toString(), categoryId);
        renderRows.run();
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderRows.run();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.82f);
            window.setLayout(width, height);
        }
    }

    private void renderCategoryDialogRows(LinearLayout rows, List<CategoryApp> apps, String query, String categoryId) {
        rows.removeAllViews();
        int shown = 0;
        for (CategoryApp app : apps) {
            if (!matchesCategorySearch(app, query)) {
                continue;
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(66));
            rowParams.setMargins(0, 0, 0, dp(8));
            rows.addView(createCategoryDialogRow(app, categoryId), rowParams);
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("No apps found");
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(getColor(R.color.modern_grey_500));
            empty.setTextSize(14);
            rows.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(120)));
        }
    }

    private View createCategoryDialogRow(CategoryApp app, String categoryId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(8), 0);
        row.setBackgroundResource(R.drawable.bg_study_picker_row);

        ImageView appIcon = createAppIcon(app, 42);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconParams.setMargins(0, 0, dp(12), 0);
        row.addView(appIcon, iconParams);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(app.name);
        name.setTextSize(14);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(getColor(R.color.modern_grey_900));
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(name);

        TextView category = new TextView(this);
        category.setText(categoryDisplayName(categoryId));
        category.setTextSize(12);
        category.setTextColor(getColor(R.color.modern_grey_600));
        category.setMaxLines(1);
        textColumn.addView(category);

        row.addView(textColumn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        CheckBox checkBox = new CheckBox(this);
        checkBox.setChecked(isAppSelected(app));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> setAppSelected(app, isChecked));
        row.setOnClickListener(v -> checkBox.setChecked(!checkBox.isChecked()));
        row.addView(checkBox, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return row;
    }

    private boolean matchesCategorySearch(CategoryApp app, String query) {
        if (app == null) {
            return false;
        }
        if (isBlank(query)) {
            return true;
        }
        String normalized = query.toLowerCase(Locale.US).trim();
        return app.name.toLowerCase(Locale.US).contains(normalized);
    }

    private String categoryDialogTitle(String categoryId, String fallback) {
        return categoryDisplayName(categoryId) + " Apps";
    }

    private String categoryDisplayName(String categoryId) {
        if (StudyModeContract.CATEGORY_SOCIAL.equals(categoryId)) {
            return "Social Media";
        }
        if (StudyModeContract.CATEGORY_GAMES.equals(categoryId)) {
            return "Games";
        }
        if (StudyModeContract.CATEGORY_ENTERTAINMENT.equals(categoryId)) {
            return "Entertainment";
        }
        if (StudyModeContract.CATEGORY_OTHER.equals(categoryId)) {
            return "Others";
        }
        return "Apps";
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
        addReviewSection("Allowed apps", allowed, false);
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
            empty.setText(blocked ? "No apps selected yet." : "No allowed apps.");
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

            ImageView appIcon = new ImageView(this);
            Bitmap bitmap = decodeIcon(app.iconBase64);
            if (bitmap != null) {
                appIcon.setImageBitmap(bitmap);
            } else {
                appIcon.setImageResource(R.drawable.ic_app);
                appIcon.setColorFilter(getColor(R.color.modern_blue_700));
            }
            appIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(appIcon, new LinearLayout.LayoutParams(dp(34), dp(34)));

            TextView name = new TextView(this);
            name.setText(app.name);
            name.setTextColor(getColor(R.color.modern_grey_800));
            name.setTextSize(14);
            name.setPadding(dp(10), 0, dp(8), 0);
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView status = new TextView(this);
            status.setText(blocked ? "Remove" : "Allowed");
            status.setGravity(Gravity.CENTER);
            status.setTextColor(getColor(blocked ? R.color.white : R.color.modern_grey_700));
            status.setTextSize(11);
            status.setTypeface(Typeface.DEFAULT_BOLD);
            status.setBackgroundResource(blocked
                    ? R.drawable.bg_mode_review_allow
                    : R.drawable.bg_mode_day_unselected);
            if (blocked) {
                status.setContentDescription("Remove " + app.name + " from Study Mode blocklist");
                status.setOnClickListener(v -> {
                    setAppSelected(app, false);
                    renderRestrictions();
                    renderReview();
                });
            }
            row.addView(status, new LinearLayout.LayoutParams(dp(78), dp(28)));
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
        if (app == null || isBlank(app.packageName)) {
            return;
        }
        String categoryId = toStudyCategoryId(app.category, app.packageName, app.name);
        if (selected) {
            policy.blockedPackages.put(app.packageName, true);
            policy.allowedOverrides.put(app.packageName, false);
        } else {
            policy.blockedPackages.remove(app.packageName);
            if (isDefaultBlockedCategory(categoryId)) {
                policy.allowedOverrides.put(app.packageName, true);
            } else {
                policy.allowedOverrides.remove(app.packageName);
            }
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
        if (policy.sessionAllowedPackages == null) {
            policy.sessionAllowedPackages = new LinkedHashMap<>();
        }
        for (String category : Arrays.asList(
                StudyModeContract.CATEGORY_SOCIAL,
                StudyModeContract.CATEGORY_GAMES,
                StudyModeContract.CATEGORY_ENTERTAINMENT,
                StudyModeContract.CATEGORY_OTHER)) {
            if (!policy.categories.containsKey(category)) {
                policy.categories.put(category,
                        new StudyModePolicy.CategorySelection(isDefaultBlockedCategory(category)));
            }
        }
    }

    private void applyDefaultSelectionsIfEmpty() {
        boolean hasExplicitBlocks = hasTrueValue(policy.blockedPackages);
        boolean hasExplicitAllows = hasTrueValue(policy.allowedOverrides);
        boolean hasEnabledCategory = false;
        if (policy.categories != null) {
            for (StudyModePolicy.CategorySelection selection : policy.categories.values()) {
                if (selection != null && selection.enabled) {
                    hasEnabledCategory = true;
                    break;
                }
            }
        }
        if (hasExplicitBlocks || hasExplicitAllows || hasEnabledCategory) {
            return;
        }
        enableDefaultStudyCategories();
    }

    private void migrateLegacySelectionDefaultsIfNeeded() {
        if (policy == null || policy.schemaVersion >= StudyModeContract.POLICY_SCHEMA_VERSION) {
            applyDefaultSelectionsIfEmpty();
            return;
        }
        enableDefaultStudyCategories();
        if (policy.allowedOverrides != null) {
            policy.allowedOverrides.clear();
        }
        policy.schemaVersion = StudyModeContract.POLICY_SCHEMA_VERSION;
    }

    private boolean hasTrueValue(Map<String, Boolean> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (Boolean value : values.values()) {
            if (Boolean.TRUE.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private void enableDefaultStudyCategories() {
        for (String category : Arrays.asList(
                StudyModeContract.CATEGORY_SOCIAL,
                StudyModeContract.CATEGORY_GAMES,
                StudyModeContract.CATEGORY_ENTERTAINMENT)) {
            StudyModePolicy.CategorySelection selection = policy.categories.get(category);
            if (selection == null) {
                selection = new StudyModePolicy.CategorySelection(true);
                policy.categories.put(category, selection);
            }
            selection.enabled = true;
        }
    }

    private boolean isDefaultBlockedCategory(String categoryId) {
        return StudyModeContract.CATEGORY_SOCIAL.equals(categoryId)
                || StudyModeContract.CATEGORY_GAMES.equals(categoryId)
                || StudyModeContract.CATEGORY_ENTERTAINMENT.equals(categoryId);
    }

    private void initializeCategoryApps() {
        categoryApps.clear();
        categoryApps.put(StudyModeContract.CATEGORY_SOCIAL, new ArrayList<>());
        categoryApps.put(StudyModeContract.CATEGORY_GAMES, new ArrayList<>());
        categoryApps.put(StudyModeContract.CATEGORY_ENTERTAINMENT, new ArrayList<>());
        categoryApps.put(StudyModeContract.CATEGORY_OTHER, new ArrayList<>());
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