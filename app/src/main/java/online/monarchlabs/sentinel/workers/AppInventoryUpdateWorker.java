package online.monarchlabs.sentinel.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import online.monarchlabs.sentinel.utils.AppInventoryDeltaSync;

public class AppInventoryUpdateWorker extends Worker {
    public static final String KEY_PACKAGE_NAME = "package_name";
    public static final String KEY_OPERATION = "operation";
    public static final String KEY_EVENT_ACTION = "event_action";
    public static final String OPERATION_UPSERT = AppInventoryDeltaSync.OPERATION_UPSERT;
    public static final String OPERATION_REMOVE = AppInventoryDeltaSync.OPERATION_REMOVE;

    private static final String TAG = "AppInventoryWorker";

    public AppInventoryUpdateWorker(@NonNull Context context,
            @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    public static Data input(String packageName, String operation, String eventAction) {
        return new Data.Builder()
                .putString(KEY_PACKAGE_NAME, packageName)
                .putString(KEY_OPERATION, operation)
                .putString(KEY_EVENT_ACTION, eventAction)
                .build();
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String packageName = getInputData().getString(KEY_PACKAGE_NAME);
        String operation = getInputData().getString(KEY_OPERATION);
        String eventAction = getInputData().getString(KEY_EVENT_ACTION);

        if (packageName == null || operation == null) {
            return Result.success();
        }

        try {
            AppInventoryDeltaSync.syncBlocking(
                    context, packageName, operation, eventAction);
            return Result.success();
        } catch (Exception error) {
            Log.w(TAG, "App inventory delta deferred", error);
            return getRunAttemptCount() < 5 ? Result.retry() : Result.failure();
        }
    }
}
