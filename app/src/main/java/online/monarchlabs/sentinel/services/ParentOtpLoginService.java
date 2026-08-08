package online.monarchlabs.sentinel.services;

import android.content.Context;
import android.util.Log;

import online.monarchlabs.sentinel.BuildConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

public class ParentOtpLoginService {
    private static final String TAG = "ParentOtpLoginService";

    private final Gson gson = new Gson();

    public ParentOtpLoginService(Context context) {
    }

    public CompletableFuture<Result> sendLoginOtp(String email) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "send");
        payload.put("email", email);
        return execute(payload);
    }

    public CompletableFuture<Result> verifyLoginOtp(String email, String otp) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "verify");
        payload.put("email", email);
        payload.put("otp", otp);
        return execute(payload);
    }

    public CompletableFuture<Result> sendSignupOtp(String email, String otp, String userType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "send-signup");
        payload.put("email", email);
        payload.put("otp", otp);
        payload.put("userType", userType);
        return execute(payload);
    }

    private CompletableFuture<Result> execute(Map<String, Object> bodyData) {
        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection conn = null;
            try {
                String workerUrl = BuildConfig.CLOUDFLARE_EMAIL_WORKER_URL;
                if (workerUrl == null || workerUrl.isEmpty()) {
                    Log.e(TAG, "Cloudflare Worker URL is not configured.");
                    return new Result(false, "OTP service is not configured.", null);
                }

                URL url = new URL(workerUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Sentinel-Client-Secret", BuildConfig.CLOUDFLARE_CLIENT_SECRET);
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(gson.toJson(bodyData).getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                String responseBody = readResponse(conn, responseCode);
                if (responseCode < 200 || responseCode >= 300) {
                    Log.e(TAG, "Cloudflare OTP service failed: " + responseBody);
                    String message = "OTP service failed. Please try again.";
                    try {
                        JsonObject errObj = JsonParser.parseString(responseBody).getAsJsonObject();
                        if (errObj.has("message")) {
                            message = errObj.get("message").getAsString();
                        }
                    } catch (Exception ignored) {}
                    return new Result(false, message, null);
                }

                JsonObject result = JsonParser.parseString(responseBody).getAsJsonObject();
                boolean success = result.has("success") && result.get("success").getAsBoolean();
                String message = getString(result, "message");
                String customToken = getString(result, "customToken");
                long retryAfterSeconds = result.has("retryAfterSeconds")
                        ? result.get("retryAfterSeconds").getAsLong()
                        : 0L;
                return new Result(success, message != null ? message : "", customToken, retryAfterSeconds);
            } catch (Exception e) {
                Log.e(TAG, "Error calling parent OTP service", e);
                return new Result(false, e.getMessage() != null ? e.getMessage() : "OTP login failed.", null);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private String readResponse(HttpURLConnection conn, int responseCode) throws Exception {
        java.io.InputStream stream = responseCode >= 200 && responseCode < 300
                ? conn.getInputStream()
                : conn.getErrorStream();
        if (stream == null) {
            return "";
        }
        try (Scanner scanner = new Scanner(stream, "UTF-8")) {
            return scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
        }
    }

    private String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    public static class Result {
        public final boolean success;
        public final String message;
        public final String customToken;
        public final long retryAfterSeconds;

        Result(boolean success, String message, String customToken) {
            this(success, message, customToken, 0L);
        }

        Result(boolean success, String message, String customToken, long retryAfterSeconds) {
            this.success = success;
            this.message = message;
            this.customToken = customToken;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}
