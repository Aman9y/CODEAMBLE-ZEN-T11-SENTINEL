package online.monarchlabs.sentinel.services;

import android.util.Log;
import online.monarchlabs.sentinel.config.AppwriteConfig;
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

/**
 * OTP Service for handling email-based OTP verification
 * Uses Appwrite backend services for secure OTP management
 */
public class OTPService {
    
    private static final String TAG = "OTPService";
    private static final int OTP_LENGTH = 6;
    private static final long OTP_VALIDITY_MINUTES = 5; // OTP valid for 5 minutes
    
    private AppwriteConfig appwriteConfig;
    private ParentOtpLoginService parentOtpLoginService;
    private Gson gson;
    
    // In-memory OTP storage for demo purposes
    // In production, use a proper database like Appwrite
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
        // Initialize Appwrite config
        try {
            this.appwriteConfig = AppwriteConfig.getInstance(context);
            Log.d(TAG, "🚀 OTPService initialized with Appwrite backend");
        } catch (Exception e) {
            Log.w(TAG, "Appwrite config not available, falling back to in-memory storage: " + e.getMessage());
            this.appwriteConfig = null;
        }
        this.gson = new Gson();
        this.parentOtpLoginService = new ParentOtpLoginService(context);
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
                    // Verification remains client-side; store only after delivery succeeds.
                    long expirationTime = System.currentTimeMillis() + (OTP_VALIDITY_MINUTES * 60 * 1000);
                    storeOTPInMemory(email, otp, expirationTime, userType);
                    Log.d(TAG, "OTP email sent through rate-limited Appwrite function to: " + email);
                    return new OTPResult(true, sendResult.message, "rate_limited_function", null, 0L);
                }

                Log.w(TAG, "OTP send rejected: " + sendResult.message);
                return new OTPResult(false, sendResult.message, null, null, sendResult.retryAfterSeconds);
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to send OTP: " + e.getMessage(), e);
                return new OTPResult(false, "Failed to send OTP: " + e.getMessage(), null, e);
            }
        });
    }
    
    /**
     * Send email via Appwrite Function using HTTP request
     */
    private boolean sendEmailViaAppwriteFunction(AppwriteConfig config, Map<String, Object> emailData) {
        // Try multiple authentication methods
        
        // Method 1: Try with API key
        boolean result = attemptFunctionCall(config, emailData, true);
        if (result) return true;
        
        // Method 2: Try without API key (public function)
        Log.d(TAG, "🔄 Retrying function call without API key (public execution)...");
        return attemptFunctionCall(config, emailData, false);
    }
    
    private boolean attemptFunctionCall(AppwriteConfig config, Map<String, Object> emailData, boolean useApiKey) {
        try {
            // Construct Appwrite Functions execution endpoint
            String functionUrl = config.getEndpoint() + "/functions/" + config.getEmailFunctionId() + "/executions";
            URL url = new URL(functionUrl);
            
            Log.d(TAG, "🔗 Calling Appwrite function at: " + functionUrl + (useApiKey ? " (with API key)" : " (public)"));
            
            // Create HTTP connection
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Appwrite-Project", config.getProjectId());
            
            // Do NOT send server API keys from the client. Appwrite Functions should
            // be secured via Appwrite permissions or invoked from trusted backend.
            
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            
            // Prepare function execution payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("body", gson.toJson(emailData));
            payload.put("async", false);
            payload.put("path", "/");
            payload.put("method", "POST");
            payload.put("headers", new HashMap<>());
            
            String jsonPayload = gson.toJson(payload);
            Log.d(TAG, "📤 Sending payload: " + jsonPayload);
            
            // Send request
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            // Get response
            int responseCode = conn.getResponseCode();
            String responseMessage = conn.getResponseMessage();
            
            Log.d(TAG, "📥 HTTP Response Code: " + responseCode);
            Log.d(TAG, "📥 HTTP Response Message: " + responseMessage);
            
            // Read response body for debugging
            String responseBody = "";
            try {
                java.io.InputStream inputStream = (responseCode >= 200 && responseCode < 300) 
                    ? conn.getInputStream() 
                    : conn.getErrorStream();
                
                if (inputStream != null) {
                    java.util.Scanner scanner = new java.util.Scanner(inputStream, "UTF-8");
                    responseBody = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
                    scanner.close();
                    Log.d(TAG, "📥 Response Body: " + responseBody);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read response body: " + e.getMessage());
            }
            
            // Handle specific error cases for retry logic
            if (responseCode == 401 && useApiKey) {
                Log.w(TAG, "🔄 API key authentication failed, will try public access");
                return false; // Let the parent method retry without API key
            }
            
            if (responseCode == 404) {
                Log.e(TAG, "❌ Function not found - please check function ID: " + config.getEmailFunctionId());
                return false;
            } else if (responseCode == 403) {
                Log.e(TAG, "❌ Forbidden - check your project permissions");
                return false;
            }
            
            boolean success = (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED);
            if (success) {
                Log.i(TAG, "✅ Email function executed successfully!" + (useApiKey ? " (with API key)" : " (public)"));
            } else {
                Log.e(TAG, "❌ Function execution failed with code: " + responseCode + " - " + responseMessage);
                Log.e(TAG, "❌ Function URL: " + functionUrl);
                Log.e(TAG, "❌ Response body: " + responseBody);
                Log.e(TAG, "❌ Project ID: " + config.getEndpoint());
                Log.e(TAG, "❌ Function ID: " + config.getEmailFunctionId());
            }
            
            return success;
            
        } catch (IOException e) {
            Log.e(TAG, "❌ Network error calling Appwrite function: " + e.getMessage(), e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "❌ Error calling Appwrite function: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Fallback method for sending OTP when Appwrite fails
     * This simulates email sending for development/testing purposes
     */
     private OTPResult sendOTPFallback(String email, String otp, String userType) {
        try {
            Log.d(TAG, "📧 FALLBACK: Email function not available, using development mode so pls see what is the prolem and fix that");
            Log.i(TAG, "🔑 DEVELOPMENT MODE - Your OTP is: " + otp + " (Valid for " + OTP_VALIDITY_MINUTES + " minutes)");
            Log.i(TAG, "📧 Email would be sent to: " + email);
            Log.i(TAG, "👤 User type: " + userType);
            Log.w(TAG, "⚠️ To enable real email sending:");
            Log.w(TAG, "   1. Deploy the email function to Appwrite");
            Log.w(TAG, "   2. Configure email provider (Gmail/Outlook)"); 
            Log.w(TAG, "   3. Set EMAIL_USER and EMAIL_PASS environment variables");
            Log.w(TAG, "   4. See backend/appwrite/parent-email-otp-login/README.md for instructions");
            
            return new OTPResult(true, 
                "DEVELOPMENT MODE - OTP: " + otp + " (Deploy email function for real emails)", 
                "fallback_method", null);
                
        } catch (Exception e) {
            Log.e(TAG, "❌ Even fallback method failed: " + e.getMessage(), e);
            return new OTPResult(false, "All email sending methods failed: " + e.getMessage(), null, e);
        }
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
