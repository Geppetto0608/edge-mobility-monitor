package com.example.jetsonbtmonitor;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final long TELEMETRY_POLL_INTERVAL_MS = 1_000L;
    private static final String PRESET_BASE = "base";
    private static final String PRESET_FAST = "fast";
    private static final String PRESET_SLOW = "slow";
    private static final String PRESET_PREFS_NAME = "wheelchair_user_presets";
    private static final String[] PRESET_IDS = {PRESET_BASE, PRESET_FAST, PRESET_SLOW};
    private static final String[] PRESET_MODE_VALUES = {"manual", "assist", "autonomous"};
    private static final String[] PRESET_MODE_LABELS = {"수동", "어시스트", "자율주행"};
    private static final String[] PRESET_STEP_LABELS = {"1 단계", "2 단계", "3 단계", "4 단계", "5 단계"};

    private final DecimalFormat speedFormat = new DecimalFormat("0.0");
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WheelchairApiClient apiClient;
    private ScrollView dashboardScrollView;
    private Button refreshButton;
    private Button connectButton;
    private TextView connectionStateText;
    private TextView bluetoothBadgeText;
    private TextView speedMpsText;
    private TextView speedText;
    private TextView batteryText;
    private TextView batteryStatusText;
    private TextView modeText;
    private TextView stepLevelText;
    private TextView systemStateText;
    private TextView rawJsonText;
    private MaterialButtonToggleGroup modeToggleGroup;
    private MaterialCardView presetBaseCard;
    private MaterialCardView presetFastCard;
    private MaterialCardView presetSlowCard;
    private TextView presetBaseNameText;
    private TextView presetFastNameText;
    private TextView presetSlowNameText;
    private TextView presetBaseSummaryText;
    private TextView presetFastSummaryText;
    private TextView presetSlowSummaryText;
    private MaterialButton presetBaseSettingsButton;
    private MaterialButton presetFastSettingsButton;
    private MaterialButton presetSlowSettingsButton;
    private MaterialButton openCameraButton;
    private MaterialButton settingsButton;
    private MaterialButton stepUpButton;
    private MaterialButton stepDownButton;
    private MaterialButton stopButton;

    private int stepLevel = 3;
    private JSONObject presetsById = new JSONObject();
    private String defaultPresetId = "";
    private String activePresetId = "";
    private String lastDisplayedMode = "";
    private boolean defaultPresetApplied;
    private boolean updatingModeSelection;
    private boolean destroyed;

    private final Runnable telemetryPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollTelemetry();
            if (!destroyed) {
                mainHandler.postDelayed(this, TELEMETRY_POLL_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!UserSession.isLoggedIn(this)) {
            startActivity(new Intent(this, WifiSetupActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        apiClient = new WheelchairApiClient(AppConfig.JETSON_API_BASE_URL);
        bindViews();
        setupActions();
        setupBackNavigation();
        setConnectedUi(false);
        setupDefaultPresets();
        loadLocalPresets();
        renderPresetCards();
        resetScrollPosition();
        checkApiConnection();
        loadPresets();
        startTelemetryPolling();
        requestNotificationPermissionIfNeeded();
        WheelchairBackgroundService.start(this);
        apiClient.logUsage(UserSession.userId(this), "app_open", emptyCallback());
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(telemetryPollRunnable);
        if (apiClient != null) {
            apiClient.shutdown();
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetScrollPosition();
    }

    private void bindViews() {
        dashboardScrollView = findViewById(R.id.dashboardScrollView);
        refreshButton = findViewById(R.id.refreshButton);
        connectButton = findViewById(R.id.connectButton);
        connectionStateText = findViewById(R.id.connectionStateText);
        bluetoothBadgeText = findViewById(R.id.bluetoothBadgeText);
        speedMpsText = findViewById(R.id.speedMpsText);
        speedText = findViewById(R.id.speedText);
        batteryText = findViewById(R.id.batteryText);
        batteryStatusText = findViewById(R.id.batteryStatusText);
        modeText = findViewById(R.id.modeText);
        stepLevelText = findViewById(R.id.stepLevelText);
        systemStateText = findViewById(R.id.systemStateText);
        rawJsonText = findViewById(R.id.rawJsonText);
        modeToggleGroup = findViewById(R.id.modeToggleGroup);
        presetBaseCard = findViewById(R.id.presetBaseCard);
        presetFastCard = findViewById(R.id.presetFastCard);
        presetSlowCard = findViewById(R.id.presetSlowCard);
        presetBaseNameText = findViewById(R.id.presetBaseNameText);
        presetFastNameText = findViewById(R.id.presetFastNameText);
        presetSlowNameText = findViewById(R.id.presetSlowNameText);
        presetBaseSummaryText = findViewById(R.id.presetBaseSummaryText);
        presetFastSummaryText = findViewById(R.id.presetFastSummaryText);
        presetSlowSummaryText = findViewById(R.id.presetSlowSummaryText);
        presetBaseSettingsButton = findViewById(R.id.presetBaseSettingsButton);
        presetFastSettingsButton = findViewById(R.id.presetFastSettingsButton);
        presetSlowSettingsButton = findViewById(R.id.presetSlowSettingsButton);
        openCameraButton = findViewById(R.id.openCameraButton);
        settingsButton = findViewById(R.id.settingsButton);
        stepUpButton = findViewById(R.id.stepUpButton);
        stepDownButton = findViewById(R.id.stepDownButton);
        stopButton = findViewById(R.id.stopButton);
    }

    private void setupActions() {
        refreshButton.setOnClickListener(view -> pollTelemetry());
        connectButton.setOnClickListener(view -> checkApiConnection());
        settingsButton.setOnClickListener(view -> startActivity(new Intent(this, SettingsActivity.class)));
        openCameraButton.setOnClickListener(view -> openCameraStream());
        presetBaseCard.setOnClickListener(view -> applyPreset(PRESET_BASE, false));
        presetFastCard.setOnClickListener(view -> applyPreset(PRESET_FAST, false));
        presetSlowCard.setOnClickListener(view -> applyPreset(PRESET_SLOW, false));
        presetBaseSettingsButton.setOnClickListener(view -> showPresetDialog(PRESET_BASE));
        presetFastSettingsButton.setOnClickListener(view -> showPresetDialog(PRESET_FAST));
        presetSlowSettingsButton.setOnClickListener(view -> showPresetDialog(PRESET_SLOW));
        stepUpButton.setOnClickListener(view -> setStepLevel(stepLevel + 1));
        stepDownButton.setOnClickListener(view -> setStepLevel(stepLevel - 1));
        stopButton.setOnClickListener(view -> sendStopCommand());
        modeToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (updatingModeSelection || !isChecked) {
                return;
            }
            if (checkedId == R.id.manualModeButton) {
                sendModeCommand("manual");
            } else if (checkedId == R.id.assistModeButton) {
                sendModeCommand("assist");
            } else if (checkedId == R.id.autonomousModeButton) {
                sendModeCommand("autonomous");
            }
        });
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                moveTaskToBack(true);
            }
        });
    }

    private void startTelemetryPolling() {
        mainHandler.post(telemetryPollRunnable);
    }

    private void setupDefaultPresets() {
        presetsById = new JSONObject();
        putPreset(createPreset(PRESET_BASE, getString(R.string.base_preset_name), "assist", 3));
        putPreset(createPreset(PRESET_FAST, getString(R.string.fast_preset_name), "autonomous", 5));
        putPreset(createPreset(PRESET_SLOW, getString(R.string.slow_preset_name), "manual", 1));
    }

    private void loadLocalPresets() {
        String userId = UserSession.userId(this);
        if (userId.isEmpty()) {
            return;
        }

        SharedPreferences preferences = presetPreferences();
        defaultPresetId = preferences.getString(userId + "_default", "");
        String rawPresets = preferences.getString(userId + "_presets", "");
        if (rawPresets == null || rawPresets.isEmpty()) {
            return;
        }

        try {
            JSONArray presets = new JSONArray(rawPresets);
            for (int i = 0; i < presets.length(); i++) {
                JSONObject preset = presets.optJSONObject(i);
                if (preset != null && isKnownPresetId(preset.optString("id", ""))) {
                    putPreset(createPreset(
                            preset.optString("id", ""),
                            preset.optString("name", defaultPresetName(preset.optString("id", ""))),
                            preset.optString("mode", "assist"),
                            preset.optInt("step_level", defaultPresetStep(preset.optString("id", "")))
                    ));
                }
            }
        } catch (JSONException ignored) {
            // Corrupt local preset cache falls back to built-in defaults.
        }
    }

    private void saveLocalPresets() {
        String userId = UserSession.userId(this);
        if (userId.isEmpty()) {
            return;
        }

        presetPreferences()
                .edit()
                .putString(userId + "_presets", presetsArray().toString())
                .putString(userId + "_default", defaultPresetId)
                .apply();
    }

    private SharedPreferences presetPreferences() {
        return getSharedPreferences(PRESET_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void loadPresets() {
        String userId = UserSession.userId(this);
        if (userId.isEmpty()) {
            return;
        }

        apiClient.getUserPresets(userId, new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> {
                    readPresetsResponse(response.optJSONObject("presets_data"));
                    saveLocalPresets();
                    renderPresetCards();
                    applyDefaultPresetIfNeeded();
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> {
                    loadLocalPresets();
                    renderPresetCards();
                    applyDefaultPresetIfNeeded();
                });
            }
        });
    }

    private void readPresetsResponse(JSONObject presetsData) {
        if (presetsData == null) {
            return;
        }

        defaultPresetId = presetsData.optString("default_preset_id", "");
        JSONArray presets = presetsData.optJSONArray("presets");
        if (presets == null) {
            return;
        }

        for (int i = 0; i < presets.length(); i++) {
            JSONObject preset = presets.optJSONObject(i);
            if (preset != null && isKnownPresetId(preset.optString("id", ""))) {
                putPreset(createPreset(
                        preset.optString("id", ""),
                        preset.optString("name", defaultPresetName(preset.optString("id", ""))),
                        preset.optString("mode", "assist"),
                        preset.optInt("step_level", defaultPresetStep(preset.optString("id", "")))
                ));
            }
        }
    }

    private void applyDefaultPresetIfNeeded() {
        if (defaultPresetApplied || defaultPresetId.isEmpty()) {
            return;
        }
        defaultPresetApplied = true;
        applyPreset(defaultPresetId, true);
    }

    private void renderPresetCards() {
        renderPresetCard(PRESET_BASE, presetBaseCard, presetBaseNameText, presetBaseSummaryText);
        renderPresetCard(PRESET_FAST, presetFastCard, presetFastNameText, presetFastSummaryText);
        renderPresetCard(PRESET_SLOW, presetSlowCard, presetSlowNameText, presetSlowSummaryText);
    }

    private void renderPresetCard(String presetId, MaterialCardView card, TextView nameText, TextView summaryText) {
        JSONObject preset = presetForId(presetId);
        boolean isActivePreset = presetId.equals(activePresetId);
        boolean isDefaultPreset = presetId.equals(defaultPresetId);
        nameText.setText(preset.optString("name", defaultPresetName(presetId)));
        summaryText.setText(presetSummary(preset, isDefaultPreset, isActivePreset));
        card.setStrokeWidth(isActivePreset ? 4 : isDefaultPreset ? 3 : 1);
        card.setStrokeColor(ContextCompat.getColor(
                this,
                isActivePreset || isDefaultPreset ? R.color.brand_blue : R.color.control_stroke
        ));
        card.setCardBackgroundColor(ContextCompat.getColor(
                this,
                isActivePreset ? R.color.preset_active_background
                        : isDefaultPreset ? R.color.preset_default_background
                        : R.color.card_background
        ));
    }

    private String presetSummary(JSONObject preset, boolean isDefaultPreset, boolean isActivePreset) {
        StringBuilder builder = new StringBuilder();
        builder.append(modeDisplayName(preset.optString("mode", "")));
        builder.append('\n');
        builder.append(preset.optInt("step_level", 3)).append(" 단계");
        if (isActivePreset) {
            builder.append(" / 선택됨");
        }
        if (isDefaultPreset) {
            builder.append(" / ").append(getString(R.string.preset_default_badge));
        }
        return builder.toString();
    }

    private void showPresetDialog(String presetId) {
        JSONObject preset = presetForId(presetId);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(4);
        container.setPadding(padding, padding, padding, 0);

        EditText nameEditText = new EditText(this);
        nameEditText.setHint(R.string.preset_name_hint);
        nameEditText.setSingleLine(true);
        nameEditText.setInputType(InputType.TYPE_CLASS_TEXT);
        nameEditText.setText(preset.optString("name", defaultPresetName(presetId)));
        container.addView(nameEditText, matchWrapParams());

        TextView modeLabel = dialogLabel("주행 모드");
        container.addView(modeLabel, matchWrapParamsWithTop(14));

        Spinner modeSpinner = new Spinner(this);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                PRESET_MODE_LABELS
        );
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);
        modeSpinner.setSelection(modeIndex(preset.optString("mode", "assist")));
        container.addView(modeSpinner, matchWrapParams());

        TextView stepLabel = dialogLabel("Step Level");
        container.addView(stepLabel, matchWrapParamsWithTop(14));

        Spinner stepSpinner = new Spinner(this);
        ArrayAdapter<String> stepAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                PRESET_STEP_LABELS
        );
        stepAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        stepSpinner.setAdapter(stepAdapter);
        stepSpinner.setSelection(stepIndex(preset.optInt("step_level", 3)));
        container.addView(stepSpinner, matchWrapParams());

        CheckBox defaultCheckBox = new CheckBox(this);
        defaultCheckBox.setText(R.string.preset_default_checkbox);
        defaultCheckBox.setChecked(presetId.equals(defaultPresetId));
        container.addView(defaultCheckBox, matchWrapParamsWithTop(12));

        new AlertDialog.Builder(this)
                .setTitle(R.string.preset_dialog_title)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.preset_save, (dialog, which) -> {
                    String name = nameEditText.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = defaultPresetName(presetId);
                    }
                    String mode = PRESET_MODE_VALUES[modeSpinner.getSelectedItemPosition()];
                    int selectedStepLevel = stepSpinner.getSelectedItemPosition() + 1;
                    savePresetFromDialog(presetId, name, mode, selectedStepLevel, defaultCheckBox.isChecked());
                })
                .show();
    }

    private void savePresetFromDialog(
            String presetId,
            String name,
            String mode,
            int selectedStepLevel,
            boolean useAsDefault
    ) {
        JSONObject preset = createPreset(presetId, name, mode, selectedStepLevel);
        putPreset(preset);
        if (useAsDefault) {
            defaultPresetId = presetId;
        } else if (presetId.equals(defaultPresetId)) {
            defaultPresetId = "";
        }
        renderPresetCards();
        saveLocalPresets();
        savePresetsToJetson();
        applyPreset(presetId, false);
    }

    private void savePresetsToJetson() {
        String userId = UserSession.userId(this);
        if (userId.isEmpty()) {
            return;
        }

        apiClient.saveUserPresets(userId, presetsArray(), defaultPresetId, new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> showStatus("프리셋 저장 완료"));
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> showStatus("프리셋 저장 실패: " + message));
            }
        });
    }

    private void applyPreset(String presetId, boolean fromDefault) {
        if (!isKnownPresetId(presetId)) {
            return;
        }
        activePresetId = presetId;
        renderPresetCards();
        JSONObject preset = presetForId(presetId);
        String mode = preset.optString("mode", "assist");
        int presetStepLevel = preset.optInt("step_level", 3);
        applyPresetLocally(mode, presetStepLevel);

        String prefix = fromDefault ? "기본 프리셋 적용: " : "프리셋 적용: ";
        String presetName = preset.optString("name", defaultPresetName(presetId));
        sendModeAndStepCommands(mode, presetStepLevel, prefix + presetName);
    }

    private void applyPresetLocally(String mode, int presetStepLevel) {
        stepLevel = clampStepLevel(presetStepLevel);
        stepLevelText.setText(getString(R.string.step_level_value, stepLevel));
        updateDisplayedMode(mode);
        syncModeSelection(mode);
    }

    private JSONArray presetsArray() {
        JSONArray result = new JSONArray();
        for (String presetId : PRESET_IDS) {
            result.put(presetForId(presetId));
        }
        return result;
    }

    private JSONObject presetForId(String presetId) {
        JSONObject preset = presetsById.optJSONObject(presetId);
        if (preset != null) {
            return preset;
        }
        return createPreset(presetId, defaultPresetName(presetId), defaultPresetMode(presetId), defaultPresetStep(presetId));
    }

    private JSONObject createPreset(String presetId, String name, String mode, int selectedStepLevel) {
        JSONObject preset = new JSONObject();
        try {
            preset.put("id", presetId);
            preset.put("name", name);
            preset.put("mode", normalizePresetMode(mode));
            preset.put("step_level", clampStepLevel(selectedStepLevel));
        } catch (JSONException ignored) {
            // JSONObject with stable preset values cannot fail here.
        }
        return preset;
    }

    private void putPreset(JSONObject preset) {
        try {
            presetsById.put(preset.optString("id", ""), preset);
        } catch (JSONException ignored) {
            // JSONObject with a nested object cannot fail here.
        }
    }

    private boolean isKnownPresetId(String presetId) {
        for (String knownPresetId : PRESET_IDS) {
            if (knownPresetId.equals(presetId)) {
                return true;
            }
        }
        return false;
    }

    private String defaultPresetName(String presetId) {
        switch (presetId) {
            case PRESET_FAST:
                return getString(R.string.fast_preset_name);
            case PRESET_SLOW:
                return getString(R.string.slow_preset_name);
            case PRESET_BASE:
            default:
                return getString(R.string.base_preset_name);
        }
    }

    private String defaultPresetMode(String presetId) {
        switch (presetId) {
            case PRESET_FAST:
                return "autonomous";
            case PRESET_SLOW:
                return "manual";
            case PRESET_BASE:
            default:
                return "assist";
        }
    }

    private int defaultPresetStep(String presetId) {
        switch (presetId) {
            case PRESET_FAST:
                return 5;
            case PRESET_SLOW:
                return 1;
            case PRESET_BASE:
            default:
                return 3;
        }
    }

    private String normalizePresetMode(String mode) {
        String normalizedMode = mode == null ? "" : mode.toLowerCase(Locale.US);
        for (String presetMode : PRESET_MODE_VALUES) {
            if (presetMode.equals(normalizedMode)) {
                return presetMode;
            }
        }
        return "assist";
    }

    private int modeIndex(String mode) {
        String normalizedMode = normalizePresetMode(mode);
        for (int i = 0; i < PRESET_MODE_VALUES.length; i++) {
            if (PRESET_MODE_VALUES[i].equals(normalizedMode)) {
                return i;
            }
        }
        return 1;
    }

    private int stepIndex(int selectedStepLevel) {
        return Math.max(0, Math.min(4, selectedStepLevel - 1));
    }

    private int clampStepLevel(int selectedStepLevel) {
        return Math.max(1, Math.min(5, selectedStepLevel));
    }

    private String modeDisplayName(String mode) {
        switch (normalizePresetMode(mode)) {
            case "manual":
                return "수동";
            case "autonomous":
                return "자율주행";
            case "assist":
            default:
                return "어시스트";
        }
    }

    private TextView dialogLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        label.setTextSize(14);
        return label;
    }

    private LinearLayout.LayoutParams matchWrapParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapParamsWithTop(int topMarginDp) {
        LinearLayout.LayoutParams params = matchWrapParams();
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
    }

    private void checkApiConnection() {
        setConnectingUi();
        apiClient.getHealth(new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> {
                    setConnectedUi(true);
                    rawJsonText.setText("Jetson API 연결됨\n버전: "
                            + response.optString("version", "--")
                            + "\nROS 토픽 수신을 확인하는 중입니다.");
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> {
                    setConnectedUi(false);
                    showStatus(message);
                });
            }
        });
    }

    private void pollTelemetry() {
        apiClient.getTelemetry(new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> {
                    setConnectedUi(true);
                    renderTelemetry(response.optJSONObject("telemetry"));
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> setConnectedUi(false));
            }
        });
    }

    private void renderTelemetry(JSONObject telemetry) {
        if (telemetry == null) {
            rawJsonText.setText("Jetson API 연결됨\n텔레메트리 응답이 비어 있습니다.");
            systemStateText.setText(R.string.system_state_waiting);
            return;
        }
        Double speedKph = readDouble(telemetry, "speed_kph");
        Integer batteryPercent = readInteger(telemetry, "battery_percent");
        String mode = readString(telemetry, "mode");
        boolean hasTelemetry = speedKph != null || batteryPercent != null || !mode.trim().isEmpty();

        speedMpsText.setText(speedKph == null ? "--" : speedFormat.format(speedKph));
        speedText.setText(speedKph == null ? getString(R.string.empty_speed_mps) : speedFormat.format(speedKph / 3.6) + " m/s");
        batteryText.setText(batteryPercent == null ? "--" : String.valueOf(batteryPercent));
        batteryStatusText.setText(batteryStatusText(batteryPercent));
        updateDisplayedMode(mode);
        systemStateText.setText(hasTelemetry ? R.string.system_state_running : R.string.system_state_waiting);
        rawJsonText.setText(telemetryStatusText(speedKph, batteryPercent, displayModeForTelemetry(mode), telemetry));
        syncModeSelection(displayModeForTelemetry(mode));
    }

    private void openCameraStream() {
        Intent intent = new Intent(this, CameraActivity.class);
        intent.putExtra(CameraActivity.EXTRA_STREAM_URL, AppConfig.CAMERA_STREAM_URL);
        startActivity(intent);
    }

    private void sendModeCommand(String mode) {
        activePresetId = "";
        renderPresetCards();
        updateDisplayedMode(mode);
        syncModeSelection(mode);
        JSONObject payload = new JSONObject();
        try {
            payload.put("command", "set_mode");
            payload.put("mode", mode);
            payload.put("source", "android_wifi");
            payload.put("user_id", UserSession.userId(this));
        } catch (JSONException exception) {
            showStatus("명령 생성에 실패했습니다.");
            return;
        }
        sendCommand(payload, "모드 전환 요청: " + displayMode(mode));
    }

    private void setStepLevel(int nextStepLevel) {
        if (nextStepLevel < 1 || nextStepLevel > 5) {
            return;
        }
        activePresetId = "";
        renderPresetCards();
        stepLevel = nextStepLevel;
        stepLevelText.setText(getString(R.string.step_level_value, stepLevel));

        JSONObject payload = new JSONObject();
        try {
            payload.put("command", "set_step");
            payload.put("step_level", stepLevel);
            payload.put("source", "android_wifi");
            payload.put("user_id", UserSession.userId(this));
        } catch (JSONException exception) {
            showStatus("단계 명령 생성에 실패했습니다.");
            return;
        }
        sendCommand(payload, "단계 전환 요청: " + stepLevel);
    }

    private void sendModeAndStepCommands(String mode, int presetStepLevel, String successMessage) {
        JSONObject modePayload = new JSONObject();
        JSONObject stepPayload = new JSONObject();
        int requestedStepLevel = clampStepLevel(presetStepLevel);
        try {
            modePayload.put("command", "set_mode");
            modePayload.put("mode", mode);
            modePayload.put("source", "android_preset");
            modePayload.put("user_id", UserSession.userId(this));

            stepPayload.put("command", "set_step");
            stepPayload.put("step_level", requestedStepLevel);
            stepPayload.put("source", "android_preset");
            stepPayload.put("user_id", UserSession.userId(this));
        } catch (JSONException exception) {
            showStatus("프리셋 명령 생성에 실패했습니다.");
            return;
        }

        rawJsonText.setText("명령 전송 중: " + successMessage);
        apiClient.sendCommand(modePayload, new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                apiClient.sendCommand(stepPayload, new WheelchairApiClient.JsonCallback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        runOnUiThread(() -> {
                            rawJsonText.setText(successMessage);
                            showStatus(successMessage);
                        });
                    }

                    @Override
                    public void onError(String message, Throwable throwable) {
                        runOnUiThread(() -> showStatus("프리셋 단계 적용 실패: " + message));
                    }
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> showStatus("프리셋 모드 적용 실패: " + message));
            }
        });
    }

    private void sendStopCommand() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("command", "stop");
            payload.put("source", "android_wifi");
            payload.put("user_id", UserSession.userId(this));
        } catch (JSONException exception) {
            showStatus("정지 명령 생성에 실패했습니다.");
            return;
        }
        systemStateText.setText(R.string.system_state_stop_requested);
        sendCommand(payload, "정지 명령을 보냈습니다.");
    }

    private void sendCommand(JSONObject payload, String successMessage) {
        rawJsonText.setText("명령 전송 중: " + commandSummary(payload));
        apiClient.sendCommand(payload, new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> {
                    rawJsonText.setText(successMessage);
                    showStatus(successMessage);
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> showStatus(message));
            }
        });
    }

    private void syncModeSelection(String mode) {
        int checkedButtonId = modeButtonId(mode);
        if (checkedButtonId == 0 || checkedButtonId == modeToggleGroup.getCheckedButtonId()) {
            return;
        }
        updatingModeSelection = true;
        modeToggleGroup.check(checkedButtonId);
        updatingModeSelection = false;
    }

    private void setConnectedUi(boolean connected) {
        connectButton.setEnabled(true);
        connectButton.setText(R.string.connection_check);
        if (connected) {
            setConnectionState(
                    getString(R.string.connection_connected),
                    R.drawable.status_dot_connected,
                    R.color.status_connected_text
            );
        } else {
            setConnectionState(
                    getString(R.string.connection_disconnected),
                    R.drawable.status_dot_disconnected,
                    R.color.status_disconnected_text
            );
        }
    }

    private void setConnectingUi() {
        setConnectionState(
                getString(R.string.connection_connecting),
                R.drawable.status_dot_connecting,
                R.color.status_connecting_text
        );
    }

    private void setConnectionState(String text, int badgeBackgroundResId, int textColorResId) {
        connectionStateText.setText(text);
        connectionStateText.setTextColor(ContextCompat.getColor(this, textColorResId));
        bluetoothBadgeText.setBackgroundResource(badgeBackgroundResId);
    }

    private void updateDisplayedMode(String mode) {
        String normalizedMode = mode == null ? "" : mode.trim();
        if (!normalizedMode.isEmpty()) {
            lastDisplayedMode = normalizedMode;
        }

        String visibleMode = lastDisplayedMode == null ? "" : lastDisplayedMode.trim();
        modeText.setText(visibleMode.isEmpty() ? "--" : displayMode(visibleMode).toUpperCase(Locale.US));
    }

    private String displayModeForTelemetry(String mode) {
        String normalizedMode = mode == null ? "" : mode.trim();
        if (!normalizedMode.isEmpty()) {
            return normalizedMode;
        }
        return lastDisplayedMode == null ? "" : lastDisplayedMode;
    }

    private String displayMode(String mode) {
        String normalizedMode = mode == null ? "" : mode.toLowerCase(Locale.US);
        switch (normalizedMode) {
            case "manual":
            case "manual_mode":
                return "수동 모드";
            case "assist":
            case "assist_mode":
                return "어시스트 모드";
            case "autonomous":
            case "autonomous_mode":
            case "auto":
                return "자율주행 모드";
            default:
                return mode;
        }
    }

    private String batteryStatusText(Integer batteryPercent) {
        if (batteryPercent == null) {
            return getString(R.string.empty_status);
        }
        return getString(batteryPercent >= 30 ? R.string.battery_status_normal : R.string.battery_status_low);
    }

    private int modeButtonId(String mode) {
        String normalizedMode = mode == null ? "" : mode.toLowerCase(Locale.US);
        switch (normalizedMode) {
            case "manual":
            case "manual_mode":
                return R.id.manualModeButton;
            case "assist":
            case "assist_mode":
                return R.id.assistModeButton;
            case "autonomous":
            case "autonomous_mode":
            case "auto":
                return R.id.autonomousModeButton;
            default:
                return 0;
        }
    }

    private Double readDouble(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        return object.optDouble(key);
    }

    private Integer readInteger(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        return object.optInt(key);
    }

    private String readString(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) {
            return "";
        }
        return object.optString(key, "");
    }

    private String telemetryStatusText(Double speedKph, Integer batteryPercent, String mode, JSONObject telemetry) {
        boolean hasTelemetry = speedKph != null || batteryPercent != null || !mode.trim().isEmpty();
        if (!hasTelemetry) {
            return "Jetson API 연결됨\n"
                    + "ROS 토픽 수신 대기 중입니다.\n"
                    + "속도: /cmd_vel 또는 /wheelchair/speed_kph 대기\n"
                    + "배터리: /wheelchair/battery_percent 또는 /battery/soc 대기\n"
                    + "모드: /wheelchair/mode 대기";
        }

        String updatedAt = readString(telemetry, "updated_at");
        StringBuilder builder = new StringBuilder();
        builder.append("Jetson API 연결됨\n");
        builder.append("속도: ").append(speedKph == null ? "--" : speedFormat.format(speedKph) + " km/h").append('\n');
        builder.append("배터리: ").append(batteryPercent == null ? "--" : batteryPercent + "%").append('\n');
        builder.append("모드: ").append(mode.trim().isEmpty() ? "--" : displayMode(mode)).append('\n');
        builder.append("마지막 수신: ").append(updatedAt.isEmpty() ? "--" : updatedAt);
        return builder.toString();
    }

    private String commandSummary(JSONObject payload) {
        String command = payload.optString("command", "");
        if ("set_mode".equals(command)) {
            return "모드 전환 " + displayMode(payload.optString("mode", ""));
        }
        if ("set_step".equals(command)) {
            return "단계 전환 " + payload.optInt("step_level") + "단계";
        }
        if ("stop".equals(command)) {
            return "정지";
        }
        return command;
    }

    private void resetScrollPosition() {
        dashboardScrollView.post(() -> dashboardScrollView.scrollTo(0, 0));
    }

    private void showStatus(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        rawJsonText.setText(message);
    }

    private WheelchairApiClient.JsonCallback emptyCallback() {
        return new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                // Usage logs are best-effort.
            }

            @Override
            public void onError(String message, Throwable throwable) {
                // Usage logs are best-effort.
            }
        };
    }
}
