package com.example.jetsonbtmonitor;

import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraActivity extends AppCompatActivity {
    static final String EXTRA_STREAM_URL = "com.example.jetsonbtmonitor.STREAM_URL";
    private static final long HEALTH_POLL_INTERVAL_MS = 1_000L;
    private static final int HEALTH_TIMEOUT_MS = 1_500;

    private WebView cameraWebView;
    private ProgressBar cameraProgressBar;
    private TextView cameraFrameCountText;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService healthExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean healthRequestInFlight;
    private volatile boolean destroyed;
    private String streamUrl;
    private String healthUrl;
    private int lastFrameCount = -1;
    private long lastHealthTimestampMs;

    private final Runnable healthPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollCameraHealth();
            if (!destroyed) {
                mainHandler.postDelayed(this, HEALTH_POLL_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        streamUrl = getIntent().getStringExtra(EXTRA_STREAM_URL);
        if (streamUrl == null || streamUrl.trim().isEmpty()) {
            streamUrl = AppConfig.CAMERA_STREAM_URL;
        }
        healthUrl = healthUrlForStream(streamUrl);

        bindViews();
        setupWebView();
        loadCameraStream();
        startHealthPolling();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(healthPollRunnable);
        healthExecutor.shutdownNow();
        if (cameraWebView != null) {
            cameraWebView.stopLoading();
            cameraWebView.destroy();
        }
        super.onDestroy();
    }

    private void bindViews() {
        MaterialButton backButton = findViewById(R.id.backButton);
        MaterialButton reloadCameraButton = findViewById(R.id.reloadCameraButton);
        cameraProgressBar = findViewById(R.id.cameraProgressBar);
        cameraWebView = findViewById(R.id.cameraWebView);
        cameraFrameCountText = findViewById(R.id.cameraFrameCountText);

        backButton.setOnClickListener(view -> finish());
        reloadCameraButton.setOnClickListener(view -> {
            loadCameraStream();
            pollCameraHealth();
        });
    }

    private void setupWebView() {
        cameraWebView.setBackgroundColor(Color.BLACK);
        WebSettings settings = cameraWebView.getSettings();
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        cameraWebView.setWebChromeClient(new WebChromeClient());
        cameraWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                cameraProgressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    android.webkit.WebResourceError error
            ) {
                if (request == null || request.isForMainFrame()) {
                    cameraProgressBar.setVisibility(View.GONE);
                    Toast.makeText(
                            CameraActivity.this,
                            "카메라 스트림을 열 수 없습니다.",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            }
        });
    }

    private void loadCameraStream() {
        cameraProgressBar.setVisibility(View.VISIBLE);
        String escapedUrl = TextUtils.htmlEncode(streamUrl);
        String html = "<!doctype html>"
                + "<html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>"
                + "html,body{margin:0;width:100%;height:100%;background:#000;}"
                + "body{display:flex;align-items:center;justify-content:center;overflow:hidden;}"
                + "img{width:100%;height:auto;max-height:100%;object-fit:contain;}"
                + "</style>"
                + "</head><body>"
                + "<img src=\"" + escapedUrl + "\" alt=\"camera stream\">"
                + "</body></html>";
        cameraWebView.loadDataWithBaseURL(streamUrl, html, "text/html", "UTF-8", null);
    }

    private void startHealthPolling() {
        mainHandler.post(healthPollRunnable);
    }

    private void pollCameraHealth() {
        if (healthRequestInFlight || destroyed) {
            return;
        }
        healthRequestInFlight = true;
        healthExecutor.execute(() -> {
            try {
                JSONObject health = fetchHealth();
                int frameCount = health.optInt("frame_count", 0);
                String phase = health.optString("phase", "unknown");
                double fps = calculateFps(frameCount);
                runOnUiThread(() -> cameraFrameCountText.setText(
                        getString(R.string.camera_frame_status, fps, phase)
                ));
            } catch (IOException | JSONException exception) {
                runOnUiThread(() -> cameraFrameCountText.setText(R.string.camera_frame_status_empty));
            } finally {
                healthRequestInFlight = false;
            }
        });
    }

    private double calculateFps(int frameCount) {
        long nowMs = System.currentTimeMillis();
        if (lastFrameCount < 0 || lastHealthTimestampMs == 0L || frameCount < lastFrameCount) {
            lastFrameCount = frameCount;
            lastHealthTimestampMs = nowMs;
            return 0.0;
        }

        long elapsedMs = nowMs - lastHealthTimestampMs;
        int frameDelta = frameCount - lastFrameCount;
        lastFrameCount = frameCount;
        lastHealthTimestampMs = nowMs;
        if (elapsedMs <= 0L) {
            return 0.0;
        }
        return frameDelta * 1000.0 / elapsedMs;
    }

    private JSONObject fetchHealth() throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(healthUrl).openConnection();
        connection.setConnectTimeout(HEALTH_TIMEOUT_MS);
        connection.setReadTimeout(HEALTH_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return new JSONObject(builder.toString());
        } finally {
            connection.disconnect();
        }
    }

    private String healthUrlForStream(String url) {
        Uri streamUri = Uri.parse(url);
        Uri healthUri = streamUri.buildUpon()
                .path("/health")
                .query(null)
                .fragment(null)
                .build();
        return healthUri.toString();
    }
}
