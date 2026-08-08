package online.monarchlabs.sentinel.services;

import android.content.Context;
import android.util.Log;

import online.monarchlabs.sentinel.config.AppwriteConfig;
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

    private final AppwriteConfig config;
    private final Gson gson = new Gson();

    public ParentOtpLoginService(Context context) {
        this.config = AppwriteConfig.getInstance(context);
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
                String functionUrl = config.getEndpoint() + "/functions/" + config.getParentOtpFunctionId()
                        + "/executions";
                URL url = new URL(functionUrl);

                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Appwrite-Project", config.getProjectId());
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);

                Map<String, Object> executionPayload = new HashMap<>();
                executionPayload.put("body", gson.toJson(bodyData));
                executionPayload.put("async", false);
                executionPayload.put("path", "/");
                executionPayload.put("method", "POST");
                executionPayload.put("headers", new HashMap<>());

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(gson.toJson(executionPayload).getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                String responseBody = readResponse(conn, responseCode);
                if (responseCode < 200 || responseCode >= 300) {
                    Log.e(TAG, "Appwrite parent OTP function failed: " + responseBody);
                    return new Result(false, "OTP login service failed. Please try again.", null);
                }

                JsonObject execution = JsonParser.parseString(responseBody).getAsJsonObject();
                String response = getString(execution, "responseBody");
                if (response == null || response.isEmpty()) {
                    response = getString(execution, "response");
                }
                if (response == null || response.isEmpty()) {
                    return new Result(false, "OTP login service returned an empty response.", null);
                }

                JsonObject result = JsonParser.parseString(response).getAsJsonObject();
                boolean success = result.has("success") && result.get("success").getAsBoolean();
                String message = getString(result, "message");
                String customToken = getString(result, "customToken");
                long retryAfterSeconds = result.has("retryAfterSeconds")
                        ? result.get("retryAfterSeconds").getAsLong()
                        : 0L;
                return new Result(success, message != null ? message : "", customToken, retryAfterSeconds);
            } catch (Exception e) {
                Log.e(TAG, "Error calling parent OTP login function", e);
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
