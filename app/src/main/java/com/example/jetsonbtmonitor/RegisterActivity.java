package com.example.jetsonbtmonitor;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.json.JSONException;
import org.json.JSONObject;

public class RegisterActivity extends AppCompatActivity {
    private WheelchairApiClient apiClient;
    private EditText userIdEditText;
    private EditText passwordEditText;
    private EditText passwordConfirmEditText;
    private EditText nameEditText;
    private EditText phoneEditText;
    private EditText heightEditText;
    private EditText weightEditText;
    private EditText notesEditText;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        apiClient = new WheelchairApiClient(AppConfig.JETSON_API_BASE_URL);

        userIdEditText = findViewById(R.id.registerUserIdEditText);
        passwordEditText = findViewById(R.id.registerPasswordEditText);
        passwordConfirmEditText = findViewById(R.id.registerPasswordConfirmEditText);
        nameEditText = findViewById(R.id.registerNameEditText);
        phoneEditText = findViewById(R.id.registerPhoneEditText);
        heightEditText = findViewById(R.id.registerHeightEditText);
        weightEditText = findViewById(R.id.registerWeightEditText);
        notesEditText = findViewById(R.id.registerNotesEditText);
        statusText = findViewById(R.id.registerStatusText);
        MaterialButton submitButton = findViewById(R.id.registerSubmitButton);
        submitButton.setOnClickListener(view -> register());
    }

    @Override
    protected void onDestroy() {
        apiClient.shutdown();
        super.onDestroy();
    }

    private void register() {
        clearInputErrors();
        String userId = userIdEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString();
        String passwordConfirm = passwordConfirmEditText.getText().toString();
        String name = nameEditText.getText().toString().trim();
        String phone = phoneEditText.getText().toString().trim();
        if (userId.isEmpty() || password.isEmpty() || name.isEmpty() || phone.isEmpty()) {
            if (userId.isEmpty()) {
                userIdEditText.setError("아이디를 입력해주세요.");
            }
            if (password.isEmpty()) {
                passwordEditText.setError("비밀번호를 입력해주세요.");
            }
            if (name.isEmpty()) {
                nameEditText.setError("이름을 입력해주세요.");
            }
            if (phone.isEmpty()) {
                phoneEditText.setError("전화번호를 입력해주세요.");
            }
            statusText.setText("아이디, 비밀번호, 이름, 전화번호는 필수입니다.");
            return;
        }
        if (!password.equals(passwordConfirm)) {
            passwordConfirmEditText.setError("비밀번호가 일치하지 않습니다.");
            passwordConfirmEditText.requestFocus();
            statusText.setText("비밀번호 확인이 일치하지 않습니다.");
            return;
        }
        if (password.length() < 4) {
            passwordEditText.setError("4자 이상 입력해주세요.");
            passwordEditText.requestFocus();
            statusText.setText("비밀번호는 4자 이상으로 입력해주세요.");
            return;
        }

        JSONObject profile = new JSONObject();
        try {
            profile.put("user_id", userId);
            profile.put("password", password);
            profile.put("name", name);
            profile.put("phone", phone);
            putIfPresent(profile, "height_cm", heightEditText.getText().toString().trim());
            putIfPresent(profile, "weight_kg", weightEditText.getText().toString().trim());
            profile.put("notes", notesEditText.getText().toString().trim());
        } catch (JSONException | NumberFormatException exception) {
            statusText.setText("키와 몸무게는 숫자로 입력해주세요.");
            return;
        }

        statusText.setText("Jetson에 회원 정보를 저장하는 중입니다.");
        apiClient.register(profile, new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> {
                    UserSession.saveProfile(RegisterActivity.this, response.optJSONObject("user"));
                    statusText.setText("회원가입 완료");
                    startActivity(new Intent(RegisterActivity.this, MainActivity.class));
                    finish();
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> showRegisterError(message));
            }
        });
    }

    private void showRegisterError(String message) {
        clearInputErrors();
        if (message != null && message.contains("user_id already exists")) {
            userIdEditText.setError("이미 사용 중인 아이디입니다.");
            userIdEditText.requestFocus();
            statusText.setText("이미 사용 중인 아이디입니다. 다른 아이디를 입력해주세요.");
            return;
        }
        statusText.setText("회원가입에 실패했습니다. Jetson 연결과 입력 정보를 확인해주세요.");
    }

    private void putIfPresent(JSONObject profile, String key, String value) throws JSONException {
        if (!value.isEmpty()) {
            profile.put(key, Double.parseDouble(value));
        }
    }

    private void clearInputErrors() {
        userIdEditText.setError(null);
        passwordEditText.setError(null);
        passwordConfirmEditText.setError(null);
        nameEditText.setError(null);
        phoneEditText.setError(null);
        heightEditText.setError(null);
        weightEditText.setError(null);
    }
}
