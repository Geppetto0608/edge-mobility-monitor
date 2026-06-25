package com.example.jetsonbtmonitor;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

public class LoginActivity extends AppCompatActivity {
    private WheelchairApiClient apiClient;
    private EditText userIdEditText;
    private EditText passwordEditText;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        apiClient = new WheelchairApiClient(AppConfig.JETSON_API_BASE_URL);

        userIdEditText = findViewById(R.id.loginUserIdEditText);
        passwordEditText = findViewById(R.id.loginPasswordEditText);
        statusText = findViewById(R.id.loginStatusText);
        MaterialButton loginButton = findViewById(R.id.loginButton);
        MaterialButton registerOpenButton = findViewById(R.id.registerOpenButton);

        loginButton.setOnClickListener(view -> login());
        registerOpenButton.setOnClickListener(view -> startActivity(new Intent(this, RegisterActivity.class)));
    }

    @Override
    protected void onDestroy() {
        apiClient.shutdown();
        super.onDestroy();
    }

    private void login() {
        clearInputErrors();
        String userId = userIdEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString();
        if (userId.isEmpty() || password.isEmpty()) {
            if (userId.isEmpty()) {
                userIdEditText.setError("아이디를 입력해주세요.");
                userIdEditText.requestFocus();
            }
            if (password.isEmpty()) {
                passwordEditText.setError("비밀번호를 입력해주세요.");
                if (!userId.isEmpty()) {
                    passwordEditText.requestFocus();
                }
            }
            statusText.setText("아이디와 비밀번호를 모두 입력해주세요.");
            return;
        }

        statusText.setText("로그인 중입니다.");
        apiClient.login(userId, password, new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> {
                    UserSession.saveProfile(LoginActivity.this, response.optJSONObject("user"));
                    statusText.setText("로그인 완료");
                    startActivity(new Intent(LoginActivity.this, MainActivity.class));
                    finish();
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> showLoginError(message));
            }
        });
    }

    private void showLoginError(String message) {
        clearInputErrors();
        if (message != null && message.contains("user not found")) {
            userIdEditText.setError("등록되지 않은 아이디입니다.");
            userIdEditText.requestFocus();
            statusText.setText("등록되지 않은 아이디입니다. 회원가입을 먼저 진행해주세요.");
            return;
        }
        if (message != null && message.contains("invalid password")) {
            passwordEditText.setError("비밀번호가 틀렸습니다.");
            passwordEditText.requestFocus();
            statusText.setText("비밀번호가 틀렸습니다. 다시 입력해주세요.");
            return;
        }
        statusText.setText("로그인에 실패했습니다. Jetson 연결과 계정 정보를 확인해주세요.");
    }

    private void clearInputErrors() {
        userIdEditText.setError(null);
        passwordEditText.setError(null);
    }
}
