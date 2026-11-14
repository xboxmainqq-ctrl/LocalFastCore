package ru.nexora.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Android entry screen for the NEXORA client.
 *
 * <p>This screen connects the NetherNet transport and completes the initial
 * NetworkSettings exchange. Player authentication and the full Bedrock play
 * session remain separate pending stages.</p>
 */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(12, 16, 25);
    private static final int PANEL = Color.rgb(24, 31, 45);
    private static final int ACCENT = Color.rgb(91, 156, 255);
    private static final int TEXT = Color.rgb(240, 244, 252);
    private static final int MUTED = Color.rgb(157, 171, 193);

    private TextView connectionStatus;
    private EditText serverAddress;
    private Button connectButton;
    private NetherNetAndroidClient netherNetClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createScreen());
    }

    private View createScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(36), dp(24), dp(28));
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView logo = text("NEXORA", 34, TEXT, true);
        logo.setLetterSpacing(0.12f);
        logo.setGravity(Gravity.CENTER);
        page.addView(logo, matchWrap());

        TextView subtitle = text("BEDROCK • ANDROID CLIENT", 12, ACCENT, true);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLetterSpacing(0.10f);
        addSpace(page, 8);
        page.addView(subtitle, matchWrap());

        addSpace(page, 34);
        LinearLayout serverCard = card();
        page.addView(serverCard, matchWrap());

        serverCard.addView(text("ПОДКЛЮЧЕНИЕ К СЕРВЕРУ", 13, MUTED, true), matchWrap());
        addSpace(serverCard, 14);

        serverAddress = new EditText(this);
        serverAddress.setSingleLine(true);
        serverAddress.setTextColor(TEXT);
        serverAddress.setHintTextColor(MUTED);
        serverAddress.setHint("Адрес сервера");
        serverAddress.setTextSize(16);
        serverAddress.setInputType( android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        serverAddress.setPadding(dp(14), dp(12), dp(14), dp(12));
        serverAddress.setBackground(rounded(Color.rgb(14, 20, 31), 12));
        serverCard.addView(serverAddress, matchWrap());

        addSpace(serverCard, 12);
        connectButton = button("ПОДКЛЮЧИТЬСЯ");
        serverCard.addView(connectButton, matchWrap());
        connectButton.setOnClickListener(view -> toggleConnection());

        addSpace(page, 20);
        LinearLayout statusCard = card();
        page.addView(statusCard, matchWrap());
        statusCard.addView(text("СОСТОЯНИЕ КЛИЕНТА", 12, MUTED, true), matchWrap());
        addSpace(statusCard, 10);

        connectionStatus = text("Не подключён", 17, TEXT, true);
        statusCard.addView(connectionStatus, matchWrap());
        addSpace(statusCard, 6);
        statusCard.addView(text(
                "NetherNet: HTTP(S) signaling, WebRTC и проверка ключа сервера.",
                13, MUTED, false
        ), matchWrap());

        addSpace(page, 20);
        LinearLayout infoCard = card();
        page.addView(infoCard, matchWrap());
        infoCard.addView(text("ВЕРСИЯ ПРОЕКТА", 12, MUTED, true), matchWrap());
        addSpace(infoCard, 8);
        infoCard.addView(text("NEXORA Client 0.1.0-dev", 15, TEXT, true), matchWrap());
        addSpace(infoCard, 8);
        infoCard.addView(text("Цель протокола: Bedrock 1.26.60 • 2225", 13, MUTED, false), matchWrap());
        addSpace(infoCard, 8);
        infoCard.addView(text("Режим: подключение и тестирование транспорта", 13, MUTED, false), matchWrap());

        addSpace(page, 26);
        TextView footer = text("СОЗДАЁМ СВОЙ МИР", 11, MUTED, true);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.14f);
        page.addView(footer, matchWrap());

        return scroll;
    }

    private void toggleConnection() {
        if (netherNetClient != null && netherNetClient.isConnected()) {
            netherNetClient.close();
            netherNetClient = null;
            connectionStatus.setText("Отключён");
            connectButton.setText("ПОДКЛЮЧИТЬСЯ");
            connectButton.setEnabled(true);
            return;
        }
        if (netherNetClient != null) {
            return;
        }
        String address = serverAddress.getText().toString().trim();
        if (address.isEmpty()) {
            serverAddress.setError("Введи адрес сервера");
            return;
        }

        connectButton.setEnabled(false);
        connectionStatus.setText("Запуск NetherNet...");
        netherNetClient = new NetherNetAndroidClient(this);
        netherNetClient.connect(address, new NetherNetAndroidClient.Listener() {
            @Override
            public void onStatus(String status) {
                connectionStatus.setText(status);
            }

            @Override
            public void onTrustConfirmationRequired(String domain, String fingerprint,
                                                    java.util.function.Consumer<Boolean> decision) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Подтвердить ключ сервера?")
                        .setMessage("Адрес: " + serverAddress.getText().toString().trim()
                                + "\nДомен (не проверен): " + domain
                                + "\n\nSHA-256 отпечаток ключа:\n" + fingerprint
                                + "\n\nЭто первое подключение по HTTP. Доверяй ключу только если можешь сверить отпечаток с владельцем сервера.")
                        .setPositiveButton("ДОВЕРЯТЬ КЛЮЧУ",
                                (dialog, which) -> decision.accept(true))
                        .setNegativeButton("ОТКЛОНИТЬ",
                                (dialog, which) -> decision.accept(false))
                        .setOnCancelListener(dialog -> decision.accept(false))
                        .setCancelable(false)
                        .show();
            }

            @Override
            public void onConnected() {
                connectionStatus.setText("WebRTC подключён • оба канала открыты");
                connectButton.setText("ОТКЛЮЧИТЬСЯ");
                connectButton.setEnabled(true);
            }

            @Override
            public void onNetworkSettingsReceived(NetherNetAndroidClient.NetworkSettings settings) {
                connectionStatus.setText("NetworkSettings получены • вход игрока ещё не подключён");
            }

            @Override
            public void onError(String message) {
                connectionStatus.setText("Ошибка: " + message);
                if (netherNetClient != null) {
                    netherNetClient.close();
                    netherNetClient = null;
                }
                connectButton.setText("ПОВТОРИТЬ");
                connectButton.setEnabled(true);
            }
        });
        connectButton.setText("ПОДКЛЮЧАЕМСЯ...");
    }

    @Override
    protected void onDestroy() {
        if (netherNetClient != null) {
            netherNetClient.close();
            netherNetClient = null;
        }
        super.onDestroy();
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        layout.setBackground(rounded(PANEL, 18));
        return layout;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(rounded(ACCENT, 12));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        View spacer = new View(this);
        parent.addView(spacer, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
