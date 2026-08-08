package online.monarchlabs.sentinel.utils;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Data Security & Privacy Helper for Sentinel.
 * 
 * Provides:
 * 1. Salted HMAC-SHA256 hashing for fast O(1) lookups (e.g. phone_to_email directory)
 *    without exposing raw phone numbers or emails in Firebase.
 * 2. AES-256-GCM field-level encryption/decryption for PII data (name, email, phone)
 *    stored in Firebase Realtime Database.
 */
public final class DataSecurityUtils {
    private static final String TAG = "DataSecurityUtils";
    
    // Pepper salt for HMAC-SHA256 lookup hashes
    private static final String LOOKUP_SALT = "Sentinel.Security.Pepper.v1.2026.LookupHash";
    
    // 256-bit AES master key seed for field encryption
    private static final byte[] AES_KEY_BYTES = deriveAesKey("Sentinel.FieldEncryptionKey.v1.MasterPassphrase");
    
    private static final String ENC_PREFIX = "enc_v1:";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private DataSecurityUtils() {
        // Utility class
    }

    /**
     * Computes a deterministic 64-character hex HMAC-SHA256 hash for lookup keys
     * (e.g. phone_to_email directory lookups).
     * 
     * @param input Raw phone number or email string
     * @return Deterministic hex hash string
     */
    public static String hashLookupKey(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "";
        }
        String cleanInput = input.trim().toLowerCase(Locale.US);
        try {
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(LOOKUP_SALT.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(secretKey);
            byte[] hashBytes = sha256Hmac.doFinal(cleanInput.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (Exception e) {
            Log.e(TAG, "HMAC-SHA256 calculation failed: " + e.getMessage());
            return cleanInput;
        }
    }

    /**
     * Encrypts plaintext using AES-256-GCM.
     * 
     * @param plaintext Sensitive string (e.g. email, phone, name)
     * @return Encrypted string formatted as enc_v1:BASE64_IV:BASE64_CIPHERTEXT
     */
    public static String encryptText(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        if (plaintext.startsWith(ENC_PREFIX)) {
            // Already encrypted
            return plaintext;
        }

        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(AES_KEY_BYTES, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            String base64Iv = Base64.encodeToString(iv, Base64.NO_WRAP);
            String base64Cipher = Base64.encodeToString(cipherBytes, Base64.NO_WRAP);

            return ENC_PREFIX + base64Iv + ":" + base64Cipher;
        } catch (Exception e) {
            Log.e(TAG, "AES-256-GCM encryption failed: " + e.getMessage());
            return plaintext;
        }
    }

    /**
     * Decrypts an enc_v1: string back to readable plaintext.
     * Passes through unencrypted strings gracefully for backward compatibility.
     * 
     * @param cipherText Formatted encrypted string or legacy plaintext
     * @return Decrypted string
     */
    public static String decryptText(String cipherText) {
        if (cipherText == null || !cipherText.startsWith(ENC_PREFIX)) {
            // Return as-is (legacy unencrypted format)
            return cipherText;
        }

        try {
            String payload = cipherText.substring(ENC_PREFIX.length());
            String[] parts = payload.split(":");
            if (parts.length != 2) {
                return cipherText;
            }

            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encryptedBytes = Base64.decode(parts[1], Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(AES_KEY_BYTES, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "AES-256-GCM decryption failed: " + e.getMessage());
            return cipherText;
        }
    }

    private static byte[] deriveAesKey(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            byte[] key = new byte[32];
            byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(inputBytes, 0, key, 0, Math.min(inputBytes.length, 32));
            return key;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
