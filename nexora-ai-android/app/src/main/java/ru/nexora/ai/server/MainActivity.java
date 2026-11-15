package ru.nexora.ai.server;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.security.SecureRandom;

public class MainActivity extends Activity {
    private static final String PREFS = "nexora_host";
    private static final String TOKEN_KEY = "api_token";
    private TextView status;
    private TextView tokenView;

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
        subtitle.setText("Android Host · Prototype 0.1.0");
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

        Button dashboard = new Button(this);\n        dashboard.setText("Open NEXORA dashboard");\n        dashboard.setOnClickListener(v -> startActivity(new Intent(this, DashboardActivity.class)));\n        root.addView(dashboard, matchWrap());\n\n        Button stop = new Button(this);
        stop.setText("Stop server");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ServerForegroundService.class));
            status.setText("Server stopped");
        });
        root.addView(stop, matchWrap());

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
        requestNotificationPermissionIfNeeded();
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
        Intent intent = new Intent(this, ServerForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        status.setText("Starting local server…");
        status.setTextColor(0xFF80CBC4);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }
}
