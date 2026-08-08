package online.monarchlabs.sentinel.utils;

import android.text.TextUtils;

public final class PhoneUtils {
    private PhoneUtils() {
        // utility class
    }

    /**
     * Normalizes a phone number to a consistent 10-digit format for consistent indexing and lookup.
     * Strips country codes (+91, 0, etc.) so typing 7304377739 or +917304377739 produces the same result.
     * Returns empty string if the phone number is invalid.
     */
    public static String normalize(String phone) {
        if (TextUtils.isEmpty(phone)) {
            return "";
        }
        
        // Strip everything except digits
        String normalized = phone.replaceAll("[^0-9]", "");
        
        // Strip common country code prefixes (e.g. 91 for India, 0 for local)
        if (normalized.length() == 12 && normalized.startsWith("91")) {
            normalized = normalized.substring(2);
        } else if (normalized.length() == 11 && (normalized.startsWith("0") || normalized.startsWith("1"))) {
            normalized = normalized.substring(1);
        } else if (normalized.length() > 10) {
            normalized = normalized.substring(normalized.length() - 10);
        }
        
        // A minimal valid phone number length is generally 10 digits
        if (normalized.length() < 10) {
            return "";
        }
        
        return normalized;
    }

    /**
     * Returns raw digits-only string without 10-digit stripping (e.g. 917304377739).
     */
    public static String rawDigits(String phone) {
        if (TextUtils.isEmpty(phone)) {
            return "";
        }
        return phone.replaceAll("[^0-9]", "");
    }

    /**
     * Checks if a phone number is valid.
     */
    public static boolean isValid(String phone) {
        return !normalize(phone).isEmpty();
    }
}
