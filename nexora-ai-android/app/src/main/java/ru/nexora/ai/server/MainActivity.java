package ru.nexora.ai.server;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;

public class MainActivity extends Activity {
    private static final String PREFS = "nexora_host";
    private static final String TOKEN_KEY = "api_token";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView tokenView;

    private final Runnable statusPoll = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String token = prefs.getString(TOKEN_KEY, null);
        if (token == null) {
            token = createToken();
            prefs.edit().putString(TOKEN_KEY, token).apply();
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 48, 40, 40);
        root.setBackgroundColor(0xFF101019);

        TextView title = new TextView(this);
        title.setText("NEXORA AI");
        title.setTextSize(30);
        title.setTextColor(0xFFEDE7FF);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Android Host · Prototype 0.1.1");
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xFFB7A8D8);
        root.addView(subtitle, matchWrap());

        status = new TextView(this);
        status.setText("Server stopped");
        status.setTextSize(18);
        status.setTextColor(0xFFFFCC80);
        status.setPadding(0, 36, 0, 20);
        root.addView(status, matchWrap());

        Button start = new Button(this);
        start.setText("Start local server");
        start.setOnClickListener(v -> startServer());
        root.addView(start, matchWrap());

        Button dashboard = new Button(this);
        dashboard.setText("Open NEXORA dashboard");
        dashboard.setOnClickListener(v -> startActivity(new Intent(this, DashboardActivity.class)));
        root.addView(dashboard, matchWrap());

        Button stop = new Button(this);
        stop.setText("Stop server");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ServerForegroundService.class));
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("server_status", "stopped").apply();
            refreshStatus();
        });
        root.addView(stop, matchWrap());

        Button rotateToken = new Button(this);
        rotateToken.setText("Regenerate API token");
        rotateToken.setOnClickListener(v -> regenerateToken());
        root.addView(rotateToken, matchWrap());

        TextView endpoint = new TextView(this);
        endpoint.setText("Local endpoint: http://127.0.0.1:8765/health");
        endpoint.setTextColor(0xFFEDE7FF);
        endpoint.setPadding(0, 28, 0, 8);
        root.addView(endpoint, matchWrap());

        tokenView = new TextView(this);
        tokenView.setText("Local API token (keep private):\n" + token);
        tokenView.setTextIsSelectable(true);
        tokenView.setTextColor(0xFFB7A8D8);
        root.addView(tokenView, matchWrap());

        TextView note = new TextView(this);
        note.setText("This prototype listens on 127.0.0.1 only. Other devices cannot connect yet. Keep this app private and do not share the token.");
        note.setTextColor(0xFF9D98AA);
        note.setPadding(0, 24, 0, 0);
        root.addView(note, matchWrap());

        setContentView(root);
        refreshStatus();
        requestNotificationPermissionIfNeeded();
        // Open the actual AI workspace immediately; the host controls remain behind it.
        startActivity(new Intent(this, DashboardActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(statusPoll);
        handler.post(statusPoll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(statusPoll);
        super.onPause();
    }

    private void refreshStatus() {
        if (status == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String state = prefs.getString("server_status", "stopped");
        if ("running".equals(state)) {
            status.setText("Server running · http://127.0.0.1:8765/health");
            status.setTextColor(0xFF80CBC4);
        } else if ("starting".equals(state)) {
            status.setText("Starting local server…");
            status.setTextColor(0xFFFFCC80);
        } else if ("error".equals(state)) {
            String error = prefs.getString("server_error", "Unknown startup error");
            status.setText("Server failed: " + error);
            status.setTextColor(0xFFFF8A80);
        } else {
            status.setText("Server stopped");
            status.setTextColor(0xFFFFCC80);
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private String createToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) {
            result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private void startServer() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putString("server_status", "starting")
                .remove("server_error").apply();
        try {
            Intent intent = new Intent(this, ServerForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            refreshStatus();
        } catch (RuntimeException error) {
            String message = error.getMessage();
            prefs.edit().putString("server_status", "error")
                    .putString("server_error", message == null
                            ? error.getClass().getSimpleName() : message).apply();
            refreshStatus();
        }
    }

    private void regenerateToken() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if ("running".equals(prefs.getString("server_status", "stopped"))
                || "starting".equals(prefs.getString("server_status", "stopped"))) {
            Toast.makeText(this, "Stop the server before regenerating its token", Toast.LENGTH_LONG).show();
            return;
        }
        String token = createToken();
        prefs.edit().putString(TOKEN_KEY, token).apply();
        tokenView.setText("Local API token (keep private):\n" + token);
        Toast.makeText(this, "New API token created", Toast.LENGTH_SHORT).show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }
}
