package com.example.jetsonbtmonitor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class WheelchairApiClient {
    interface JsonCallback {
        void onSuccess(JSONObject response);

        void onError(String message, Throwable throwable);
    }

    private static final int TIMEOUT_MS = 2_000;

    private final String baseUrl;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    WheelchairApiClient(String baseUrl) {
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    void getHealth(JsonCallback callback) {
        get("/api/health", callback);
    }

    void getTelemetry(JsonCallback callback) {
        get("/api/telemetry", callback);
    }

    void getUserLogs(String userId, JsonCallback callback) {
        get("/api/users/logs?user_id=" + urlEncode(userId), callback);
    }

    void getUserPresets(String userId, JsonCallback callback) {
        get("/api/users/presets?user_id=" + urlEncode(userId), callback);
    }

    void saveUserPresets(String userId, org.json.JSONArray presets, String defaultPresetId, JsonCallback callback) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("user_id", userId);
            payload.put("presets", presets);
            payload.put("default_preset_id", defaultPresetId);
        } catch (JSONException ignored) {
            // JSONObject with stable preset values cannot fail here.
        }
        post("/api/users/presets", payload, callback);
    }

    void sendCommand(JSONObject payload, JsonCallback callback) {
        post("/api/command", payload, callback);
    }

    void login(String userId, String password, JsonCallback callback) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("user_id", userId);
            payload.put("password", password);
        } catch (JSONException ignored) {
            // JSONObject with string values cannot fail here.
        }
        post("/api/users/login", payload, callback);
    }

    void register(JSONObject profile, JsonCallback callback) {
        post("/api/users/register", profile, callback);
    }

    void logUsage(String userId, String type, JsonCallback callback) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("user_id", userId);
            payload.put("type", type);
        } catch (JSONException ignored) {
            // JSONObject with string values cannot fail here.
        }
        post("/api/users/usage", payload, callback);
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private void get(String path, JsonCallback callback) {
        executor.execute(() -> {
            try {
                callback.onSuccess(request("GET", path, null));
            } catch (ApiException exception) {
                callback.onError(exception.getMessage(), exception);
            } catch (IOException | JSONException exception) {
                callback.onError("Jetson Wi-Fi API에 연결할 수 없습니다.", exception);
            }
        });
    }

    private void post(String path, JSONObject payload, JsonCallback callback) {
        executor.execute(() -> {
            try {
                callback.onSuccess(request("POST", path, payload));
            } catch (ApiException exception) {
                callback.onError(exception.getMessage(), exception);
            } catch (IOException | JSONException exception) {
                callback.onError("Jetson Wi-Fi API 요청에 실패했습니다.", exception);
            }
        });
    }

    private JSONObject request(String method, String path, JSONObject payload) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");

        if (payload != null) {
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Content-Length", String.valueOf(body.length));
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
            }
        }

        int statusCode = connection.getResponseCode();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            JSONObject response = new JSONObject(builder.toString());
            if (statusCode >= 400) {
                throw new ApiException(statusCode, response.optString("error", "HTTP " + statusCode));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (IOException exception) {
            return "";
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static final class ApiException extends IOException {
        private ApiException(int statusCode, String message) {
            super(message == null || message.isEmpty() ? "HTTP " + statusCode : message);
        }
    }
}
