package com.example.jetsonbtmonitor;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

public class WifiSetupActivity extends AppCompatActivity {
    private WheelchairApiClient apiClient;
    private TextView wifiStatusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_setup);
        apiClient = new WheelchairApiClient(AppConfig.JETSON_API_BASE_URL);

        wifiStatusText = findViewById(R.id.wifiStatusText);
        MaterialButton openWifiSettingsButton = findViewById(R.id.openWifiSettingsButton);
        MaterialButton checkWifiButton = findViewById(R.id.checkWifiButton);

        openWifiSettingsButton.setOnClickListener(view -> openWifiSettings());
        checkWifiButton.setOnClickListener(view -> checkJetsonConnection());
    }

    @Override
    protected void onDestroy() {
        apiClient.shutdown();
        super.onDestroy();
    }

    private void openWifiSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            intent = new Intent(Settings.Panel.ACTION_WIFI);
        } else {
            intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        }
        startActivity(intent);
    }

    private void checkJetsonConnection() {
        wifiStatusText.setText("Jetson 연결을 확인하는 중입니다.");
        apiClient.getHealth(new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> {
                    wifiStatusText.setText("Jetson Wi-Fi API 연결됨");
                    Class<?> nextActivity = UserSession.isLoggedIn(WifiSetupActivity.this)
                            ? MainActivity.class
                            : LoginActivity.class;
                    startActivity(new Intent(WifiSetupActivity.this, nextActivity));
                    finish();
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> wifiStatusText.setText(
                        "Jetson에 연결되지 않았습니다. Wi-Fi를 확인해주세요."
                ));
            }
        });
    }
}
