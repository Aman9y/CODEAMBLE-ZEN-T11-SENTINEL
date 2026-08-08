package online.monarchlabs.sentinel.services;

import android.util.Log;
import online.monarchlabs.sentinel.BuildConfig;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

/**
 * OTP Service for handling email-based OTP verification
 * Uses Cloudflare Workers for secure OTP management
 */
public class OTPService {
    
    private static final String TAG = "OTPService";
    private static final int OTP_LENGTH = 6;
    private static final long OTP_VALIDITY_MINUTES = 5; // OTP valid for 5 minutes
    
    private ParentOtpLoginService parentOtpLoginService;
    private Gson gson;
    
    // In-memory OTP storage for tracking sent codes locally
    private static final Map<String, OTPData> otpStorage = new HashMap<>();
    
    private static class OTPData {
        String otp;
        long expirationTime;
        String userType;
        boolean used;
        
        OTPData(String otp, long expirationTime, String userType) {
            this.otp = otp;
            this.expirationTime = expirationTime;
            this.userType = userType;
            this.used = false;
        }
    }
    
    public OTPService(android.content.Context context) {
        this.gson = new Gson();
        this.parentOtpLoginService = new ParentOtpLoginService(context);
        Log.d(TAG, "OTPService initialized with Cloudflare backend");
    }
    
    /**
     * Generate a secure 6-digit OTP
     */
    private String generateOTP() {
        SecureRandom random = new SecureRandom();
        StringBuilder otp = new StringBuilder();
        
        for (int i = 0; i < OTP_LENGTH; i++) {
            otp.append(random.nextInt(10));
        }
        
        return otp.toString();
    }
    
    /**
     * Store OTP in memory for verification (demo purposes)
     */
    private void storeOTPInMemory(String email, String otp, long expirationTime, String userType) {
        // Remove any existing OTP for this email
        otpStorage.remove(email);
        
        // Store new OTP
        otpStorage.put(email, new OTPData(otp, expirationTime, userType));
        
        Log.d(TAG, "💾 OTP stored in memory for email: " + email);
    }
    
    /**
     * Send OTP to email address
     * @param email The email address to send OTP to
     * @param userType Type of user (parent/child)
     * @return CompletableFuture with success status
     */
    public CompletableFuture<OTPResult> sendOTP(String email, String userType) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Log.d(TAG, "🔄 Generating OTP for email: " + email);
                
                // Generate new OTP
                String otp = generateOTP();
                Log.d(TAG, "📧 Generated OTP: " + otp + " (expires in " + OTP_VALIDITY_MINUTES + " minutes)");
                
                ParentOtpLoginService.Result sendResult = parentOtpLoginService
                        .sendSignupOtp(email, otp, userType)
                        .get();
                if (sendResult.success) {
                    long expirationTime = System.currentTimeMillis() + (OTP_VALIDITY_MINUTES * 60 * 1000);
                    storeOTPInMemory(email, otp, expirationTime, userType);
                    Log.d(TAG, "OTP email sent through Cloudflare to: " + email);
                    return new OTPResult(true, "Verification code sent.", "cloudflare", null, 0L);
                }

                Log.w(TAG, "Cloudflare OTP send rejected: " + sendResult.message);
                return new OTPResult(false, sendResult.message, null, null, sendResult.retryAfterSeconds);
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to send OTP: " + e.getMessage(), e);
                return new OTPResult(false, "Failed to send OTP: " + e.getMessage(), null, e);
            }
        });
    }
    

    /**
     * Verify the provided OTP
     * @param email Email address
     * @param otp OTP code to verify
     * @return CompletableFuture with verification result
     */
    public CompletableFuture<OTPResult> verifyOTP(String email, String otp) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Log.d(TAG, "🔍 Verifying OTP for email: " + email);
                Log.d(TAG, "🔑 OTP provided: " + otp);
                
                // Check if OTP exists in memory storage
                OTPData storedOTPData = otpStorage.get(email);
                
                if (storedOTPData == null) {
                    Log.w(TAG, "❌ No OTP found for email: " + email);
                    return new OTPResult(false, "No OTP found for this email. Please request a new OTP.", null, null);
                }
                
                // Check if OTP is already used
                if (storedOTPData.used) {
                    Log.w(TAG, "❌ OTP already used for email: " + email);
                    return new OTPResult(false, "OTP has already been used. Please request a new OTP.", null, null);
                }
                
                // Check if OTP has expired
                long currentTime = System.currentTimeMillis();
                if (currentTime > storedOTPData.expirationTime) {
                    Log.w(TAG, "⏰ OTP expired for email: " + email + " (" + ((currentTime - storedOTPData.expirationTime) / 1000) + " seconds ago)");
                    return new OTPResult(false, "OTP has expired. Please request a new OTP.", null, null);
                }
                
                // Verify OTP matches
                if (!storedOTPData.otp.equals(otp)) {
                    Log.w(TAG, "❌ OTP mismatch for email: " + email + " - Expected: " + storedOTPData.otp + ", Got: " + otp);
                    return new OTPResult(false, "Invalid OTP. Please check and try again.", null, null);
                }
                
                // Mark OTP as used
                storedOTPData.used = true;
                
                Log.d(TAG, "🎉 OTP verification successful for: " + email);
                return new OTPResult(true, "OTP verified successfully!", "memory_storage", null);
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Error during OTP verification: " + e.getMessage(), e);
                return new OTPResult(false, "Verification failed: " + e.getMessage(), null, e);
            }
        });
    }
    
    /**
     * Resend OTP to email address
     * @param email Email address
     * @param userType User type
     * @return CompletableFuture with result
     */
    public CompletableFuture<OTPResult> resendOTP(String email, String userType) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Send new OTP
                return sendOTP(email, userType).get();
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to resend OTP: " + e.getMessage(), e);
                return new OTPResult(false, "Failed to resend OTP", null, e);
            }
        });
    }
    
    /**
     * Invalidate existing OTPs for an email
     */
    private void invalidateExistingOTPs(String email) {
        Log.d(TAG, "🧹 Invalidating existing OTPs for email: " + email);
        
        // Remove any existing OTP for this email from memory storage
        OTPData existingOTP = otpStorage.remove(email);
        if (existingOTP != null) {
            Log.d(TAG, "✅ Invalidated existing OTP for: " + email);
        } else {
            Log.d(TAG, "📭 No existing OTP found to invalidate for: " + email);
        }
    }
    
    /**
     * Result class for OTP operations
     */
    public static class OTPResult {
        private final boolean success;
        private final String message;
        private final String documentId;
        private final Exception error;
        private final long retryAfterSeconds;
        
        public OTPResult(boolean success, String message, String documentId, Exception error) {
            this(success, message, documentId, error, 0L);
        }

        public OTPResult(boolean success, String message, String documentId, Exception error,
                long retryAfterSeconds) {
            this.success = success;
            this.message = message;
            this.documentId = documentId;
            this.error = error;
            this.retryAfterSeconds = retryAfterSeconds;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getMessage() {
            return message;
        }
        
        public String getDocumentId() {
            return documentId;
        }
        
        public Exception getError() {
            return error;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
