package online.monarchlabs.sentinel;

/**
 * Central gate to control which notifications are allowed to be shown.
 * Only allow: TIMER_EXPIRED (permanent until next-day refresh) and LIVE_TIMER.
 * Everything else should check this gate and avoid posting notifications.
 */
public final class NotificationGate {
    public enum Type {
        TIMER_EXPIRED,
        LIVE_TIMER,
        OTHER
    }

    private NotificationGate() {}

    public static boolean allow(Type type) {
        return type == Type.TIMER_EXPIRED || type == Type.LIVE_TIMER;
    }
}


