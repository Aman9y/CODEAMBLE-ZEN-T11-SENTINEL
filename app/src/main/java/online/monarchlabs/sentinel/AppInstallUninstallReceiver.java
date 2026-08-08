package online.monarchlabs.sentinel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import online.monarchlabs.sentinel.utils.AppInventoryDeltaSync;
import online.monarchlabs.sentinel.workers.AppInventoryUpdateWorker;

public class AppInstallUninstallReceiver extends BroadcastReceiver {
    public static final String ACTION_APP_INVENTORY_CHANGED =
            "online.monarchlabs.sentinel.APP_INVENTORY_CHANGED";
    public static final String EXTRA_PACKAGE_NAME = "package_name";
    public static final String EXTRA_OPERATION = "operation";
    private static final String TAG = "AppInstallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String packageName = intent.getData() != null
                ? intent.getData().getSchemeSpecificPart() : null;
        if (action == null || packageName == null || packageName.isEmpty()
                || packageName.equals(context.getPackageName())) {
            return;
        }

        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
        String operation;
        String eventAction = "";

        if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            if (replacing) {
                return;
            }
            operation = AppInventoryUpdateWorker.OPERATION_UPSERT;
            eventAction = "INSTALLED";
        } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
            if (replacing) {
                return;
            }
            operation = AppInventoryUpdateWorker.OPERATION_REMOVE;
            eventAction = "UNINSTALLED";
        } else if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            operation = AppInventoryUpdateWorker.OPERATION_UPSERT;
        } else {
            return;
        }

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                AppInventoryUpdateWorker.class)
                .setInputData(AppInventoryUpdateWorker.input(
                        packageName, operation, eventAction))
                .addTag("app_inventory_delta")
                .build();
        String uniqueName = "app_inventory_" + packageName + "_"
                + (eventAction.isEmpty() ? operation : eventAction);
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request);

        Intent changed = new Intent(ACTION_APP_INVENTORY_CHANGED);
        changed.setPackage(context.getPackageName());
        changed.putExtra(EXTRA_PACKAGE_NAME, packageName);
        changed.putExtra(EXTRA_OPERATION, operation);
        context.sendBroadcast(changed);

        Log.d(TAG, "Queued app inventory delta for " + packageName);
    }
}
