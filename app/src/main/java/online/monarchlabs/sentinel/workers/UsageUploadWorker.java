package online.monarchlabs.sentinel.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import online.monarchlabs.sentinel.SessionManager;
import online.monarchlabs.sentinel.utils.SUsageDataManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WorkManager Worker for reliable background usage data uploads.
 * WorkManager retries eligible uploads after process death and device restarts.
 * 
 * Features:
 * - Bootstraps seven days once per connection, then uploads today deltas


 * - Survives app restarts
 * - Battery optimized by WorkManager
 */
public class UsageUploadWorker extends Worker {

    private static final String TAG = "UsageUploadWorker";

    public UsageUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "🚀 UsageUploadWorker starting...");

        try {
            SessionManager sessionManager = new SessionManager(
                    getApplicationContext());
            String deviceId = sessionManager.getChildDeviceId();
            String connectionId = sessionManager.getConnectionId();
            boolean activeChildSession = sessionManager.isLoggedIn()
                    && "child".equals(sessionManager.getUserType())
                    && sessionManager.isConnectionActive()
                    && deviceId != null && !deviceId.isEmpty()
                    && connectionId != null && !connectionId.isEmpty();
            if (!activeChildSession) {
                Log.d(TAG, "No active child relationship; skipping usage upload");
                return Result.success();
            }

            String expectedConnectionId = getInputData().getString(
                    UsageUploadScheduler.INPUT_EXPECTED_CONNECTION_ID);
            if (expectedConnectionId != null
                    && !expectedConnectionId.equals(connectionId)) {
                Log.d(TAG, "Discarding stale usage work for an old connection");
                return Result.success();
            }

            Log.d(TAG, "📱 Uploading usage data for device: " + deviceId);

            // Upload usage data
            boolean uploadSuccess = uploadUsageData(deviceId);

            if (uploadSuccess) {
                Log.d(TAG, "✅ UsageUploadWorker completed successfully");
                return Result.success();
            } else {
                Log.w(TAG, "⚠️ Upload failed - will retry");
                return Result.retry(); // WorkManager will retry with exponential backoff
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error in UsageUploadWorker: " + e.getMessage(), e);
            return Result.retry();
        }
    }

    /**
     * Upload usage data to Firebase using SUsageDataManager
     * Returns true if successful, false otherwise
     */
    private boolean uploadUsageData(String deviceId) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean(false);

        try {
            SUsageDataManager usageManager = SUsageDataManager.getInstance(getApplicationContext());

            usageManager.uploadToFirebase(deviceId, new SUsageDataManager.OnUploadCompleteListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "✅ Usage data uploaded successfully");
                    success.set(true);
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "❌ Upload error: " + error);
                    success.set(false);
                    latch.countDown();
                }
            });

            // Wait for upload to complete (max 30 seconds)
            boolean completed = latch.await(30, TimeUnit.SECONDS);

            if (!completed) {
                Log.w(TAG, "⏰ Upload timed out after 30 seconds");
                return false;
            }

            return success.get();

        } catch (Exception e) {
            Log.e(TAG, "❌ Exception during upload: " + e.getMessage());
            return false;
        }
    }
}
