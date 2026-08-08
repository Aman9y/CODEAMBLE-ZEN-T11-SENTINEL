package online.monarchlabs.sentinel.assistant.parser;

public class TimeRange {
    private final int startMinutes;
    private final int endMinutes;

    public TimeRange(int startMinutes, int endMinutes) {
        this.startMinutes = startMinutes;
        this.endMinutes = endMinutes;
    }

    public int getStartMinutes() {
        return startMinutes;
    }

    public int getEndMinutes() {
        return endMinutes;
    }

    public String getDisplayText() {
        return formatMinutes(startMinutes) + " to " + formatMinutes(endMinutes);
    }

    private static String formatMinutes(int minutes) {
        int hour24 = minutes / 60;
        int minute = minutes % 60;
        String suffix = hour24 >= 12 ? "PM" : "AM";
        int hour12 = hour24 % 12;
        if (hour12 == 0) {
            hour12 = 12;
        }
        return String.format("%d:%02d %s", hour12, minute, suffix);
    }
}
