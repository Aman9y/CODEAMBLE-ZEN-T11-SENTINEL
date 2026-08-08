package online.monarchlabs.sentinel.workers;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Scheduler for reliable usage data uploads using WorkManager.
 * 
 * WorkManager advantages:
 * - Survives app kills
 * - Survives device reboots
 * - Battery optimized
 * - Guaranteed execution
 * - Automatic retry on failure
 */
public class UsageUploadScheduler {

    private static final String TAG = "UsageUploadScheduler";
    private static final String WORK_NAME = "usage_upload_periodic";
    private static final String IMMEDIATE_WORK_NAME = "usage_upload_immediate";
    static final String INPUT_EXPECTED_CONNECTION_ID = "expected_connection_id";

    // WorkManager periodic jobs have a 15-minute minimum interval.
    private static final int UPLOAD_INTERVAL_MINUTES = 15;

    /**
     * Schedule periodic usage uploads.
     * Call this once in ChildDashboardActivity.onCreate()
     */
    public static void schedulePeriodicUpload(Context context) {
        Log.d(TAG, "📅 Scheduling periodic usage upload every " + UPLOAD_INTERVAL_MINUTES + " minutes");

        try {
            // Constraints: require network connection
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            // Create periodic work request
            PeriodicWorkRequest uploadRequest = new PeriodicWorkRequest.Builder(
                    UsageUploadWorker.class,
                    UPLOAD_INTERVAL_MINUTES,
                    TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .addTag("usage_upload")
                    .build();

            // Enqueue the work - KEEP existing if already scheduled
            WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                            WORK_NAME,
                            ExistingPeriodicWorkPolicy.KEEP, // Don't restart if already scheduled
                            uploadRequest);

            Log.d(TAG, "✅ Periodic upload scheduled successfully");

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to schedule periodic upload: " + e.getMessage(), e);
        }
    }

    /** Schedule one retryable seven-day bootstrap for this connection. */
    public static void triggerBootstrapUpload(
            Context context, String connectionId) {
        if (connectionId == null || connectionId.trim().isEmpty()) {
            return;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        Data input = new Data.Builder()
                .putString(INPUT_EXPECTED_CONNECTION_ID, connectionId)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                UsageUploadWorker.class)
                .setConstraints(constraints)
                .setInputData(input)
                .addTag("usage_bootstrap")
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                "usage_bootstrap_" + connectionId,
                ExistingWorkPolicy.KEEP,
                request);
    }
    /**
     * Trigger an immediate one-time upload (for manual refresh)
     */
    public static void triggerImmediateUpload(Context context) {
        Log.d(TAG, "⚡ Triggering immediate upload...");

        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            OneTimeWorkRequest immediateRequest = new OneTimeWorkRequest.Builder(UsageUploadWorker.class)
                    .setConstraints(constraints)
                    .addTag("usage_upload_immediate")
                    .build();

            WorkManager.getInstance(context).enqueueUniqueWork(
                    IMMEDIATE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    immediateRequest);

            Log.d(TAG, "✅ Immediate upload triggered");

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to trigger immediate upload: " + e.getMessage());
        }
    }

    /**
     * Cancel all scheduled uploads (for cleanup/logout)
     */
    public static void cancelAllUploads(Context context) {
        Log.d(TAG, "🛑 Cancelling all scheduled uploads");

        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME);
            WorkManager.getInstance(context).cancelAllWorkByTag("usage_upload");
            WorkManager.getInstance(context).cancelAllWorkByTag("usage_upload_immediate");
            WorkManager.getInstance(context).cancelAllWorkByTag("usage_bootstrap");
            Log.d(TAG, "✅ All uploads cancelled");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to cancel uploads: " + e.getMessage());
        }
    }
}
