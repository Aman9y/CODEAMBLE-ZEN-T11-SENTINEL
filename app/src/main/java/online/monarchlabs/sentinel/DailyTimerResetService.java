package online.monarchlabs.sentinel;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import online.monarchlabs.sentinel.workers.UsageUploadScheduler;

import java.util.Calendar;

/**
 * Schedules the v2 usage rollover. AppTimerService owns timer day rollover and
 * Firebase v2 policy reconciliation; no global database scan is performed here.
 */
public final class DailyTimerResetService extends Service {
    private static final String TAG = "DailyTimerResetService";
    private static final String ACTION_MIDNIGHT_ROLLOVER =
            "online.monarchlabs.sentinel.action.V2_MIDNIGHT_ROLLOVER";
    private static final int REQUEST_CODE = 4201;

    @Override
    public void onCreate() {
        super.onCreate();
        scheduleNextRollover();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null
                && ACTION_MIDNIGHT_ROLLOVER.equals(intent.getAction())) {
            SessionManager session = new SessionManager(this);
            if ("child".equalsIgnoreCase(session.getUserType())
                    && session.isConnectionActive()) {
                UsageUploadScheduler.triggerImmediateUpload(this);
                Log.d(TAG, "Queued v2 usage rollover upload");
            }
        }
        scheduleNextRollover();
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private void scheduleNextRollover() {
        AlarmManager alarmManager =
                (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        Calendar next = Calendar.getInstance();
        next.add(Calendar.DAY_OF_YEAR, 1);
        next.set(Calendar.HOUR_OF_DAY, 0);
        next.set(Calendar.MINUTE, 2);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);

        Intent intent = new Intent(this, DailyTimerResetService.class)
                .setAction(ACTION_MIDNIGHT_ROLLOVER);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getService(
                this, REQUEST_CODE, intent, pendingFlags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    next.getTimeInMillis(),
                    pendingIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    next.getTimeInMillis(),
                    pendingIntent);
        } else {
            alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    next.getTimeInMillis(),
                    pendingIntent);
        }
    }

    public static void startService(Context context) {
        try {
            context.startService(new Intent(context, DailyTimerResetService.class));
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not schedule rollover service", error);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}