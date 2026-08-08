package online.monarchlabs.sentinel.workers;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public final class AssistantCommandOutboxScheduler {
    private static final String TAG = "AssistantOutboxScheduler";
    private static final String WORK_NAME = "assistant_command_outbox_retry";

    private AssistantCommandOutboxScheduler() {
    }

    public static void scheduleRetry(Context context) {
        if (context == null) {
            return;
        }
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AssistantCommandOutboxWorker.class)
                    .setConstraints(constraints)
                    .addTag(WORK_NAME)
                    .build();

            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request);
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule assistant outbox retry: " + e.getMessage(), e);
        }
    }
}