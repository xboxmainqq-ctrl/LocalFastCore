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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Android entry screen for the NEXORA client.
 *
 * <p>This screen connects to the first-party NEXORA server and displays its
 * world metadata and a bounded 3x3 voxel preview. Authentication, dynamic
 * view-distance streaming, and interactive gameplay remain pending stages.</p>
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
    private ChunkPreviewView chunkPreview;
    private Button cameraModeButton;
    private boolean firstPersonCamera;
    private TextView movementStatus;
    private TextView playersStatus;
    private final Map<UUID, String> remotePlayerNames = new LinkedHashMap<>();

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

        TextView subtitle = text("NEXORA • ANDROID CLIENT", 12, ACCENT, true);
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

        addSpace(page, 18);
        TextView mapTitle = text("3D МИР NEXORA • OPENGL ES 2.0", 12, MUTED, true);
        page.addView(mapTitle, matchWrap());
        addSpace(page, 8);
        chunkPreview = new ChunkPreviewView(this);
        chunkPreview.setMovementListener((x, y, z) -> {
            NetherNetAndroidClient client = netherNetClient;
            if (client != null && client.isConnected()) {
                client.sendPlayerMove(x, y, z);
            }
        });
        chunkPreview.setVisibility(View.GONE);
        page.addView(chunkPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        addSpace(page, 8);
        LinearLayout playersCard = card();
        page.addView(playersCard, matchWrap());
        playersCard.addView(text("ИГРОКИ В МИРЕ", 12, MUTED, true), matchWrap());
        addSpace(playersCard, 6);
        playersStatus = text("Других игроков пока нет", 13, TEXT, false);
        playersCard.addView(playersStatus, matchWrap());
        addSpace(page, 8);
        cameraModeButton = button("ВИД: СВОБОДНАЯ КАМЕРА");
        page.addView(cameraModeButton, matchWrap());
        cameraModeButton.setOnClickListener(view -> {
            firstPersonCamera = !firstPersonCamera;
            chunkPreview.setFirstPersonMode(firstPersonCamera);
            cameraModeButton.setText(firstPersonCamera
                    ? "ВИД: ОТ ПЕРВОГО ЛИЦА" : "ВИД: СВОБОДНАЯ КАМЕРА");
        });
        addSpace(page, 8);
        LinearLayout movementPanel = card();
        page.addView(movementPanel, matchWrap());
        movementPanel.addView(text("УПРАВЛЕНИЕ ПЕРСОНАЖЕМ", 12, MUTED, true), matchWrap());
        addSpace(movementPanel, 8);
        TextView movementHint = text("Движение отправляется серверу NEXORA • сервер подтверждает координаты", 12, MUTED, false);
        movementPanel.addView(movementHint, matchWrap());
        addSpace(movementPanel, 10);
        LinearLayout forwardRow = new LinearLayout(this);
        forwardRow.setGravity(Gravity.CENTER);
        movementPanel.addView(forwardRow, matchWrap());
        Button forwardButton = button("ВПЕРЁД");
        forwardRow.addView(forwardButton, new LinearLayout.LayoutParams(dp(132), dp(48)));
        forwardButton.setOnClickListener(view -> chunkPreview.movePlayer(1f, 0f));
        LinearLayout sideRow = new LinearLayout(this);
        sideRow.setGravity(Gravity.CENTER);
        movementPanel.addView(sideRow, matchWrap());
        Button leftButton = button("◀");
        Button backButton = button("НАЗАД");
        Button rightButton = button("▶");
        sideRow.addView(leftButton, new LinearLayout.LayoutParams(dp(64), dp(48)));
        sideRow.addView(backButton, new LinearLayout.LayoutParams(dp(112), dp(48)));
        sideRow.addView(rightButton, new LinearLayout.LayoutParams(dp(64), dp(48)));
        leftButton.setOnClickListener(view -> chunkPreview.movePlayer(0f, -1f));
        backButton.setOnClickListener(view -> chunkPreview.movePlayer(-1f, 0f));
        rightButton.setOnClickListener(view -> chunkPreview.movePlayer(0f, 1f));
        addSpace(movementPanel, 8);
        Button jumpButton = button("ПРЫЖОК");
        movementPanel.addView(jumpButton, matchWrap());
        jumpButton.setOnClickListener(view -> chunkPreview.jumpPlayer());
        addSpace(page, 6);
        page.addView(text("Проведи пальцем — повернуть камеру • двумя пальцами — приблизить или отдалить",
                12, MUTED, false), matchWrap());

        addSpace(page, 20);
        LinearLayout infoCard = card();
        page.addView(infoCard, matchWrap());
        infoCard.addView(text("ВЕРСИЯ ПРОЕКТА", 12, MUTED, true), matchWrap());
        addSpace(infoCard, 8);
        infoCard.addView(text("NEXORA Client 0.1.0-dev", 15, TEXT, true), matchWrap());
        addSpace(infoCard, 8);
        infoCard.addView(text("Внутренний протокол NEXORA v1", 13, MUTED, false), matchWrap());
        addSpace(infoCard, 8);
        infoCard.addView(text("Режим: перспективный 3D-рендер • прототип камеры", 13, MUTED, false), matchWrap());

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
            public void onNexoraReady(String serverId, int maxMessageBytes) {
                connectionStatus.setText("Протокол согласован • сервер " + serverId);
            }

            @Override
            public void onWorldInfoReceived(NexoraAndroidProtocolCodec.WorldInfo world) {
                chunkPreview.setCenter(Math.floorDiv(world.spawnX(), 16),
                        Math.floorDiv(world.spawnZ(), 16));
                chunkPreview.setPlayerSpawn(world.spawnX(), world.spawnY(), world.spawnZ());
                chunkPreview.setVisibility(View.VISIBLE);
                connectionStatus.setText("Мир: " + world.worldId()
                        + "\nВысота: " + world.minY() + ".." + (world.maxYExclusive() - 1)
                        + "\nПоявление: " + world.spawnX() + ", " + world.spawnY() + ", " + world.spawnZ()
                        + "\nТиков: " + world.tickRate() + "/с"
                        + "\nЗагружено чанков: " + world.loadedChunks());
            }

            @Override
            public void onServerPosition(NexoraAndroidProtocolCodec.PlayerPosition position) {
                chunkPreview.setServerPosition(position.x(), position.y(), position.z());
            }

            @Override
            public void onRemotePlayerState(
                    NexoraAndroidProtocolCodec.RemotePlayerState player) {
                chunkPreview.setRemotePlayerState(player);
                String suffix = player.playerId().toString().substring(0, 4).toUpperCase(java.util.Locale.ROOT);
                remotePlayerNames.put(player.playerId(), "Игрок-" + suffix);
                refreshRemotePlayers();
            }

            @Override
            public void onRemotePlayerLeft(java.util.UUID playerId) {
                chunkPreview.removeRemotePlayer(playerId);
                remotePlayerNames.remove(playerId);
                refreshRemotePlayers();
            }

            @Override
            public void onChunkDataReceived(NexoraAndroidProtocolCodec.ChunkData chunk) {
                chunkPreview.setChunk(chunk);
                connectionStatus.setText("Получено чанков: "
                        + chunkPreview.receivedChunkCount() + "/9"
                        + "\nПоследний чанк: " + chunk.chunkX() + ", " + chunk.chunkZ()
                        + "\nБлоков в нём: " + chunk.blocks().size()
                        + "\nИзометрический объёмный просмотр");
            }

            @Override
            public void onNetworkSettingsReceived(BedrockAndroidNetworkSettings settings) {
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

    private void refreshRemotePlayers() {
        if (playersStatus == null) return;
        if (remotePlayerNames.isEmpty()) {
            playersStatus.setText("Других игроков пока нет");
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("Онлайн: ").append(remotePlayerNames.size());
        for (String name : remotePlayerNames.values()) {
            text.append((char) 10).append("• ").append(name);
        }
        playersStatus.setText(text.toString());
    }

    @Override
    protected void onPause() {
        if (chunkPreview != null) {
            chunkPreview.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (chunkPreview != null) {
            chunkPreview.onResume();
        }
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
