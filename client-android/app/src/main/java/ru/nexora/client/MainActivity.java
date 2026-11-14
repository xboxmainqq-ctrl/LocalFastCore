package ru.nexora.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.GridLayout;
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
    private EditText playerNickname;
    private Button connectButton;
    private NetherNetAndroidClient netherNetClient;
    private ChunkPreviewView chunkPreview;
    private Button cameraModeButton;
    private Button skinButton;
    private Button customSkinButton;
    private ImageView customSkinPreview;
    private int selectedSkin;
    private boolean firstPersonCamera;
    private TextView movementStatus;
    private TextView playersStatus;
    private TextView inventoryStatus;
    private final TextView[] inventorySlotViews = new TextView[36];
    private int selectedInventorySlot = -1;
    private final Map<UUID, String> remotePlayerNames = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selectedSkin = getPreferences(MODE_PRIVATE).getInt("nexora_player_skin", 0);
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

        addSpace(serverCard, 10);
        playerNickname = new EditText(this);
        playerNickname.setSingleLine(true);
        playerNickname.setText("Игрок");
        playerNickname.setTextColor(TEXT);
        playerNickname.setHintTextColor(MUTED);
        playerNickname.setHint("Игровой ник (до 16 символов)");
        playerNickname.setTextSize(16);
        playerNickname.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        playerNickname.setPadding(dp(14), dp(12), dp(14), dp(12));
        playerNickname.setBackground(rounded(Color.rgb(14, 20, 31), 12));
        serverCard.addView(playerNickname, matchWrap());

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
        addSpace(playersCard, 14);
        playersCard.addView(text("ИНВЕНТАРЬ", 12, MUTED, true), matchWrap());
        addSpace(playersCard, 6);
        inventoryStatus = text("Ожидаем данные от сервера...", 14, TEXT, false);
        inventoryStatus.setLineSpacing(dp(4), 1.0f);
        playersCard.addView(inventoryStatus, matchWrap());
        addSpace(playersCard, 8);
        GridLayout inventoryGrid = new GridLayout(this);
        inventoryGrid.setColumnCount(6);
        inventoryGrid.setRowCount(6);
        for (int slot = 0; slot < inventorySlotViews.length; slot++) {
            TextView cell = text("—", 10, MUTED, true);
            cell.setGravity(Gravity.CENTER);
            cell.setMinHeight(dp(48));
            cell.setBackground(rounded(Color.rgb(14, 20, 31), 8));
            GridLayout.LayoutParams cellParams = new GridLayout.LayoutParams(
                    GridLayout.spec(slot / 6, 1f), GridLayout.spec(slot % 6, 1f));
            cellParams.width = 0;
            cellParams.height = dp(50);
            cellParams.setMargins(dp(2), dp(2), dp(2), dp(2));
            inventoryGrid.addView(cell, cellParams);
            inventorySlotViews[slot] = cell;
        }
        playersCard.addView(inventoryGrid, matchWrap());
        addSpace(playersCard, 10);
        LinearLayout inventoryActions = new LinearLayout(this);
        inventoryActions.setOrientation(LinearLayout.VERTICAL);
        playersCard.addView(inventoryActions, matchWrap());
        Button dropOneButton = button("ВЫБРОСИТЬ 1");
        Button dropStackButton = button("ВЫБРОСИТЬ СТАК");
        Button useItemButton = button("ИСПОЛЬЗОВАТЬ");
        inventoryActions.addView(dropOneButton, matchWrap());
        addSpace(inventoryActions, 6);
        inventoryActions.addView(dropStackButton, matchWrap());
        addSpace(inventoryActions, 6);
        inventoryActions.addView(useItemButton, matchWrap());
        dropOneButton.setOnClickListener(view -> sendInventoryAction(
                NexoraAndroidProtocolCodec.InventoryAction.DROP_ONE));
        dropStackButton.setOnClickListener(view -> sendInventoryAction(
                NexoraAndroidProtocolCodec.InventoryAction.DROP_STACK));
        useItemButton.setOnClickListener(view -> sendInventoryAction(
                NexoraAndroidProtocolCodec.InventoryAction.USE));
        addSpace(playersCard, 10);
        skinButton = button("ВНЕШНОСТЬ: НЕОНОВЫЙ ПИЛОТ");
        playersCard.addView(skinButton, matchWrap());
        updateSkinButton();
        skinButton.setOnClickListener(view -> showSkinPicker());
        addSpace(playersCard, 8);
        customSkinPreview = new ImageView(this);
        customSkinPreview.setAdjustViewBounds(true);
        customSkinPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        customSkinPreview.setBackground(rounded(Color.rgb(14, 20, 31), 12));
        LinearLayout.LayoutParams skinPreviewParams = new LinearLayout.LayoutParams(dp(128), dp(128));
        skinPreviewParams.gravity = Gravity.CENTER_HORIZONTAL;
        playersCard.addView(customSkinPreview, skinPreviewParams);
        addSpace(playersCard, 8);
        customSkinButton = button("ЗАГРУЗИТЬ PNG-СКИН (64x64)");
        playersCard.addView(customSkinButton, matchWrap());
        customSkinButton.setOnClickListener(view -> openCustomSkinPicker());
        refreshCustomSkinButton();
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
        infoCard.addView(text("Внутренний протокол NEXORA v1.2", 13, MUTED, false), matchWrap());
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
        String nickname = playerNickname.getText().toString().trim();
        if (nickname.isEmpty() || nickname.length() > 16) {
            playerNickname.setError("Ник должен содержать от 1 до 16 символов");
            connectButton.setEnabled(true);
            return;
        }
        netherNetClient.connect(address, nickname, selectedSkin, new NetherNetAndroidClient.Listener() {
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
                remotePlayerNames.put(player.playerId(), player.playerName());
                refreshRemotePlayers();
            }

            @Override
            public void onRemotePlayerLeft(java.util.UUID playerId) {
                chunkPreview.removeRemotePlayer(playerId);
                remotePlayerNames.remove(playerId);
                refreshRemotePlayers();
            }

            @Override
            public void onInventoryReceived(NexoraAndroidProtocolCodec.InventoryState inventory) {
                refreshInventory(inventory);
            }

            @Override
            public void onInventoryActionResult(
                    NexoraAndroidProtocolCodec.InventoryActionResult result) {
                String message = switch (result.status()) {
                    case OK -> "Действие выполнено";
                    case INVALID_SLOT -> "Неверная ячейка";
                    case EMPTY_SLOT -> "Ячейка пуста";
                    case NOT_USABLE -> "У этого предмета пока нет действия";
                };
                inventoryStatus.setText(message + " • предметы: " + result.action());
                selectedInventorySlot = -1;
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


    private static final int REQUEST_CUSTOM_SKIN = 4107;

    private void openCustomSkinPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        startActivityForResult(intent, REQUEST_CUSTOM_SKIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CUSTOM_SKIN || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        try {
            NexoraSkinFile.importPng(this, data.getData());
            refreshCustomSkinButton();
            Toast.makeText(this,
                    "PNG проверен и сохранён. Ниже доступен предпросмотр; 3D-текстура будет следующим этапом.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception invalid) {
            Toast.makeText(this, "Не удалось загрузить скин: " + invalid.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void refreshCustomSkinButton() {
        if (customSkinButton == null) return;
        java.io.File storedSkin = NexoraSkinFile.storedFile(this);
        boolean saved = storedSkin.isFile() && storedSkin.length() > 0L;
        if (customSkinPreview != null) {
            if (saved) {
                customSkinPreview.setImageBitmap(BitmapFactory.decodeFile(storedSkin.getAbsolutePath()));
                customSkinPreview.setVisibility(View.VISIBLE);
            } else {
                customSkinPreview.setImageDrawable(null);
                customSkinPreview.setVisibility(View.GONE);
            }
        }
        customSkinButton.setText(saved
                ? "PNG-СКИН СОХРАНЁН • ПРЕДПРОСМОТР"
                : "ЗАГРУЗИТЬ PNG-СКИН (64x64)");
    }

    private void showSkinPicker() {
        String[] names = {"Неоновый пилот", "Лесной исследователь", "Алый страж", "Сумеречный маг", "Песчаный странник"};
        new AlertDialog.Builder(this)
                .setTitle("Внешность персонажа NEXORA")
                .setSingleChoiceItems(names, selectedSkin, (dialog, which) -> {
                    selectedSkin = which;
                    getPreferences(MODE_PRIVATE).edit().putInt("nexora_player_skin", which).apply();
                    updateSkinButton();
                    dialog.dismiss();
                })
                .setNegativeButton("ЗАКРЫТЬ", null)
                .show();
    }

    private void updateSkinButton() {
        if (skinButton == null) return;
        String[] names = {"НЕОНОВЫЙ ПИЛОТ", "ЛЕСНОЙ ИССЛЕДОВАТЕЛЬ", "АЛЫЙ СТРАЖ", "СУМЕРЕЧНЫЙ МАГ", "ПЕСЧАНЫЙ СТРАННИК"};
        skinButton.setText("ВНЕШНОСТЬ: " + names[Math.max(0, Math.min(names.length - 1, selectedSkin))]);
    }

    private void refreshInventory(NexoraAndroidProtocolCodec.InventoryState inventory) {
        if (inventoryStatus == null) return;
        inventoryStatus.setText("Предметов: " + inventory.totalItems()
                + " • занято ячеек: " + inventory.stacks().size() + "/36"
                + (selectedInventorySlot >= 0 ? " • выбрана ячейка " + (selectedInventorySlot + 1) : ""));
        for (int index = 0; index < inventorySlotViews.length; index++) {
            TextView cell = inventorySlotViews[index];
            if (cell != null) {
                cell.setText("—");
                cell.setTextColor(MUTED);
                cell.setBackground(rounded(index == selectedInventorySlot
                        ? Color.rgb(55, 93, 150) : Color.rgb(14, 20, 31), 8));
            }
        }
        for (NexoraAndroidProtocolCodec.InventoryStack stack : inventory.stacks()) {
            int slot = stack.slotIndex();
            if (slot < 0 || slot >= inventorySlotViews.length) continue;
            String itemName = stack.itemId();
            int separator = itemName.indexOf(':');
            if (separator >= 0) itemName = itemName.substring(separator + 1);
            itemName = itemName.replace('_', ' ');
            if (itemName.length() > 9) itemName = itemName.substring(0, 8) + "…";
            inventorySlotViews[slot].setText(itemName + (char) 10 + "×" + stack.count());
            inventorySlotViews[slot].setTextColor(TEXT);
        }
        for (int index = 0; index < inventorySlotViews.length; index++) {
            final int slot = index;
            inventorySlotViews[index].setOnClickListener(view -> {
                if (selectedInventorySlot < 0) {
                    selectedInventorySlot = slot;
                    inventoryStatus.setText("Выбрана ячейка " + (slot + 1)
                            + " • нажми другую ячейку для перемещения");
                    refreshInventorySelection();
                    return;
                }
                int source = selectedInventorySlot;
                selectedInventorySlot = -1;
                NetherNetAndroidClient client = netherNetClient;
                if (client == null || !client.sendInventoryAction(
                        NexoraAndroidProtocolCodec.InventoryAction.MOVE_OR_SWAP, source, slot)) {
                    inventoryStatus.setText("Не удалось отправить перемещение");
                } else {
                    inventoryStatus.setText("Перемещение отправлено серверу...");
                }
                refreshInventorySelection();
            });
        }
    }

    private void refreshInventorySelection() {
        for (int index = 0; index < inventorySlotViews.length; index++) {
            inventorySlotViews[index].setBackground(rounded(index == selectedInventorySlot
                    ? Color.rgb(55, 93, 150) : Color.rgb(14, 20, 31), 8));
        }
    }

    private void sendInventoryAction(NexoraAndroidProtocolCodec.InventoryAction action) {
        if (selectedInventorySlot < 0) {
            inventoryStatus.setText("Сначала выбери ячейку инвентаря");
            return;
        }
        NetherNetAndroidClient client = netherNetClient;
        if (client == null || !client.sendInventoryAction(action, selectedInventorySlot, 0)) {
            inventoryStatus.setText("Не удалось отправить действие серверу");
        } else {
            inventoryStatus.setText("Действие отправлено серверу...");
        }
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
