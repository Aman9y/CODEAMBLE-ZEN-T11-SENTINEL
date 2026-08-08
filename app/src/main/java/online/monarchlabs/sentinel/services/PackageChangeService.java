package online.monarchlabs.sentinel.services;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import online.monarchlabs.sentinel.utils.AppInventoryDeltaSync;
import online.monarchlabs.sentinel.workers.AppInventoryUpdateWorker;

/**
 * Keeps app install/delete detection live after the optimized inventory sync.
 * Writes only the changed app and a revision marker, not the full inventory.
 */
public class PackageChangeService extends Service {
    private static final String TAG = "PackageChangeService";
    private static final long HEARTBEAT_INTERVAL_MS = 60_000L;

    private BroadcastReceiver packageReceiver;
    private Handler heartbeatHandler;
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            AppInventoryDeltaSync.updateLiveMonitorHeartbeat(PackageChangeService.this);
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        heartbeatHandler = new Handler(Looper.getMainLooper());
        AppInventoryDeltaSync.markLiveMonitorActive(this, true);
        heartbeatHandler.post(heartbeatRunnable);
        setupPackageReceiver();
        Log.d(TAG, "Package change listener registered");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppInventoryDeltaSync.updateLiveMonitorHeartbeat(this);
        return START_STICKY;
    }

    private void setupPackageReceiver() {
        packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handlePackageIntent(intent);
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter);
    }

    private void handlePackageIntent(Intent intent) {
        String action = intent.getAction();
        String packageName = intent.getData() != null
                ? intent.getData().getSchemeSpecificPart() : null;
        if (action == null || packageName == null || packageName.isEmpty()
                || packageName.equals(getPackageName())) {
            return;
        }

        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
        String operation;
        String eventAction = "";

        if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            if (replacing) {
                return;
            }
            operation = AppInventoryDeltaSync.OPERATION_UPSERT;
            eventAction = "INSTALLED";
        } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
            if (replacing) {
                return;
            }
            operation = AppInventoryDeltaSync.OPERATION_REMOVE;
            eventAction = "UNINSTALLED";
        } else if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            operation = AppInventoryDeltaSync.OPERATION_UPSERT;
        } else {
            return;
        }

        final String targetPackageName = packageName;
        final String targetOperation = operation;
        final String targetEventAction = eventAction;

        AppInventoryDeltaSync.updateLiveMonitorHeartbeat(this);
        AppInventoryDeltaSync.syncAsync(
                this,
                targetPackageName,
                targetOperation,
                targetEventAction,
                new AppInventoryDeltaSync.Callback() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "Synced app inventory delta for " + targetPackageName);
                    }

                    @Override
                    public void onError(Exception error) {
                        Log.w(TAG, "Immediate app inventory delta failed; queued retry", error);
                        enqueueRetry(targetPackageName, targetOperation, targetEventAction);
                    }
                });
    }

    private void enqueueRetry(String packageName, String operation, String eventAction) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                AppInventoryUpdateWorker.class)
                .setInputData(AppInventoryUpdateWorker.input(
                        packageName, operation, eventAction))
                .addTag("app_inventory_delta")
                .build();
        WorkManager.getInstance(getApplicationContext())
                .enqueueUniqueWork(
                        "app_inventory_" + packageName + "_"
                                + (eventAction.isEmpty() ? operation : eventAction),
                        ExistingWorkPolicy.REPLACE,
                        request);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (packageReceiver != null) {
            try {
                unregisterReceiver(packageReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was already unregistered by the framework.
            }
        }
        if (heartbeatHandler != null) {
            heartbeatHandler.removeCallbacksAndMessages(null);
        }
        AppInventoryDeltaSync.markLiveMonitorActive(this, false);
        super.onDestroy();
        Log.d(TAG, "Package change listener stopped");
    }
}
