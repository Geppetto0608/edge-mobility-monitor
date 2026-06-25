package com.example.jetsonbtmonitor;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

public class SettingsActivity extends AppCompatActivity {
    private WheelchairApiClient apiClient;
    private TextView accountStateText;
    private TextView userProfileText;
    private TextView userLogsText;
    private MaterialButton loginButton;
    private MaterialButton logoutButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        apiClient = new WheelchairApiClient(AppConfig.JETSON_API_BASE_URL);

        accountStateText = findViewById(R.id.accountStateText);
        userProfileText = findViewById(R.id.userProfileText);
        userLogsText = findViewById(R.id.userLogsText);
        MaterialButton contactButton = findViewById(R.id.contactButton);
        loginButton = findViewById(R.id.loginButton);
        logoutButton = findViewById(R.id.logoutButton);

        renderAccount();
        loadUserLogs();
        contactButton.setOnClickListener(view -> openDialer());
        loginButton.setOnClickListener(view -> startActivity(new Intent(this, LoginActivity.class)));
        logoutButton.setOnClickListener(view -> {
            WheelchairBackgroundService.stop(this);
            UserSession.logout(this);
            Intent intent = new Intent(this, WifiSetupActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });
    }

    @Override
    protected void onDestroy() {
        apiClient.shutdown();
        super.onDestroy();
    }

    private void renderAccount() {
        boolean loggedIn = UserSession.isLoggedIn(this);
        accountStateText.setText(loggedIn ? R.string.account_logged_in : R.string.account_logged_out);
        userProfileText.setText(loggedIn ? profileText(UserSession.profile(this)) : getString(R.string.profile_empty));
        loginButton.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
        logoutButton.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
    }

    private void loadUserLogs() {
        String userId = UserSession.userId(this);
        if (userId.isEmpty()) {
            userLogsText.setText(R.string.account_logs_login_needed);
            return;
        }

        userLogsText.setText(R.string.account_logs_loading);
        apiClient.getUserLogs(userId, new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> userLogsText.setText(logsText(response.optJSONObject("logs"))));
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> userLogsText.setText("기록을 불러오지 못했습니다: " + message));
            }
        });
    }

    private String profileText(JSONObject profile) {
        StringBuilder builder = new StringBuilder();
        builder.append("아이디: ").append(profile.optString("user_id", "--")).append('\n');
        builder.append("이름: ").append(profile.optString("name", "--")).append('\n');
        builder.append("전화번호: ").append(profile.optString("phone", "--")).append('\n');
        builder.append("키: ").append(profile.optString("height_cm", "--")).append(" cm\n");
        builder.append("몸무게: ").append(profile.optString("weight_kg", "--")).append(" kg\n");
        builder.append("긴급사항: ").append(profile.optString("notes", "--"));
        return builder.toString();
    }

    private String logsText(JSONObject logs) {
        if (logs == null) {
            return getString(R.string.account_logs_empty);
        }

        StringBuilder builder = new StringBuilder();
        appendLogGroup(builder, "로그인", logs.optJSONArray("login_logs"));
        appendLogGroup(builder, "사용", logs.optJSONArray("usage_logs"));
        if (builder.length() == 0) {
            return getString(R.string.account_logs_empty);
        }
        return builder.toString().trim();
    }

    private void appendLogGroup(StringBuilder builder, String title, JSONArray logs) {
        if (logs == null || logs.length() == 0) {
            return;
        }
        builder.append(title).append('\n');
        int start = Math.max(0, logs.length() - 3);
        for (int i = logs.length() - 1; i >= start; i--) {
            JSONObject log = logs.optJSONObject(i);
            if (log == null) {
                continue;
            }
            builder.append("- ")
                    .append(log.optString("type", title))
                    .append(" / ")
                    .append(log.optString("time", "--"));
            String mode = log.optString("mode", "");
            if (!mode.isEmpty()) {
                builder.append(" / ").append(mode);
            }
            if (log.has("step_level")) {
                builder.append(" / ").append(log.optInt("step_level")).append("단계");
            }
            builder.append('\n');
        }
        builder.append('\n');
    }

    private void openDialer() {
        Intent intent = new Intent(Intent.ACTION_DIAL);
        intent.setData(Uri.parse("tel:" + AppConfig.SUPPORT_PHONE));
        startActivity(intent);
    }
}
