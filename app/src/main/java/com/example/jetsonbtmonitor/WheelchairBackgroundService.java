package com.example.jetsonbtmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.text.DecimalFormat;

public class WheelchairBackgroundService extends Service {
    static final String ACTION_START = "com.example.jetsonbtmonitor.action.START_BACKGROUND";
    static final String ACTION_STOP = "com.example.jetsonbtmonitor.action.STOP_BACKGROUND";

    private static final String CHANNEL_ID = "wheelchair_background";
    private static final int NOTIFICATION_ID = 1001;
    private static final long POLL_INTERVAL_MS = 5_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DecimalFormat speedFormat = new DecimalFormat("0.0");

    private WheelchairApiClient apiClient;
    private boolean running;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollTelemetry();
            if (running) {
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    static void start(Context context) {
        Intent intent = new Intent(context, WheelchairBackgroundService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        Intent intent = new Intent(context, WheelchairBackgroundService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        apiClient = new WheelchairApiClient(AppConfig.JETSON_API_BASE_URL);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!UserSession.isLoggedIn(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        startForegroundCompat(buildNotification(
                getString(R.string.background_service_title),
                getString(R.string.background_service_waiting)
        ));
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(pollRunnable);
        if (apiClient != null) {
            apiClient.shutdown();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void pollTelemetry() {
        apiClient.getTelemetry(new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                if (!running) {
                    return;
                }
                JSONObject telemetry = response.optJSONObject("telemetry");
                updateNotification(getString(R.string.background_service_title), telemetryText(telemetry));
            }

            @Override
            public void onError(String message, Throwable throwable) {
                if (!running) {
                    return;
                }
                updateNotification(
                        getString(R.string.background_service_title),
                        getString(R.string.background_service_disconnected)
                );
            }
        });
    }

    private String telemetryText(JSONObject telemetry) {
        if (telemetry == null) {
            return getString(R.string.background_service_waiting);
        }

        Double speedKph = readDouble(telemetry, "speed_kph");
        Integer battery = readInteger(telemetry, "battery_percent");
        String mode = readString(telemetry, "mode");
        if (speedKph == null && battery == null && mode.isEmpty()) {
            return getString(R.string.background_service_waiting);
        }

        String speedText = speedKph == null ? "--" : speedFormat.format(speedKph) + " km/h";
        String batteryText = battery == null ? "--" : battery + "%";
        String modeText = mode.isEmpty() ? "--" : mode;
        return "속도 " + speedText + " | 배터리 " + batteryText + " | 모드 " + modeText;
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

    private void updateNotification(String title, String text) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, text));
    }

    private Notification buildNotification(String title, String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            return;
        }
        startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.background_service_channel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.background_service_channel_description));
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(channel);
    }
}
