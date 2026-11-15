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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;

/**
 * Android entry screen for the NEXORA client.
 *
 * <p>This screen connects to the first-party NEXORA server and displays its
 * world metadata, authoritative movement, and a streamed 3x3 voxel view.
 * Interactive block editing remains a later gameplay stage.</p>
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
    private ChunkPreviewView localPreview;
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
    private final TextView[] localHotbarViews = new TextView[9];
    private final String[] localHotbarItems = new String[9];
    private final int[] localHotbarCounts = new int[9];
    private int selectedLocalHotbarSlot = 0;
    private final Map<UUID, String> remotePlayerNames = new LinkedHashMap<>();
    private ExecutorService previewGenerationExecutor;
    private LocalWorldStore localWorldStore;
    private volatile double localPlayerX;
    private volatile double localPlayerY;
    private volatile double localPlayerZ;
    private int streamingCenterChunkX = Integer.MIN_VALUE;
    private int streamingCenterChunkZ = Integer.MIN_VALUE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selectedSkin = getPreferences(MODE_PRIVATE).getInt("nexora_player_skin", 0);
        localWorldStore = new LocalWorldStore(new File(getFilesDir(), "worlds"));
        setContentView(createHomeScreen());
    }

    private View createHomeScreen() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(24), dp(48), dp(24), dp(32));
        page.setBackgroundColor(BACKGROUND);

        TextView logo = text("NEXORA", 36, TEXT, true);
        logo.setGravity(Gravity.CENTER);
        logo.setLetterSpacing(0.16f);
        page.addView(logo, matchWrap());

        TextView subtitle = text("ТВОЙ МИР. ТВОЯ ИГРА.", 12, ACCENT, true);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLetterSpacing(0.12f);
        addSpace(page, 10);
        page.addView(subtitle, matchWrap());
        addSpace(page, 42);

        Button profile = menuButton("ПРОФИЛЬ", "Игровой ник и внешность");
        page.addView(profile, matchWrap());
        addSpace(page, 14);
        Button servers = menuButton("СЕРВЕРА NEXORA", "Официальные миры NEXORA");
        page.addView(servers, matchWrap());
        addSpace(page, 14);
        Button resourcePacks = menuButton("РЕСУРС-ПАКИ", "Импорт, выбор и удаление текстур");
        page.addView(resourcePacks, matchWrap());
        addSpace(page, 14);
        resourcePacks.setOnClickListener(v -> showResourcePackManager());

        Button singlePlayer = menuButton("ОДИНОЧНЫЙ МИР", "Играть без подключения к серверу");
        page.addView(singlePlayer, matchWrap());

        profile.setOnClickListener(v -> showProfileDialog());
        servers.setOnClickListener(v -> showServersScreen());
        singlePlayer.setOnClickListener(v -> showSinglePlayerScreen());

        addSpace(page, 34);
        TextView footer = text("NEXORA • ANDROID", 11, MUTED, true);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.12f);
        page.addView(footer, matchWrap());
        return page;
    }

    private Button menuButton(String title, String subtitle) {
        Button button = new Button(this);
        button.setText(title + "\n" + subtitle);
        button.setTextColor(TEXT);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(82));
        GradientDrawable shape = rounded(PANEL, 18);
        shape.setStroke(dp(1), ACCENT);
        button.setBackground(shape);
        return button;
    }

    private void showProfileDialog() {
        EditText nickname = new EditText(this);
        nickname.setSingleLine(true);
        nickname.setText(getPreferences(MODE_PRIVATE).getString("nexora_player_nickname", "Игрок"));
        nickname.setHint("Игровой ник");
        nickname.setTextColor(TEXT);
        nickname.setHintTextColor(MUTED);
        new AlertDialog.Builder(this)
                .setTitle("ПРОФИЛЬ NEXORA")
                .setMessage("Настрой свой игровой профиль")
                .setView(nickname)
                .setPositiveButton("СОХРАНИТЬ", (dialog, which) -> {
                    String value = nickname.getText().toString().trim();
                    if (value.isEmpty() || value.length() > 16) {
                        Toast.makeText(this, "Ник должен содержать от 1 до 16 символов", Toast.LENGTH_LONG).show();
                        return;
                    }
                    getPreferences(MODE_PRIVATE).edit()
                            .putString("nexora_player_nickname", value).apply();
                    Toast.makeText(this, "Профиль сохранён", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("ВЫБРАТЬ СКИН", (dialog, which) -> showSkinPicker())
                .setNegativeButton("НАЗАД", null)
                .show();
    }

    private void showServersScreen() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(28), dp(22), dp(22));
        content.setBackgroundColor(BACKGROUND);
        TextView title = text("СЕРВЕРА NEXORA", 26, TEXT, true);
        content.addView(title, matchWrap());
        addSpace(content, 12);
        LinearLayout card = card();
        content.addView(card, matchWrap());
        card.addView(text("ОФИЦИАЛЬНЫЕ СЕРВЕРА", 13, ACCENT, true), matchWrap());
        addSpace(card, 10);
        card.addView(text("Здесь будут отображаться сервера NEXORA, размещённые на хостингах. Выберешь сервер и нажмёшь «Подключиться» — вводить адрес вручную не придётся.", 14, TEXT, false), matchWrap());
        addSpace(card, 12);
        card.addView(text("Список пока пуст. Сервера добавим сюда после их запуска на хостингах.", 13, MUTED, false), matchWrap());
        addSpace(content, 20);
        Button back = button("НАЗАД");
        content.addView(back, matchWrap());
        back.setOnClickListener(v -> setContentView(createHomeScreen()));
        setContentView(content);
    }

    private void showSinglePlayerScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("ОДИНОЧНЫЕ МИРЫ", 25, TEXT, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title, matchWrap());
        addSpace(page, 8);
        TextView subtitle = text("Создавай миры и возвращайся к ним позже", 13, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        page.addView(subtitle, matchWrap());
        addSpace(page, 20);

        Button create = button("＋  СОЗДАТЬ НОВЫЙ МИР");
        page.addView(create, matchWrap());
        create.setOnClickListener(v -> showCreateWorldDialog());
        addSpace(page, 18);

        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        int count = prefs.getInt("nexora_world_count", 0);
        if (count == 0) {
            LinearLayout empty = card();
            page.addView(empty, matchWrap());
            empty.addView(text("ПОКА НЕТ МИРОВ", 14, ACCENT, true), matchWrap());
            addSpace(empty, 8);
            empty.addView(text("Нажми «Создать новый мир». Название и seed сохранятся в списке на этом устройстве.", 13, MUTED, false), matchWrap());
        } else {
            for (int i = count - 1; i >= 0; i--) {
                final String worldName = prefs.getString("nexora_world_name_" + i, "Мир " + (i + 1));
                final long worldSeed = prefs.getLong("nexora_world_seed_" + i, 0L);
                final String worldId = prefs.getString("nexora_world_id_" + i, "legacy_world_" + i);
                LinearLayout worldCard = card();
                page.addView(worldCard, matchWrap());
                worldCard.addView(text(worldName, 17, TEXT, true), matchWrap());
                addSpace(worldCard, 5);
                worldCard.addView(text("SEED: " + worldSeed, 11, ACCENT, true), matchWrap());
                addSpace(worldCard, 10);
                Button play = button("ИГРАТЬ");
                worldCard.addView(play, matchWrap());
                play.setOnClickListener(v -> launchWorldPreview(worldId, worldName, worldSeed));
                addSpace(page, 12);
            }
        }

        addSpace(page, 14);
        Button back = button("В МЕНЮ");
        page.addView(back, matchWrap());
        back.setOnClickListener(v -> setContentView(createHomeScreen()));
        setContentView(scroll);
    }

    private void showCreateWorldDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(12), dp(20), dp(4));

        EditText worldName = new EditText(this);
        worldName.setSingleLine(true);
        worldName.setHint("Название мира");
        worldName.setText("Мой мир");
        form.addView(worldName, matchWrap());

        EditText seedInput = new EditText(this);
        seedInput.setSingleLine(true);
        seedInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        seedInput.setHint("Seed мира");
        seedInput.setText(Long.toString(new SecureRandom().nextLong()));
        form.addView(seedInput, matchWrap());

        Button randomSeed = button("СЛУЧАЙНЫЙ СИД");
        form.addView(randomSeed, matchWrap());
        randomSeed.setOnClickListener(v ->
                seedInput.setText(Long.toString(new SecureRandom().nextLong())));

        new AlertDialog.Builder(this)
                .setTitle("СОЗДАНИЕ МИРА")
                .setMessage("Для нового мира автоматически создан случайный seed. Можешь оставить его или задать свой.")
                .setView(form)
                .setPositiveButton("СОЗДАТЬ МИР", (dialog, which) -> {
                    String name = worldName.getText().toString().trim();
                    if (name.isEmpty()) name = "Мой мир";
                    long seed;
                    try {
                        seed = Long.parseLong(seedInput.getText().toString().trim());
                    } catch (NumberFormatException invalidSeed) {
                        seed = new SecureRandom().nextLong();
                    }
                    android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
                    int index = prefs.getInt("nexora_world_count", 0);
                    String worldId = UUID.randomUUID().toString();
                    prefs.edit()
                            .putString("nexora_world_name_" + index, name)
                            .putLong("nexora_world_seed_" + index, seed)
                            .putString("nexora_world_id_" + index, worldId)
                            .putInt("nexora_world_count", index + 1)
                            .putString("nexora_last_world_name", name)
                            .putLong("nexora_last_world_seed", seed)
                            .apply();
                    launchWorldPreview(worldId, name, seed);
                })
                .setNegativeButton("ОТМЕНА", null)
                .show();
    }

    private void launchWorldPreview(String worldId, String worldName, long seed) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(20), dp(16), dp(20));
        page.setBackgroundColor(BACKGROUND);

        TextView title = text(worldName, 24, TEXT, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title, matchWrap());
        addSpace(page, 6);
        TextView state = text("ЛОКАЛЬНЫЙ МИР • SEED: " + seed, 11, ACCENT, true);
        state.setGravity(Gravity.CENTER);
        page.addView(state, matchWrap());
        addSpace(page, 10);

        localPreview = new ChunkPreviewView(this);
        localPreview.setFirstPersonMode(true);
        localPreview.setCenter(0, 0);
        page.addView(localPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        localPreview.setMovementListener((x, y, z) -> {
            localPlayerX = x;
            localPlayerY = y;
            localPlayerZ = z;
            try {
                localWorldStore.savePlayerPosition(worldId,
                        new LocalWorldStore.PlayerPosition(x, y, z));
            } catch (java.io.IOException failure) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Не удалось сохранить позицию игрока", Toast.LENGTH_SHORT).show());
            }
        });

        localPreview.setBlockTargetListener((x, y, z, placeX, placeY, placeZ) ->
                runOnUiThread(() -> showLocalBlockActionDialog(
                        worldId, x, y, z, placeX, placeY, placeZ)));

        TextView hotbarTitle = text("ПАНЕЛЬ ПРЕДМЕТОВ", 11, MUTED, true);
        page.addView(hotbarTitle, matchWrap());
        GridLayout localHotbar = new GridLayout(this);
        localHotbar.setColumnCount(9);
        localHotbar.setRowCount(1);
        page.addView(localHotbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        for (int slot = 0; slot < localHotbarViews.length; slot++) {
            final int slotIndex = slot;
            TextView cell = text("", 9, TEXT, true);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(1), dp(2), dp(1), dp(2));
            cell.setBackground(rounded(slot == selectedLocalHotbarSlot ? ACCENT : PANEL, 6));
            GridLayout.LayoutParams cellParams = new GridLayout.LayoutParams(
                    GridLayout.spec(0, 1f), GridLayout.spec(slot, 1f));
            cellParams.width = 0;
            cellParams.height = dp(46);
            cellParams.setMargins(dp(1), dp(1), dp(1), dp(1));
            localHotbar.addView(cell, cellParams);
            localHotbarViews[slot] = cell;
            cell.setOnClickListener(view -> {
                selectedLocalHotbarSlot = slotIndex;
                refreshLocalHotbar();
            });
        }
        refreshLocalHotbar();

        addSpace(page, 10);
        Button camera = button("ВИД: ОТ ПЕРВОГО ЛИЦА");
        page.addView(camera, matchWrap());
        camera.setOnClickListener(v -> {
            boolean firstPerson = !camera.getText().toString().contains("ПЕРВОГО");
            localPreview.setFirstPersonMode(firstPerson);
            camera.setText(firstPerson ? "ВИД: ОТ ПЕРВОГО ЛИЦА" : "ВИД: ОТ ТРЕТЬЕГО ЛИЦА");
        });

        LinearLayout forward = new LinearLayout(this);
        forward.setGravity(Gravity.CENTER);
        page.addView(forward, matchWrap());
        Button forwardButton = button("ВПЕРЁД  ▲");
        forward.addView(forwardButton, new LinearLayout.LayoutParams(dp(160), dp(52)));
        bindHoldMovement(forwardButton, () -> {
            if (localPreview != null) localPreview.movePlayer(1f, 0f);
        });

        LinearLayout directions = new LinearLayout(this);
        directions.setGravity(Gravity.CENTER);
        page.addView(directions, matchWrap());
        Button left = button("◀");
        Button backStep = button("НАЗАД  ▼");
        Button right = button("▶");
        directions.addView(left, new LinearLayout.LayoutParams(dp(72), dp(52)));
        directions.addView(backStep, new LinearLayout.LayoutParams(dp(116), dp(52)));
        directions.addView(right, new LinearLayout.LayoutParams(dp(72), dp(52)));
        bindHoldMovement(left, () -> {
            if (localPreview != null) localPreview.movePlayer(0f, -1f);
        });
        bindHoldMovement(backStep, () -> {
            if (localPreview != null) localPreview.movePlayer(-1f, 0f);
        });
        bindHoldMovement(right, () -> {
            if (localPreview != null) localPreview.movePlayer(0f, 1f);
        });
        addSpace(page, 6);
        Button jump = button("ПРЫЖОК");
        page.addView(jump, matchWrap());
        jump.setOnClickListener(v -> localPreview.jumpPlayer());
        addSpace(page, 6);
        page.addView(text("Мир хранится на устройстве. После выхода твоя позиция и созданные чанки останутся сохранены.",
                11, MUTED, false), matchWrap());
        addSpace(page, 8);
        Button exit = button("СОХРАНИТЬ И ВЫЙТИ");
        page.addView(exit, matchWrap());
        exit.setOnClickListener(v -> {
            saveLocalPlayerPosition(worldId);
            if (previewGenerationExecutor != null) {
                previewGenerationExecutor.shutdownNow();
                previewGenerationExecutor = null;
            }
            if (localPreview != null) {
                localPreview.onPause();
                localPreview = null;
            }
            setContentView(createHomeScreen());
        });
        setContentView(page);

        if (previewGenerationExecutor != null) previewGenerationExecutor.shutdownNow();
        previewGenerationExecutor = Executors.newSingleThreadExecutor();
        state.setText("ЗАГРУЗКА МИРА • SEED: " + seed);
        previewGenerationExecutor.execute(() -> {
            try {
                List<NexoraAndroidProtocolCodec.ChunkData> chunks = localWorldStore.loadChunks(worldId);
                boolean generated = chunks.isEmpty();
                int spawnY = 64;
                if (generated) {
                    NexoraWorldPreviewGenerator.PreviewData data =
                            NexoraWorldPreviewGenerator.generate(seed);
                    chunks = data.chunks();
                    spawnY = data.spawnY();
                    for (NexoraAndroidProtocolCodec.ChunkData chunk : chunks) {
                        localWorldStore.saveChunk(worldId, chunk);
                    }
                }
                LocalWorldStore.PlayerPosition savedPosition = localWorldStore.loadPlayerPosition(worldId);
                List<LocalWorldStore.InventorySlot> savedInventory = localWorldStore.loadInventory(worldId);
                final List<LocalWorldStore.InventorySlot> initialInventory = savedInventory;
                final int initialSpawnY = spawnY;
                final boolean wasGenerated = generated;
                final List<NexoraAndroidProtocolCodec.ChunkData> loadedChunks = List.copyOf(chunks);
                runOnUiThread(() -> {
                    if (localPreview == null || previewGenerationExecutor == null
                            || previewGenerationExecutor.isShutdown()) return;
                    for (NexoraAndroidProtocolCodec.ChunkData chunk : loadedChunks) {
                        localPreview.setChunk(chunk);
                    }
                    if (initialInventory == null) {
                        java.util.Arrays.fill(localHotbarItems, "");
                        java.util.Arrays.fill(localHotbarCounts, 0);
                        localHotbarItems[0] = "minecraft:dirt";
                        localHotbarCounts[0] = 32;
                        selectedLocalHotbarSlot = 0;
                    } else {
                        for (int slot = 0; slot < 9; slot++) {
                            LocalWorldStore.InventorySlot saved = initialInventory.get(slot);
                            localHotbarItems[slot] = saved.itemId();
                            localHotbarCounts[slot] = saved.count();
                        }
                    }
                    refreshLocalHotbar();
                    persistLocalInventory(worldId);
                    if (savedPosition != null) {
                        localPlayerX = savedPosition.x();
                        localPlayerY = savedPosition.y();
                        localPlayerZ = savedPosition.z();
                        localPreview.setPlayerPosition(savedPosition.x(), savedPosition.y(), savedPosition.z());
                    } else {
                        localPlayerX = 0.5;
                        localPlayerY = initialSpawnY;
                        localPlayerZ = 0.5;
                        localPreview.setPlayerSpawn(0, initialSpawnY, 0);
                        saveLocalPlayerPosition(worldId);
                    }
                    state.setText((wasGenerated ? "МИР СОЗДАН" : "МИР ЗАГРУЖЕН")
                            + " • ЧАНКОВ: " + loadedChunks.size());
                });
            } catch (Exception failure) {
                runOnUiThread(() -> state.setText("ОШИБКА МИРА: "
                        + failure.getClass().getSimpleName()));
            }
        });
    }

    private void refreshLocalHotbar() {
        for (int slot = 0; slot < localHotbarViews.length; slot++) {
            TextView cell = localHotbarViews[slot];
            if (cell == null) continue;
            String itemId = localHotbarItems[slot];
            String label = itemId == null || itemId.isEmpty() ? "—" : itemId.substring(itemId.indexOf(':') + 1);
            if (label.length() > 5) label = label.substring(0, 5);
            cell.setText((slot + 1) + "\n" + label + (localHotbarCounts[slot] > 0
                    ? "\n×" + localHotbarCounts[slot] : ""));
            cell.setBackground(rounded(slot == selectedLocalHotbarSlot ? ACCENT : PANEL, 6));
        }
    }

    private void persistLocalInventory(String worldId) {
        ExecutorService executor = previewGenerationExecutor;
        if (executor == null || executor.isShutdown()) return;
        List<LocalWorldStore.InventorySlot> snapshot = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            String item = localHotbarItems[slot] == null ? "" : localHotbarItems[slot];
            snapshot.add(new LocalWorldStore.InventorySlot(item, localHotbarCounts[slot]));
        }
        executor.execute(() -> {
            try {
                localWorldStore.saveInventory(worldId, snapshot);
            } catch (java.io.IOException failure) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Не удалось сохранить инвентарь", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private boolean canAddLocalItem(String itemId, int amount) {
        if (itemId == null || itemId.isEmpty() || amount <= 0) return false;
        int capacity = 0;
        for (int slot = 0; slot < 9; slot++) {
            if (itemId.equals(localHotbarItems[slot])) capacity += Math.max(0, 64 - localHotbarCounts[slot]);
            else if (localHotbarCounts[slot] == 0) capacity += 64;
        }
        return capacity >= amount;
    }

    private boolean addLocalItem(String itemId, int amount) {
        if (!canAddLocalItem(itemId, amount)) return false;
        for (int slot = 0; slot < 9; slot++) {
            if (itemId.equals(localHotbarItems[slot]) && localHotbarCounts[slot] < 64) {
                int accepted = Math.min(amount, 64 - localHotbarCounts[slot]);
                localHotbarCounts[slot] += accepted;
                amount -= accepted;
                if (amount == 0) {
                    refreshLocalHotbar();
                    return true;
                }
            }
        }
        for (int slot = 0; slot < 9 && amount > 0; slot++) {
            if (localHotbarCounts[slot] == 0) {
                int accepted = Math.min(amount, 64);
                localHotbarItems[slot] = itemId;
                localHotbarCounts[slot] = accepted;
                amount -= accepted;
            }
        }
        refreshLocalHotbar();
        return amount == 0;
    }

    private void showLocalBlockActionDialog(String worldId, int x, int y, int z,
                                                int placeX, int placeY, int placeZ) {
        new AlertDialog.Builder(this)
                .setTitle("БЛОК В МИРЕ")
                .setMessage("X: " + x + "   Y: " + y + "   Z: " + z
                        + "\nВ руке: " + (localHotbarItems[selectedLocalHotbarSlot] == null
                        ? "пусто" : localHotbarItems[selectedLocalHotbarSlot]))
                .setItems(new String[]{"СЛОМАТЬ БЛОК", "ПОСТАВИТЬ ВЫБРАННЫЙ ПРЕДМЕТ"}, (dialog, which) -> {
                    ChunkPreviewView preview = localPreview;
                    if (preview == null) return;
                    String blockId;
                    String minedBlock = null;
                    int targetX;
                    int targetY;
                    int targetZ;
                    if (which == 0) {
                        targetX = x;
                        targetY = y;
                        targetZ = z;
                        blockId = "minecraft:air";
                        minedBlock = preview.localBlockIdAt(x, y, z);
                        if (minedBlock == null) {
                            Toast.makeText(this, "Здесь нет блока", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!canAddLocalItem(minedBlock, 1)) {
                            Toast.makeText(this, "Нет места в панели предметов", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } else {
                        targetX = placeX;
                        targetY = placeY;
                        targetZ = placeZ;
                        blockId = localHotbarItems[selectedLocalHotbarSlot];
                        if (blockId == null || blockId.isEmpty()
                                || localHotbarCounts[selectedLocalHotbarSlot] <= 0) {
                            Toast.makeText(this, "Выбери блок в панели предметов", Toast.LENGTH_SHORT).show();
                            return;
                        }
                     }
                    NexoraAndroidProtocolCodec.ChunkData updated =
                            preview.updateLocalBlock(targetX, targetY, targetZ, blockId);
                    if (updated == null) {
                        Toast.makeText(this, "Этот чанк ещё не загружен", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (which == 0) {
                        addLocalItem(minedBlock, 1);
                    } else {
                        localHotbarCounts[selectedLocalHotbarSlot]--;
                        if (localHotbarCounts[selectedLocalHotbarSlot] == 0) {
                            localHotbarItems[selectedLocalHotbarSlot] = "";
                        }
                    }
                    refreshLocalHotbar();
                    List<LocalWorldStore.InventorySlot> inventorySnapshot = new ArrayList<>(9);
                    for (int slot = 0; slot < 9; slot++) {
                        String item = localHotbarItems[slot] == null ? "" : localHotbarItems[slot];
                        inventorySnapshot.add(new LocalWorldStore.InventorySlot(
                                item, localHotbarCounts[slot]));
                    }
                    ExecutorService executor = previewGenerationExecutor;
                    if (executor == null || executor.isShutdown()) {
                        Toast.makeText(this, "Не удалось сохранить изменение", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    executor.execute(() -> {
                        try {
                            localWorldStore.saveChunk(worldId, updated);
                            localWorldStore.saveInventory(worldId, inventorySnapshot);
                            runOnUiThread(() -> Toast.makeText(this,
                                    which == 0 ? "Блок добыт и сохранён" : "Блок установлен и сохранён",
                                    Toast.LENGTH_SHORT).show());
                        } catch (java.io.IOException failure) {
                            runOnUiThread(() -> Toast.makeText(this,
                                    "Ошибка сохранения блока", Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton("ОТМЕНА", null)
                .show();
    }

    private void bindHoldMovement(Button button, Runnable movement) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        boolean[] held = {false};
        Runnable repeat = new Runnable() {
            @Override
            public void run() {
                if (!held[0]) return;
                movement.run();
                handler.postDelayed(this, 90L);
            }
        };
        button.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                held[0] = true;
                movement.run();
                handler.postDelayed(repeat, 90L);
                view.setPressed(true);
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL
                    || action == android.view.MotionEvent.ACTION_OUTSIDE) {
                held[0] = false;
                handler.removeCallbacks(repeat);
                view.setPressed(false);
                return true;
            }
            return true;
        });
    }

    private void saveLocalPlayerPosition(String worldId) {
        try {
            localWorldStore.savePlayerPosition(worldId,
                    new LocalWorldStore.PlayerPosition(localPlayerX, localPlayerY, localPlayerZ));
        } catch (java.io.IOException failure) {
            Toast.makeText(this, "Ошибка сохранения мира", Toast.LENGTH_SHORT).show();
        }
    }

    private View createWorldClientScreen() {
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
        chunkPreview.setBlockTargetListener((x, y, z, placeX, placeY, placeZ) ->
                runOnUiThread(() -> showTargetActionDialog(x, y, z, placeX, placeY, placeZ)));
        chunkPreview.setVisibility(View.GONE);
        page.addView(chunkPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        addSpace(page, 8);
        LinearLayout blockActions = new LinearLayout(this);
        blockActions.setOrientation(LinearLayout.HORIZONTAL);
        blockActions.setGravity(Gravity.CENTER);
        Button breakBlock = button("СЛОМАТЬ БЛОК");
        Button placeBlock = button("ПОСТАВИТЬ БЛОК");
        blockActions.addView(breakBlock, new LinearLayout.LayoutParams(0, dp(48), 1f));
        blockActions.addView(placeBlock, new LinearLayout.LayoutParams(0, dp(48), 1f));
        breakBlock.setOnClickListener(v -> showBlockActionDialog(false));
        placeBlock.setOnClickListener(v -> showBlockActionDialog(true));
        page.addView(blockActions, matchWrap());
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


    private void showTargetActionDialog(int x, int y, int z,
                                        int placeX, int placeY, int placeZ) {
        new AlertDialog.Builder(this)
                .setTitle("Блок выбран")
                .setMessage("Координаты: " + x + ", " + y + ", " + z
                        + "\nВыбери действие с блоком.")
                .setItems(new String[]{"СЛОМАТЬ БЛОК", "ПОСТАВИТЬ РЯДОМ"}, (dialog, which) -> {
                    boolean place = which == 1;
                    NetherNetAndroidClient client = netherNetClient;
                    int targetX = place ? placeX : x;
                    int targetY = place ? placeY : y;
                    int targetZ = place ? placeZ : z;
                    String blockId = place ? "minecraft:dirt" : "";
                    if (client == null || !client.sendBlockAction(place,
                            targetX, targetY, targetZ, blockId)) {
                        Toast.makeText(this, "Не удалось отправить действие серверу",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, place ? "Запрошена установка блока"
                                : "Запрошено разрушение блока", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("ОТМЕНА", null)
                .show();
    }

    private void showBlockActionDialog(boolean place) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), dp(8), dp(20), dp(4));
        EditText x = new EditText(this);
        EditText y = new EditText(this);
        EditText z = new EditText(this);
        x.setHint("X"); y.setHint("Y"); z.setHint("Z");
        x.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        y.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        z.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        fields.addView(x, matchWrap());
        fields.addView(y, matchWrap());
        fields.addView(z, matchWrap());
        EditText blockId = null;
        if (place) {
            blockId = new EditText(this);
            blockId.setSingleLine(true);
            blockId.setText("minecraft:dirt");
            blockId.setHint("ID блока");
            fields.addView(blockId, matchWrap());
        }
        EditText selectedBlockId = blockId;
        new AlertDialog.Builder(this)
                .setTitle(place ? "Поставить блок" : "Сломать блок")
                .setMessage("Введи координаты блока в мире. Сервер проверит дистанцию и инвентарь.")
                .setView(fields)
                .setNegativeButton("Отмена", null)
                .setPositiveButton(place ? "Поставить" : "Сломать", (dialog, which) -> {
                    try {
                        int blockX = Integer.parseInt(x.getText().toString().trim());
                        int blockY = Integer.parseInt(y.getText().toString().trim());
                        int blockZ = Integer.parseInt(z.getText().toString().trim());
                        String id = selectedBlockId == null ? "" : selectedBlockId.getText().toString().trim();
                        NetherNetAndroidClient client = netherNetClient;
                        if (client == null || !client.sendBlockAction(place, blockX, blockY, blockZ, id)) {
                            Toast.makeText(this, "Не удалось отправить действие блоку", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Действие отправлено серверу", Toast.LENGTH_SHORT).show();
                        }
                    } catch (NumberFormatException invalid) {
                        Toast.makeText(this, "Координаты должны быть целыми числами", Toast.LENGTH_SHORT).show();
                    }
                }).show();
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
            public void onResourcePackInstalled() {
                ChunkPreviewView preview = chunkPreview;
                if (preview != null) {
                    preview.reloadResourcePackTextures();
                    connectionStatus.setText("Ресурс-пак сервера установлен • текстуры обновлены");
                }
            }

            @Override
            public void onWorldInfoReceived(NexoraAndroidProtocolCodec.WorldInfo world) {
                streamingCenterChunkX = Math.floorDiv(world.spawnX(), 16);
                streamingCenterChunkZ = Math.floorDiv(world.spawnZ(), 16);
                chunkPreview.setCenter(streamingCenterChunkX, streamingCenterChunkZ);
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
                int chunkX = Math.floorDiv((int) Math.floor(position.x()), 16);
                int chunkZ = Math.floorDiv((int) Math.floor(position.z()), 16);
                if (chunkX != streamingCenterChunkX || chunkZ != streamingCenterChunkZ) {
                    streamingCenterChunkX = chunkX;
                    streamingCenterChunkZ = chunkZ;
                    chunkPreview.setCenter(chunkX, chunkZ);
                    NetherNetAndroidClient client = netherNetClient;
                    if (client != null) client.requestChunksAround(chunkX, chunkZ);
                }
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
            public void onBlockUpdate(NexoraAndroidProtocolCodec.BlockUpdate update) {
                chunkPreview.setBlockUpdate(update);
            }

            public void onChunkDataReceived(NexoraAndroidProtocolCodec.ChunkData chunk) {
                chunkPreview.setChunk(chunk);
                connectionStatus.setText("Получено чанков: "
                        + chunkPreview.receivedChunkCount() + " чанков в окне 3×3"
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
    private static final int REQUEST_RESOURCE_PACK = 4108;

    private void openResourcePackPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        startActivityForResult(intent, REQUEST_RESOURCE_PACK);
    }

    private void showResourcePackManager() {
        java.util.List<NexoraResourcePackLibrary.Pack> packs =
                NexoraResourcePackLibrary.list(this);
        String active = NexoraResourcePackLibrary.activeId(this);
        if (packs.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("РЕСУРС-ПАКИ")
                    .setMessage("Пока нет установленных пакетов. Импортируй ZIP с папкой textures/blocks.")
                    .setPositiveButton("ИМПОРТИРОВАТЬ", (dialog, which) -> openResourcePackPicker())
                    .setNegativeButton("ЗАКРЫТЬ", null)
                    .show();
            return;
        }
        String[] labels = new String[packs.size()];
        for (int i = 0; i < packs.size(); i++) {
            NexoraResourcePackLibrary.Pack pack = packs.get(i);
            labels[i] = (pack.id.equals(active) ? "✓ " : "") + pack.name;
        }
        new AlertDialog.Builder(this)
                .setTitle("РЕСУРС-ПАКИ • " + packs.size())
                .setItems(labels, (dialog, index) -> showResourcePackActions(packs.get(index)))
                .setPositiveButton("ИМПОРТИРОВАТЬ", (dialog, which) -> openResourcePackPicker())
                .setNegativeButton("ЗАКРЫТЬ", null)
                .show();
    }

    private void showResourcePackActions(NexoraResourcePackLibrary.Pack pack) {
        boolean isActive = pack.id.equals(NexoraResourcePackLibrary.activeId(this));
        String[] actions = isActive
                ? new String[]{"АКТИВЕН", "УДАЛИТЬ"}
                : new String[]{"СДЕЛАТЬ АКТИВНЫМ", "УДАЛИТЬ"};
        new AlertDialog.Builder(this)
                .setTitle(pack.name)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0 && !isActive) {
                        try {
                            NexoraResourcePackLibrary.activate(this, pack.id);
                            Toast.makeText(this, "Пакет активирован. Перезапусти мир для применения.",
                                    Toast.LENGTH_LONG).show();
                        } catch (Exception failure) {
                            Toast.makeText(this, "Не удалось включить пакет: " + failure.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    } else if (which == 1) {
                        new AlertDialog.Builder(this)
                                .setTitle("Удалить ресурс-пак?")
                                .setMessage("Пакет «" + pack.name + "» будет удалён из библиотеки.")
                                .setPositiveButton("УДАЛИТЬ", (confirm, selected) -> {
                                    try {
                                        NexoraResourcePackLibrary.delete(this, pack.id);
                                        Toast.makeText(this, "Ресурс-пак удалён", Toast.LENGTH_SHORT).show();
                                    } catch (Exception failure) {
                                        Toast.makeText(this, "Ошибка удаления: " + failure.getMessage(),
                                                Toast.LENGTH_LONG).show();
                                    }
                                    showResourcePackManager();
                                })
                                .setNegativeButton("ОТМЕНА", null)
                                .show();
                    }
                })
                .setNegativeButton("НАЗАД", (dialog, which) -> showResourcePackManager())
                .show();
    }

    private String resourcePackDisplayName(android.net.Uri uri) {
        if (uri == null) return "";
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Exception ignored) {
            // The library will use a generated name when the provider hides filenames.
        }
        return "";
    }

    private void openCustomSkinPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        startActivityForResult(intent, REQUEST_CUSTOM_SKIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_RESOURCE_PACK) {
            final android.net.Uri selectedPack = data.getData();
            final String displayName = resourcePackDisplayName(selectedPack);
            final android.content.Context appContext = getApplicationContext();
            Toast.makeText(this, "Импорт ресурс-пака…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    int imported = NexoraResourcePackInstaller.install(appContext, selectedPack);
                    NexoraResourcePackLibrary.registerImportedActive(appContext, displayName);
                    runOnUiThread(() -> Toast.makeText(this,
                            "Ресурс-пак добавлен в библиотеку. Текстур: " + imported
                                    + ". Он активен; открой мир заново для применения.",
                            Toast.LENGTH_LONG).show());
                } catch (Exception invalid) {
                    final String reason = invalid.getMessage() == null
                            ? "неизвестная ошибка" : invalid.getMessage();
                    runOnUiThread(() -> Toast.makeText(this,
                            "Не удалось установить ресурс-пак: " + reason,
                            Toast.LENGTH_LONG).show());
                }
            }, "NEXORA-ResourcePackImport").start();
            return;
        }
        if (requestCode == REQUEST_CUSTOM_SKIN) {
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
        if (chunkPreview != null) chunkPreview.onPause();
        if (localPreview != null) localPreview.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (chunkPreview != null) chunkPreview.onResume();
        if (localPreview != null) localPreview.onResume();
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
