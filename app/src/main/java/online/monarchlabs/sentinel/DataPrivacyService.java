package online.monarchlabs.sentinel;

import android.content.Context;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
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

/** Calls the Cloudflare privacy service using a verified Firebase ID token. */
public final class DataPrivacyService {
    private static final String TAG = "DataPrivacyService";

    private final Gson gson = new Gson();
    private final android.content.Context context;

    public DataPrivacyService(Context context) {
        this.context = context;
    }

    public CompletableFuture<Result> deleteCurrentAccount() {
        com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            return CompletableFuture.completedFuture(new Result(false, "Please sign in again."));
        }
        
        CompletableFuture<Result> future = new CompletableFuture<>();
        String uid = user.getUid();
        
        // 1. Fetch the user's phone number to delete the index
        com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("parent_profiles")
                .child(uid)
                .child("phone")
                .get()
                .addOnSuccessListener(snapshot -> {
                    String phone = snapshot.getValue(String.class);
                    if (phone != null && !phone.isEmpty()) {
                        // 2. Delete the mapping
                        new online.monarchlabs.sentinel.services.ParentDirectoryService(context)
                                .deleteMapping(phone)
                                .thenAccept(ignored -> proceedWithDeletion(future));
                    } else {
                        proceedWithDeletion(future);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Failed to read phone for cleanup, proceeding with deletion anyway", e);
                    proceedWithDeletion(future);
                });
                
        return future;
    }
    
    private void proceedWithDeletion(CompletableFuture<Result> future) {
        executeAuthenticated("deleteAccount", new HashMap<>())
                .thenAccept(future::complete)
                .exceptionally(error -> {
                    future.complete(new Result(false, error.getMessage()));
                    return null;
                });
    }

    public CompletableFuture<Result> recordParentConsent(String eventId, String parentDeviceId) {
        Map<String, Object> data = new HashMap<>();
        data.put("eventId", eventId);
        data.put("parentDeviceId", parentDeviceId);
        return executeAuthenticated("recordConsent", data);
    }

    private CompletableFuture<Result> executeAuthenticated(String action, Map<String, Object> data) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            future.complete(new Result(false, "Please sign in again."));
            return future;
        }

        FirebaseAuth.getInstance().getCurrentUser().getIdToken(false)
                .addOnSuccessListener(tokenResult -> CompletableFuture
                        .supplyAsync(() -> execute(action, data, tokenResult.getToken()))
                        .thenAccept(future::complete)
                        .exceptionally(error -> {
                            future.complete(new Result(false, error.getMessage()));
                            return null;
                        }))
                .addOnFailureListener(error -> future.complete(new Result(false,
                        error.getMessage() == null ? "Could not authenticate the request." : error.getMessage())));
        return future;
    }

    private Result execute(String action, Map<String, Object> data, String idToken) {
        HttpURLConnection connection = null;
        try {
            if (BuildConfig.CLOUDFLARE_PRIVACY_WORKER_URL == null || BuildConfig.CLOUDFLARE_PRIVACY_WORKER_URL.isEmpty()) {
                return new Result(false, "Cloudflare Privacy service URL is not configured.");
            }
            URL url = new URL(BuildConfig.CLOUDFLARE_PRIVACY_WORKER_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-Sentinel-Client-Secret", BuildConfig.CLOUDFLARE_CLIENT_SECRET);
            connection.setDoOutput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);

            Map<String, Object> functionBody = new HashMap<>(data);
            functionBody.put("action", action);
            functionBody.put("firebaseIdToken", idToken);

            try (OutputStream output = connection.getOutputStream()) {
                output.write(gson.toJson(functionBody).getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            String responseBody = readResponse(connection, responseCode);
            if (responseCode < 200 || responseCode >= 300) {
                if (!responseBody.isEmpty()) {
                    try {
                        JsonObject errorObj = JsonParser.parseString(responseBody).getAsJsonObject();
                        if (errorObj.has("message")) {
                            return new Result(false, errorObj.get("message").getAsString());
                        }
                    } catch (Exception ignored) {}
                }
                return new Result(false, "Privacy service request failed: HTTP " + responseCode);
            }

            JsonObject result = JsonParser.parseString(responseBody).getAsJsonObject();
            return new Result(result.has("success") && result.get("success").getAsBoolean(),
                    getString(result, "message"));
        } catch (Exception error) {
            Log.e(TAG, "Privacy function call failed", error);
            return new Result(false, error.getMessage() == null ? "Privacy service failed." : error.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readResponse(HttpURLConnection connection, int responseCode) throws Exception {
        java.io.InputStream stream = responseCode >= 200 && responseCode < 300
                ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) return "";
        try (Scanner scanner = new Scanner(stream, "UTF-8")) {
            return scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
        }
    }

    private String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return null;
        return object.get(key).getAsString();
    }

    public static final class Result {
        public final boolean success;
        public final String message;

        Result(boolean success, String message) {
            this.success = success;
            this.message = message == null ? "" : message;
        }
    }
}
