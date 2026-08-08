package online.monarchlabs.sentinel.services;

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

import online.monarchlabs.sentinel.BuildConfig;

public final class NextDnsWorkerService {
    private static final String TAG = "NextDnsWorkerService";

    private final Gson gson = new Gson();

    public NextDnsWorkerService(Context context) {
    }

    public CompletableFuture<Result> addDenylistDomain(String deviceId, String profileId, String domain) {
        return execute("addDenylistDomain", deviceId, profileId, domain);
    }

    public CompletableFuture<Result> removeDenylistDomain(String deviceId, String profileId, String domain) {
        return execute("removeDenylistDomain", deviceId, profileId, domain);
    }

    private CompletableFuture<Result> execute(String action, String deviceId, String profileId, String domain) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            future.complete(new Result(false, "Please sign in again."));
            return future;
        }

        FirebaseAuth.getInstance().getCurrentUser().getIdToken(false)
                .addOnSuccessListener(tokenResult -> CompletableFuture
                        .supplyAsync(() -> executeRequest(action, deviceId, profileId, domain, tokenResult.getToken()))
                        .thenAccept(future::complete)
                        .exceptionally(error -> {
                            future.complete(new Result(false, error.getMessage()));
                            return null;
                        }))
                .addOnFailureListener(error -> future.complete(new Result(false,
                        error.getMessage() == null ? "Could not authenticate the request." : error.getMessage())));
        return future;
    }

    private Result executeRequest(String action, String deviceId, String profileId, String domain, String idToken) {
        HttpURLConnection connection = null;
        try {
            if (BuildConfig.CLOUDFLARE_NEXTDNS_WORKER_URL == null
                    || BuildConfig.CLOUDFLARE_NEXTDNS_WORKER_URL.isEmpty()) {
                return new Result(false, "NextDNS worker URL is not configured.");
            }

            URL url = new URL(BuildConfig.CLOUDFLARE_NEXTDNS_WORKER_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-Sentinel-Client-Secret", BuildConfig.CLOUDFLARE_CLIENT_SECRET);
            connection.setDoOutput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);

            Map<String, Object> body = new HashMap<>();
            body.put("action", action);
            body.put("firebaseIdToken", idToken);
            body.put("deviceId", deviceId);
            body.put("profileId", profileId);
            body.put("domain", domain);

            try (OutputStream output = connection.getOutputStream()) {
                output.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            String responseBody = readResponse(connection, responseCode);
            if (responseCode < 200 || responseCode >= 300) {
                return new Result(false, readMessage(responseBody, "NextDNS worker request failed."));
            }

            JsonObject result = JsonParser.parseString(responseBody).getAsJsonObject();
            boolean success = result.has("success") && result.get("success").getAsBoolean();
            return new Result(success, readMessage(responseBody, success ? "" : "NextDNS update failed."));
        } catch (Exception error) {
            Log.e(TAG, "NextDNS worker call failed", error);
            return new Result(false, error.getMessage() == null ? "NextDNS worker failed." : error.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readResponse(HttpURLConnection connection, int responseCode) throws Exception {
        java.io.InputStream stream = responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (stream == null) {
            return "";
        }
        try (Scanner scanner = new Scanner(stream, "UTF-8")) {
            return scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
        }
    }

    private String readMessage(String responseBody, String fallback) {
        try {
            JsonObject object = JsonParser.parseString(responseBody).getAsJsonObject();
            if (object.has("message") && !object.get("message").isJsonNull()) {
                return object.get("message").getAsString();
            }
        } catch (Exception ignored) {
        }
        return fallback;
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
