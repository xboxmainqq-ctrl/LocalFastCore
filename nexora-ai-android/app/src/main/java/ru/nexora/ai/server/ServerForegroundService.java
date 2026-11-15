package ru.nexora.ai.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

public class ServerForegroundService extends Service {
    private static final String CHANNEL_ID = "nexora_host_server";
    private LocalApiServer apiServer;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences("nexora_host", MODE_PRIVATE);
        String token = prefs.getString("api_token", "");
        if (apiServer == null) {
            apiServer = new LocalApiServer(token);
            apiServer.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (apiServer != null) {
            apiServer.stop();
            apiServer = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "NEXORA AI Host", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when the local NEXORA AI host is running");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("NEXORA AI Host is running")
                .setContentText("Local development server · 127.0.0.1:8765")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }
}
