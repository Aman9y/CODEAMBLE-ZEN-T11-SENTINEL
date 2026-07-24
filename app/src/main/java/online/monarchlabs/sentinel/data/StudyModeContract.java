package online.monarchlabs.sentinel.data;

/** Firebase contract for Study Mode V1. */
public final class StudyModeContract {
    public static final String MODE_ID = "study";
    public static final int POLICY_SCHEMA_VERSION = 1;
    public static final String DEVICE_MODES_PATH = FirebaseSchemaV2Repository.ROOT + "/device_modes";

    public static final String CATEGORY_SOCIAL = "social";
    public static final String CATEGORY_GAMES = "games";
    public static final String CATEGORY_ENTERTAINMENT = "entertainment";

    public static final int MAX_TIME_SLOTS = 4;
    public static final String DEFAULT_TIMEZONE = "Asia/Kolkata";

    private StudyModeContract() {
    }

    public static String studyModePath(String deviceId) {
        return DEVICE_MODES_PATH + "/" + deviceId + "/" + MODE_ID;
    }
}
