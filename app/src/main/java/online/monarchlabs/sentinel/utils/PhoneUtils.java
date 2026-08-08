package online.monarchlabs.sentinel.utils;

import android.text.TextUtils;

public final class PhoneUtils {
    private PhoneUtils() {
        // utility class
    }

    /**
     * Normalizes a phone number to a digits-only format for consistent indexing and lookup.
     * Returns empty string if the phone number is invalid.
     */
    public static String normalize(String phone) {
        if (TextUtils.isEmpty(phone)) {
            return "";
        }
        
        // Strip everything except digits
        String normalized = phone.replaceAll("[^0-9]", "");
        
        // A minimal valid phone number length is generally 10 digits
        if (normalized.length() < 10) {
            return "";
        }
        
        return normalized;
    }

    /**
     * Checks if a phone number is valid.
     */
    public static boolean isValid(String phone) {
        return !normalize(phone).isEmpty();
    }
}
