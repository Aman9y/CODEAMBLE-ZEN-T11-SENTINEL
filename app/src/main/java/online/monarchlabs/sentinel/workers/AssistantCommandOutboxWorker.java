package online.monarchlabs.sentinel.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import online.monarchlabs.sentinel.assistant.execution.AssistantCommandOutbox;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AssistantCommandOutboxWorker extends Worker {
    private static final String TAG = "AssistantOutboxWorker";

    public AssistantCommandOutboxWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        AssistantCommandOutbox outbox = new AssistantCommandOutbox(getApplicationContext());
        List<AssistantCommandOutbox.PendingCommand> pendingCommands = outbox.getAll();
        if (pendingCommands.isEmpty()) {
            return Result.success();
        }

        boolean allProcessed = true;
        for (AssistantCommandOutbox.PendingCommand pendingCommand : pendingCommands) {
            try {
                if (pendingCommand == null || pendingCommand.childId == null || pendingCommand.commandId == null) {
                    continue;
                }

                DatabaseReference commandRef = FirebaseDatabase.getInstance()
                        .getReference("v2")
                        .child("commands")
                        .child(pendingCommand.childId)
                        .child(pendingCommand.commandId);

                if (remoteCommandExists(commandRef)) {
                    outbox.remove(pendingCommand.commandId);
                    continue;
                }

                if (!writeCommand(commandRef, pendingCommand.firebasePayload)) {
                    allProcessed = false;
                } else {
                    outbox.remove(pendingCommand.commandId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed while processing assistant outbox entry: " + e.getMessage(), e);
                allProcessed = false;
            }
        }

        return allProcessed ? Result.success() : Result.retry();
    }

    private boolean remoteCommandExists(DatabaseReference commandRef) {
        try {
            DataSnapshot snapshot = Tasks.await(commandRef.get(), 10, TimeUnit.SECONDS);
            return snapshot != null && snapshot.exists();
        } catch (Exception e) {
            Log.w(TAG, "Could not verify remote command state; will retry later: " + e.getMessage());
            return false;
        }
    }

    private boolean writeCommand(DatabaseReference commandRef, Map<String, Object> payload) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        commandRef.setValue(payload)
                .addOnSuccessListener(ignored -> {
                    success.set(true);
                    latch.countDown();
                })
                .addOnFailureListener(error -> {
                    Log.w(TAG, "Outbox retry failed to write command: " + error.getMessage());
                    success.set(false);
                    latch.countDown();
                });

        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                Log.w(TAG, "Outbox retry timed out while writing command");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        return success.get();
    }
}