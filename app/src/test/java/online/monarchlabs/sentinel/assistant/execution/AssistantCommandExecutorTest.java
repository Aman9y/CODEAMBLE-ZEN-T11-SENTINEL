package online.monarchlabs.sentinel.assistant.execution;

import com.google.firebase.database.ValueEventListener;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AssistantCommandExecutorTest {
    @Test
    public void offlineChildTriggersTimeoutAndCleansUpListener() {
        FakeTimeoutScheduler scheduler = new FakeTimeoutScheduler();
        AssistantCommandExecutor executor = new AssistantCommandExecutor(null, scheduler);
        FakeAckEndpoint ackEndpoint = new FakeAckEndpoint();
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> acks = new ArrayList<>();

        AssistantCommandExecutor.ExecutionCallback callback = new AssistantCommandExecutor.ExecutionCallback() {
            @Override
            public void onQueued(SentinelCommand command) {
            }

            @Override
            public void onAck(Map<String, Object> ack) {
                acks.add(ack);
            }

            @Override
            public void onError(String message) {
                errors.add(message);
            }
        };

        executor.beginAckTracking("command-1", ackEndpoint, callback);

        assertNotNull(ackEndpoint.registeredListener);

        scheduler.fireTimeout();

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("Timeout: Child device did not respond within 30 seconds."));
        assertTrue(acks.isEmpty());
        assertEquals(1, ackEndpoint.removeListenerCount);
        assertTrue(ackEndpoint.removedListener == ackEndpoint.registeredListener);
    }

    private static class FakeAckEndpoint implements AssistantCommandExecutor.AckEndpoint {
        private ValueEventListener registeredListener;
        private ValueEventListener removedListener;
        private int removeListenerCount;

        @Override
        public void addValueEventListener(ValueEventListener listener) {
            this.registeredListener = listener;
        }

        @Override
        public void removeEventListener(ValueEventListener listener) {
            this.removedListener = listener;
            this.removeListenerCount++;
        }
    }

    private static class FakeTimeoutScheduler implements AssistantCommandExecutor.TimeoutScheduler {
        private Runnable scheduled;

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            this.scheduled = runnable;
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            if (this.scheduled == runnable) {
                this.scheduled = null;
            }
        }

        void fireTimeout() {
            if (scheduled != null) {
                Runnable runnable = scheduled;
                scheduled = null;
                runnable.run();
            }
        }
    }
}