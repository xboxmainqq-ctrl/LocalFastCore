package ru.nexora.network.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.network.nethernet.NetherNetDataChannelListener;
import ru.nexora.network.nethernet.NetherNetInboundPipeline;
import ru.nexora.network.nethernet.NetherNetMessageFramer;
import ru.nexora.inventory.PlayerInventory;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventoryStorage;
import ru.nexora.inventory.PlayerEquipment;
import ru.nexora.inventory.PlayerEquipmentStorage;
import ru.nexora.inventory.EquipmentSlot;
import ru.nexora.inventory.EquipmentSlotRegistry;
import ru.nexora.inventory.PlayerEquipmentService;
import ru.nexora.inventory.ArmorSlotRegistry;
import ru.nexora.inventory.EquipmentStats;
import ru.nexora.inventory.InventoryTransactionService;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.ContainerInventoryTransactionService;
import ru.nexora.inventory.CraftingRecipe;
import ru.nexora.inventory.CraftingRecipeRegistry;
import ru.nexora.inventory.CraftingService;
import ru.nexora.inventory.FurnaceService;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.BlockType;
import ru.nexora.world.World;
import ru.nexora.world.WorldContainerManager;
import ru.nexora.world.WorldFurnaceManager;
import ru.nexora.world.WorldItemEntity;
import ru.nexora.world.WorldItemEntityManager;
import ru.nexora.world.WorldLivingEntity;
import ru.nexora.world.WorldLivingEntityManager;
import ru.nexora.world.WorldChunk;
import ru.nexora.world.WorldCollision;
import ru.nexora.world.WorldRaycast;
import ru.nexora.world.WorldPathfinder;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerDataStorage;
import ru.nexora.world.PlayerExperience;
import ru.nexora.world.PlayerPhysics;
import ru.nexora.world.PlayerVitals;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Bedrock login stages above NetherNet:
 * RequestNetworkSettings -> NetworkSettings -> Login -> ClientToServerHandshake
 * -> PlayStatus -> ResourcePacksInfo -> ResourcePackStack.
 */
public final class BedrockSessionManager implements NetherNetDataChannelListener, AutoCloseable {
    public static final int LOGIN_FAILED_CLIENT_OLD = 1;
    public static final int LOGIN_FAILED_SERVER_OLD = 2;
    public static final int LOGIN_SUCCESS = 0;
    public static final int DEFAULT_COMPRESSION_THRESHOLD = 256;
    public static final int SCTP_MAX_MESSAGE_BYTES = 262_144;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int serverProtocol;
    private final String serverVersion;
    private final int compressionThreshold;
    private final World world;
    private final PlayerDataStorage playerStorage;
    private final PlayerInventoryStorage inventoryStorage;
    private final PlayerEquipmentStorage equipmentStorage;
    private volatile BedrockItemRegistry itemRegistry = BedrockItemRegistry.empty();
    private volatile ArmorSlotRegistry armorSlotRegistry = ArmorSlotRegistry.empty();
    private volatile EquipmentSlotRegistry equipmentSlotRegistry = EquipmentSlotRegistry.empty();
    private final InventoryTransactionService inventoryTransactionService = new InventoryTransactionService();
    private final ru.nexora.inventory.AnvilTransactionService anvilTransactionService =
            new ru.nexora.inventory.AnvilTransactionService();
    private final ContainerInventoryTransactionService containerTransactionService =
            new ContainerInventoryTransactionService();
    private final CraftingRecipeRegistry craftingRecipes = CraftingRecipeRegistry.vanillaSubset();
    private final CraftingService craftingService = new CraftingService();
    private final PlayerEquipmentService playerEquipmentService = new PlayerEquipmentService();
    private final OutboundFrameSender outbound;
    private final Consumer<PacketEvent> packetSink;
    private final LongSupplier nanoTime;
    private final AutoCloseable furnaceUpdateListener;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final AtomicLong nextEntityId = new AtomicLong(1);
    private final AtomicLong nextFormId = new AtomicLong(1);
    private final AtomicLong lastItemActorTickNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastLivingAiTickMillis = new AtomicLong(Long.MIN_VALUE);
    private final Map<Long, Long> nextMobAttackMillis = new ConcurrentHashMap<>();
    private final Map<Long, MobKillCredit> livingEntityKillCredit = new ConcurrentHashMap<>();
    private final Map<Long, String> livingEntityAggroTargets = new ConcurrentHashMap<>();
    private final Map<Long, ru.nexora.combat.ArrowProjectile> mobArrows = new ConcurrentHashMap<>();
    private final AtomicLong nextMobArrowRuntimeId = new AtomicLong(4_000_000L);
    private final Map<Long, Long> creeperFuseStartedMillis = new ConcurrentHashMap<>();
    private final Map<Long, Long> endermanTeleportCooldownMillis = new ConcurrentHashMap<>();
    private final ru.nexora.combat.LivingEntityAiService livingEntityAi =
            new ru.nexora.combat.LivingEntityAiService();
    private final ru.nexora.world.MobPathCache mobPathCache =
            new ru.nexora.world.MobPathCache();
    private final ru.nexora.combat.MobStuckTracker mobStuckTracker =
            new ru.nexora.combat.MobStuckTracker();
    private final ru.nexora.combat.MobAiLoadController mobAiLoadController =
            new ru.nexora.combat.MobAiLoadController();
    private volatile boolean serverAuthoritativeMovement;
    private volatile ru.nexora.ai.NexoraAiAssistant aiAssistant;
    private volatile ru.nexora.ai.NexoraAiDiagnostics aiDiagnostics;
    private final ru.nexora.ai.NexoraAiGuard aiGuard = new ru.nexora.ai.NexoraAiGuard(
            ru.nexora.ai.NexoraAiGuard.DEFAULT_WINDOW_NANOS,
            ru.nexora.ai.NexoraAiGuard.DEFAULT_MAX_FRAMES_PER_WINDOW,
            ru.nexora.ai.NexoraAiGuard.DEFAULT_MAX_BYTES_PER_WINDOW,
            ru.nexora.ai.NexoraAiGuard.DEFAULT_MAX_TRACKED_PEERS,
            event -> {
                ru.nexora.ai.NexoraAiDiagnostics diagnostics = aiDiagnostics;
                if (diagnostics != null) {
                    diagnostics.recordGuardSignal(event.reason() + ": "
                            + event.framesInWindow() + " frames, "
                            + event.bytesInWindow() + " bytes in current window");
                }
            });
    private final NetherNetInboundPipeline inbound;

    public BedrockSessionManager(
            int serverProtocol,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, "1.26.62", DEFAULT_COMPRESSION_THRESHOLD, outbound, packetSink);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, DEFAULT_COMPRESSION_THRESHOLD, outbound, packetSink);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold,
                new World("NEXORA World", 0L, new FlatWorldGenerator()), outbound, packetSink);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, null,
                outbound, packetSink, System::nanoTime);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, null,
                outbound, packetSink, System::nanoTime);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            PlayerInventoryStorage inventoryStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, inventoryStorage,
                null, outbound, packetSink, System::nanoTime);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            PlayerInventoryStorage inventoryStorage,
            PlayerEquipmentStorage equipmentStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, inventoryStorage,
                equipmentStorage, outbound, packetSink, System::nanoTime);
    }

    BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink,
            LongSupplier nanoTime
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, null,
                outbound, packetSink, nanoTime);
    }

    BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink,
            LongSupplier nanoTime
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, null,
                outbound, packetSink, nanoTime);
    }

    BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            PlayerInventoryStorage inventoryStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink,
            LongSupplier nanoTime
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, inventoryStorage,
                null, outbound, packetSink, nanoTime);
    }

    BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            PlayerInventoryStorage inventoryStorage,
            PlayerEquipmentStorage equipmentStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink,
            LongSupplier nanoTime
    ) {
        if (serverProtocol < 1) throw new IllegalArgumentException("serverProtocol must be positive");
        if (serverVersion == null || serverVersion.isBlank()) {
            throw new IllegalArgumentException("serverVersion must not be blank");
        }
        if (compressionThreshold < 1 || compressionThreshold > 0xffff) {
            throw new IllegalArgumentException("compressionThreshold must be 1..65535");
        }
        this.serverProtocol = serverProtocol;
        this.serverVersion = serverVersion;
        this.compressionThreshold = compressionThreshold;
        this.world = Objects.requireNonNull(world, "world");
        this.playerStorage = playerStorage;
        this.inventoryStorage = inventoryStorage;
        this.equipmentStorage = equipmentStorage;
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.packetSink = Objects.requireNonNull(packetSink, "packetSink");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.inbound = new NetherNetInboundPipeline(this::handleCompleteMessage, aiGuard);
        this.furnaceUpdateListener = this.world.furnaces()
                .map(manager -> manager.addUpdateListener(this::handleFurnaceUpdate))
                .orElse(null);
    }

    @Override
    public void onIdentity(String networkId, NetherNetClientIdentity identity) {
        SessionState state = sessions.computeIfAbsent(networkId, ignored -> new SessionState());
        synchronized (state) {
            if (state.identity != null && !state.identity.xuid().equals(identity.xuid())) {
                throw new SecurityException("NetherNet identity changed during an active session");
            }
            state.identity = Objects.requireNonNull(identity, "identity");
        }
    }

    @Override
    public void onFrame(String networkId, String channelLabel, byte[] frame) {
        inbound.onFrame(networkId, channelLabel, frame);
    }

    @Override
    public void onDisconnected(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state != null) {
            if (state.identity != null) aiGuard.onPlayerDisconnected(state.identity.xuid());
            state.combatLock.lock();
            try {
                synchronized (state) {
                    saveAndClearContainerWindow(state);
                    try {
                        returnCraftingGridItems(state);
                        savePlayer(state);
                        saveInventory(state);
                        saveEquipment(state);
                    } catch (IOException | RuntimeException exception) {
                        System.err.println("NEXORA: unable to save disconnected player: " + exception.getMessage());
                    }
                }
            } finally {
                state.combatLock.unlock();
            }
            sessions.remove(networkId, state);
        }
        inbound.onDisconnected(networkId);
    }

    public void savePlayers() throws IOException {
        IOException failure = null;
        for (SessionState state : sessions.values()) {
            synchronized (state) {
                try {
                    savePlayer(state);
                    saveInventory(state);
                    saveEquipment(state);
                } catch (IOException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) throw failure;
    }

    private void savePlayer(SessionState state) throws IOException {
        if (playerStorage == null || !state.loginAuthenticated || !state.positionInitialized
                || state.identity == null) return;
        String displayName = state.identity.displayName() == null ? "unknown" : state.identity.displayName();
        try {
            playerStorage.save(new PlayerData(
                    state.identity.xuid(), displayName, world.name(),
                    state.positionX, state.positionY, state.positionZ,
                    state.yaw, state.pitch, System.currentTimeMillis(), state.vitals,
                    state.experiencePoints, state.experienceRemainderMilli));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Authenticated player data cannot be persisted", exception);
        }
    }

    private void returnCraftingGridItems(SessionState state) throws IOException {
        List<Optional<ru.nexora.inventory.ItemStack>> playerBefore = state.inventory.snapshot();
        int selectedSlot = state.inventory.selectedHotbarSlot();
        List<Optional<ru.nexora.inventory.ItemStack>> uiBefore = state.craftingUiInventory.snapshot();
        List<Long> spawnedRuntimeIds = new ArrayList<>();
        try {
            for (int slot = 28; slot <= 31; slot++) {
                ru.nexora.inventory.ItemStack stack =
                        state.craftingUiInventory.getSlot(slot).orElse(null);
                if (stack == null) continue;
                int remaining = state.inventory.add(stack);
                if (remaining > 0) {
                    var dropped = world.itemEntities().spawn(
                            stack.withCount(remaining),
                            state.positionX, state.positionY + 0.5, state.positionZ,
                            0, 0.15, 0, System.currentTimeMillis(),
                            WorldItemEntityManager.DEFAULT_PICKUP_DELAY_MILLIS,
                            state.identity == null ? null : state.identity.xuid());
                    spawnedRuntimeIds.add(dropped.runtimeId());
                }
                state.craftingUiInventory.setSlot(slot, null);
            }
            saveInventory(state);
        } catch (IOException | RuntimeException exception) {
            state.inventory.restore(playerBefore, selectedSlot);
            state.craftingUiInventory.restore(uiBefore);
            for (long runtimeId : spawnedRuntimeIds) {
                try {
                    world.itemEntities().remove(runtimeId);
                } catch (RuntimeException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            throw exception;
        }
    }

    private void saveInventory(SessionState state) throws IOException {
        if (inventoryStorage == null || !state.loginAuthenticated || state.identity == null) return;
        inventoryStorage.save(state.identity.xuid(), state.inventory, state.craftingUiInventory);
    }

    private void saveEquipment(SessionState state) throws IOException {
        if (equipmentStorage == null || !state.loginAuthenticated || state.identity == null) return;
        equipmentStorage.save(state.identity.xuid(), state.equipment);
    }

    /** Enables server-side movement simulation before any Bedrock session is created. */
    public synchronized void enableServerAuthoritativeMovement() {
        if (!sessions.isEmpty()) {
            throw new IllegalStateException("Movement authority must be configured before sessions are created");
        }
        this.serverAuthoritativeMovement = true;
    }

    /** Aggregate-only network anomaly status for trusted operator diagnostics. */
    public String guardSummary() {
        return aiGuard.snapshot().summary();
    }

    /** Read-only moderator review summary; never exposes XUIDs or executes punishments. */
    public String guardReviewSummary() {
        return aiGuard.combatReviewSummary(nanoTime.getAsLong());
    }

    /** Read-only mob navigation/cache diagnostics for allowlisted operators. */
    public String mobAiDiagnosticsSummary() {
        ru.nexora.world.MobPathCache.Stats cache = mobPathCache.stats();
        ru.nexora.combat.MobStuckTracker.Stats stuck = mobStuckTracker.stats(Math.max(0L, nanoTime.getAsLong() / 1_000_000L));
        StringBuilder result = new StringBuilder("Mob path cache: ")
                .append("hits=").append(cache.hits())
                .append(", misses=").append(cache.misses())
                .append(", hitRate=").append(Math.round(cache.hitRate() * 1000.0) / 10.0).append('%')
                .append(", recomputations=").append(cache.recomputations())
                .append(", blocked=").append(cache.blockedWaypoints())
                .append(", evictions=").append(cache.evictions())
                .append(", entries=").append(cache.entries())
                .append("\nMob stuck tracker: tracked=").append(stuck.tracked())
                .append(", stuck=").append(stuck.stuck())
                .append(", evictions=").append(stuck.evictions())
                .append("\nMob AI load controller: ").append(mobAiLoadController.stats().summary());
        ru.nexora.ai.NexoraAiDiagnostics diagnostics = aiDiagnostics;
        if (diagnostics == null) {
            result.append("\nMob AI latency: diagnostics unavailable");
        } else {
            boolean found = false;
            for (ru.nexora.ai.NexoraAiDiagnostics.LatencySummary latency : diagnostics.latencySummaries()) {
                if (!latency.subsystem().equals("mob-ai")
                        && !latency.subsystem().equals("mob-pathfinding")) continue;
                found = true;
                result.append("\n").append(latency.subsystem())
                        .append(": samples=").append(latency.sampleCount())
                        .append(", avg=").append(latency.averageNanos() / 1_000_000L).append(" ms")
                        .append(", p95=").append(latency.p95Nanos() / 1_000_000L).append(" ms")
                        .append(", max=").append(latency.maxNanos() / 1_000_000L).append(" ms");
            }
            if (!found) result.append("\nMob AI latency: waiting for measurements");
        }
        result.append("\nMob spawners: ").append(world.mobSpawners().stats().summary());
        result.append("\nЭто накопительные счётчики и время подсистем, не TPS и не CPU.");
        return result.toString();
    }

    /** Lists a small, bounded set of mobs that have made no meaningful movement progress. */
    public String mobAiStuckSummary() {
        long now = Math.max(0L, nanoTime.getAsLong() / 1_000_000L);
        ru.nexora.combat.MobStuckTracker.Stats stats = mobStuckTracker.stats(now);
        StringBuilder result = new StringBuilder("Застрявшие мобы: ")
                .append(stats.stuck()).append(" (отслеживается ").append(stats.tracked()).append(')');
        List<ru.nexora.combat.MobStuckTracker.StuckMob> stuck =
                mobStuckTracker.stuck(now, 5);
        if (stuck.isEmpty()) {
            result.append("\nПодтверждённых случаев пока нет.");
        } else {
            for (ru.nexora.combat.MobStuckTracker.StuckMob mob : stuck) {
                result.append("\n#").append(mob.runtimeId())
                        .append(": ").append(mob.durationMillis() / 1000.0).append(" сек., попыток=")
                        .append(mob.attempts());
            }
        }
        result.append("\nОбнаружение только сообщает о проблеме; мобов система не телепортирует.");
        return result.toString();
    }

    private void handleMobSpawnerCommand(String networkId, String arguments) {
        String[] parts = arguments.isBlank() ? new String[0] : arguments.split("\\s+");
        if (parts.length == 0 || parts[0].equalsIgnoreCase("list")) {
            List<ru.nexora.world.MobSpawnerState> spawners = world.mobSpawners().snapshot();
            if (spawners.isEmpty()) {
                sendAiMessage(networkId, "§b[NEXORA SPAWNER] §fСпавнеров пока нет. "
                        + "Создать: /ai spawner place <x> <y> <z> [mob] [delay]");
                return;
            }
            StringBuilder result = new StringBuilder("§b[NEXORA SPAWNER] §fСпавнеры: ")
                    .append(spawners.size());
            for (ru.nexora.world.MobSpawnerState spawner : spawners.stream().limit(10).toList()) {
                result.append("\\n").append(spawner.entityIdentifier()).append(" @ ")
                        .append(spawner.x()).append(' ').append(spawner.y()).append(' ')
                        .append(spawner.z()).append(" delay=").append(spawner.delayTicks());
            }
            sendAiMessage(networkId, result.toString());
            return;
        }
        String action = parts[0].toLowerCase(Locale.ROOT);
        if (action.equals("help")) {
            sendAiMessage(networkId, "§b[NEXORA SPAWNER] §fКоманды: /ai spawner list; "
                    + "/ai spawner place <x> <y> <z> [mob] [delay]; "
                    + "/ai spawner set <x> <y> <z> <mob> <delay>; "
                    + "/ai spawner remove <x> <y> <z>. Только для операторов.");
            return;
        }
        try {
            if (action.equals("place") && (parts.length == 4 || parts.length == 5 || parts.length == 6)) {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                String identifier = parts.length >= 5 ? normalizeSpawnerIdentifier(parts[4]) : "minecraft:zombie";
                int delay = parts.length == 6 ? Integer.parseInt(parts[5])
                        : ru.nexora.world.MobSpawnerState.DEFAULT_DELAY_TICKS;
                world.setBlock(x, y, z, ru.nexora.world.BlockType.SPAWNER);
                if (!world.mobSpawners().configure(x, y, z, identifier, delay,
                        Math.max(0L, nanoTime.getAsLong() / 50_000_000L))) {
                    world.setBlock(x, y, z, ru.nexora.world.BlockType.AIR);
                    sendAiMessage(networkId, "§c[NEXORA SPAWNER] Не удалось настроить спавнер.");
                    return;
                }
                broadcastBlockUpdate(x, y, z, ru.nexora.world.BlockType.SPAWNER);
                sendAiMessage(networkId, "§a[NEXORA SPAWNER] Спавнер установлен: " + identifier
                        + " (" + x + " " + y + " " + z + "), задержка " + delay + " тиков.");
                return;
            }
            if (action.equals("set") && parts.length == 6) {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                String identifier = normalizeSpawnerIdentifier(parts[4]);
                int delay = Integer.parseInt(parts[5]);
                if (!world.mobSpawners().configure(x, y, z, identifier, delay,
                        Math.max(0L, nanoTime.getAsLong() / 50_000_000L))) {
                    sendAiMessage(networkId, "§c[NEXORA SPAWNER] Спавнер не найден или параметры неверны.");
                    return;
                }
                sendAiMessage(networkId, "§a[NEXORA SPAWNER] Настройки обновлены.");
                return;
            }
            if (action.equals("remove") && parts.length == 4) {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                if (world.getBlock(x, y, z) != ru.nexora.world.BlockType.SPAWNER) {
                    sendAiMessage(networkId, "§c[NEXORA SPAWNER] В этой точке нет спавнера.");
                    return;
                }
                world.setBlock(x, y, z, ru.nexora.world.BlockType.AIR);
                broadcastBlockUpdate(x, y, z, ru.nexora.world.BlockType.AIR);
                sendAiMessage(networkId, "§a[NEXORA SPAWNER] Спавнер удалён.");
                return;
            }
        } catch (IllegalArgumentException exception) {
            sendAiMessage(networkId, "§c[NEXORA SPAWNER] Неверные координаты, тип моба или задержка.");
            return;
        }
        sendAiMessage(networkId, "§b[NEXORA SPAWNER] §fИспользуй /ai spawner help.");
    }

    private static String normalizeSpawnerIdentifier(String value) {
        String identifier = value.contains(":") ? value.toLowerCase(Locale.ROOT)
                : "minecraft:" + value.toLowerCase(Locale.ROOT);
        if (!java.util.Set.of(
                "minecraft:allay", "minecraft:armadillo", "minecraft:axolotl", "minecraft:bat",
                "minecraft:bee", "minecraft:blaze", "minecraft:bogged", "minecraft:camel",
                "minecraft:cat", "minecraft:cave_spider", "minecraft:chicken", "minecraft:cod",
                "minecraft:cow", "minecraft:creeper", "minecraft:dolphin", "minecraft:donkey",
                "minecraft:drowned", "minecraft:elder_guardian", "minecraft:enderman",
                "minecraft:endermite", "minecraft:evoker", "minecraft:fox", "minecraft:frog",
                "minecraft:ghast", "minecraft:glow_squid", "minecraft:goat", "minecraft:hoglin",
                "minecraft:horse", "minecraft:husk", "minecraft:iron_golem", "minecraft:llama",
                "minecraft:magma_cube", "minecraft:mooshroom", "minecraft:mule", "minecraft:ocelot",
                "minecraft:panda", "minecraft:parrot", "minecraft:phantom", "minecraft:pig",
                "minecraft:piglin", "minecraft:pillager", "minecraft:polar_bear",
                "minecraft:pufferfish", "minecraft:rabbit", "minecraft:ravager", "minecraft:salmon",
                "minecraft:sheep", "minecraft:shulker", "minecraft:silverfish", "minecraft:skeleton",
                "minecraft:slime", "minecraft:sniffer", "minecraft:snow_golem", "minecraft:spider",
                "minecraft:squid", "minecraft:stray", "minecraft:strider", "minecraft:tadpole",
                "minecraft:trader_llama", "minecraft:tropical_fish", "minecraft:turtle",
                "minecraft:vex", "minecraft:villager", "minecraft:vindicator",
                "minecraft:wandering_trader", "minecraft:warden", "minecraft:witch",
                "minecraft:wither", "minecraft:wither_skeleton", "minecraft:wolf",
                "minecraft:zoglin", "minecraft:zombie", "minecraft:zombie_villager",
                "minecraft:zombified_piglin").contains(identifier)) {
            throw new IllegalArgumentException("Unsupported spawner mob");
        }
        return identifier;
    }

    private void handleAiGuardCommand(String networkId, SessionState state, String arguments) {
        String[] parts = arguments.isBlank() ? new String[0] : arguments.split("\\s+", 2);
        if (parts.length == 0 || parts[0].equalsIgnoreCase("status")) {
            sendAiMessage(networkId, "§b[NEXORA AI GUARD] §f" + guardSummary()
                    + "\n" + guardReviewSummary()
                    + "\nДела: /ai guard cases; карточка: /ai guard case <ID>.");
            return;
        }
        String action = parts[0].toLowerCase(Locale.ROOT);
        if (action.equals("help")) {
            sendAiMessage(networkId, "§b[NEXORA AI GUARD] §fКоманды: /ai guard, /ai guard cases, "
                    + "/ai guard case <ID>, /ai guard confirm <ID>, /ai guard dismiss <ID>. "
                    + "Подтверждение фиксирует решение модератора, но не применяет наказание.");
            return;
        }
        if (action.equals("cases")) {
            java.util.List<ru.nexora.ai.NexoraAiGuard.CombatCaseView> cases =
                    aiGuard.combatCases(nanoTime.getAsLong());
            if (cases.isEmpty()) {
                sendAiMessage(networkId, "§b[NEXORA AI GUARD] §fОткрытых или сохранённых KillAura-дел нет.");
                return;
            }
            StringBuilder result = new StringBuilder("§b[NEXORA AI GUARD] §fПоследние дела:");
            for (ru.nexora.ai.NexoraAiGuard.CombatCaseView value : cases.stream().limit(8).toList()) {
                result.append("\n").append(value.id()).append(" — ").append(value.displayName())
                        .append(": ").append(value.hits()).append(" hits / 5s, ")
                        .append(value.targetSwitches()).append(" переключений [")
                        .append(value.status()).append("]");
            }
            result.append("\nОткрыть: /ai guard case <ID>");
            sendAiMessage(networkId, result.toString());
            return;
        }
        if (parts.length != 2 || parts[1].isBlank()) {
            sendAiMessage(networkId, "§b[NEXORA AI GUARD] §fУкажи ID дела. Команда: /ai guard help.");
            return;
        }
        String caseId = parts[1].trim();
        if (action.equals("case")) {
            ru.nexora.ai.NexoraAiGuard.CombatCaseView value =
                    aiGuard.findCombatCase(caseId, nanoTime.getAsLong()).orElse(null);
            if (value == null) {
                sendAiMessage(networkId, "§b[NEXORA AI GUARD] §fДело не найдено или срок хранения истёк.");
            } else {
                sendAiMessage(networkId, "§b[NEXORA AI GUARD] §fДело " + value.id()
                        + "\nИгрок: " + value.displayName()
                        + "\nСигнал: " + value.hits() + " успешных попаданий / 5 секунд; "
                        + value.targetSwitches() + " переключений цели."
                        + "\nСтатус: " + value.status()
                        + "\nРешение модератора: " + (value.reviewed() ? "уже зафиксировано" : "не принято")
                        + "\nПодтверждение не запускает бан/кик и не меняет состояние игрока.");
            }
            return;
        }
        if (action.equals("confirm") || action.equals("dismiss")) {
            String reviewerXuid = state.identity == null ? null : state.identity.xuid();
            ru.nexora.ai.NexoraAiGuard.CombatCaseView value = aiGuard.reviewCombatCase(
                    caseId, reviewerXuid, action.equals("confirm"), nanoTime.getAsLong()).orElse(null);
            if (value == null) {
                sendAiMessage(networkId, "§b[NEXORA AI GUARD] §fНе удалось изменить дело: проверь ID и убедись, что оно ещё OPEN.");
            } else {
                sendAiMessage(networkId, "§b[NEXORA AI GUARD] §fДело " + value.id()
                        + (action.equals("confirm") ? " подтверждено модератором." : " помечено как ложное срабатывание.")
                        + " Наказание автоматически не применялось.");
            }
            return;
        }
        sendAiMessage(networkId, "§b[NEXORA AI GUARD] §fНеизвестная команда. Используй /ai guard help.");
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    public long authenticatedPlayerCount() {
        return sessions.values().stream().filter(state -> state.loginAuthenticated).count();
    }

    public long resourcePackReadyCount() {
        return sessions.values().stream().filter(state -> state.resourcePacksCompleted).count();
    }

    /** Read-only aggregate world/session counters for the authenticated operator assistant. */
    public String adminRuntimeSummary() {
        return "players=" + authenticatedPlayerCount()
                + ", sessions=" + activeSessionCount()
                + ", mobs=" + world.livingEntities().snapshot().size()
                + ", droppedItems=" + world.itemEntities().snapshot().size();
    }

    /**
     * Server-authoritative anvil entry point. The screen/controller can call this after
     * validating its two input slots; failed combinations never mutate inventory or XP.
     */
    public Optional<ru.nexora.inventory.AnvilTransactionService.Result> combineAnvilItems(
            String networkId, int baseSlot, int sacrificeSlot
    ) throws IOException {
        SessionState state = sessions.get(networkId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            if (!state.loginAuthenticated || !state.clientInitialized || state.dead) return Optional.empty();

            List<Optional<ru.nexora.inventory.ItemStack>> previousInventory = state.inventory.snapshot();
            int previousSelectedSlot = state.inventory.selectedHotbarSlot();
            int previousExperiencePoints = state.experiencePoints;
            int previousExperienceRemainderMilli = state.experienceRemainderMilli;
            long totalExperienceMilli = (long) previousExperiencePoints * 1000L
                    + previousExperienceRemainderMilli;

            ru.nexora.inventory.AnvilTransactionService.Result result =
                    anvilTransactionService.combine(state.inventory, baseSlot, sacrificeSlot, totalExperienceMilli);
            if (!result.successful()) return Optional.of(result);

            state.experiencePoints = (int) (result.remainingExperienceMilli() / 1000L);
            state.experienceRemainderMilli = (int) (result.remainingExperienceMilli() % 1000L);
            try {
                saveInventory(state);
                savePlayer(state);
            } catch (IOException | RuntimeException exception) {
                state.inventory.restore(previousInventory, previousSelectedSlot);
                state.experiencePoints = previousExperiencePoints;
                state.experienceRemainderMilli = previousExperienceRemainderMilli;
                try {
                    saveInventory(state);
                    savePlayer(state);
                } catch (IOException | RuntimeException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                if (exception instanceof IOException ioException) throw ioException;
                throw exception;
            }

            sendInventoryAndEquipmentState(networkId, state);
            sendPacket(networkId, createPlayerAttributes(state, Math.max(0, state.lastInputTick)), true);
            return Optional.of(result);
        }
    }

    /** Returns the authoritative inventory for an authenticated player session. */
    public Optional<PlayerInventory> playerInventory(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            if (!state.loginAuthenticated) return Optional.empty();
            return Optional.of(state.inventory);
        }
    }

    /** Returns authoritative armor, offhand and cursor state for an authenticated player. */
    public Optional<PlayerEquipment> playerEquipment(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            if (!state.loginAuthenticated) return Optional.empty();
            return Optional.of(state.equipment);
        }
    }

    /** Returns the server runtime entity ID for an authenticated player. */
    public Optional<Long> playerRuntimeId(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            if (!state.loginAuthenticated) return Optional.empty();
            return Optional.of(state.entityId);
        }
    }

    /** Returns authoritative health and hunger state for an authenticated player. */
    public Optional<PlayerVitals> playerVitals(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            if (!state.loginAuthenticated) return Optional.empty();
            return Optional.of(state.vitals);
        }
    }

    /** Returns derived magical equipment attributes for an authenticated player. */
    public Optional<EquipmentStats> playerEquipmentStats(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            if (!state.loginAuthenticated) return Optional.empty();
            return Optional.of(EquipmentStats.calculate(state.equipment, equipmentSlotRegistry));
        }
    }

    /**
     * Equips one registered magical item from the player's inventory.
     * Successful mutations are persisted before authoritative inventory state is sent.
     */
    public PlayerEquipmentService.Result equipMagicItem(
            String networkId, EquipmentSlot slot, int inventorySlot
    ) throws IOException {
        SessionState state = sessions.get(networkId);
        if (state == null) return PlayerEquipmentService.Result.EMPTY_INVENTORY_SLOT;
        synchronized (state) {
            if (!state.loginAuthenticated || !state.clientInitialized) {
                return PlayerEquipmentService.Result.EMPTY_INVENTORY_SLOT;
            }
            List<Optional<ru.nexora.inventory.ItemStack>> previousInventory = state.inventory.snapshot();
            int previousSelectedSlot = state.inventory.selectedHotbarSlot();
            PlayerEquipment.Snapshot previousEquipment = state.equipment.snapshot();
            PlayerEquipmentService.Result result = playerEquipmentService.equipFromInventory(
                    state.inventory, state.equipment, slot, inventorySlot, equipmentSlotRegistry);
            if (result != PlayerEquipmentService.Result.EQUIPPED) return result;
            try {
                saveInventory(state);
                saveEquipment(state);
            } catch (IOException | RuntimeException exception) {
                state.inventory.restore(previousInventory, previousSelectedSlot);
                state.equipment.restore(previousEquipment);
                try {
                    saveInventory(state);
                    saveEquipment(state);
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                if (exception instanceof IOException ioException) throw ioException;
                throw exception;
            }
            sendInventoryAndEquipmentState(networkId, state);
            return result;
        }
    }

    /** Unequips a magical item back into the player's inventory without losing it if full. */
    public PlayerEquipmentService.Result unequipMagicItem(String networkId, EquipmentSlot slot)
            throws IOException {
        SessionState state = sessions.get(networkId);
        if (state == null) return PlayerEquipmentService.Result.EMPTY_EQUIPMENT_SLOT;
        synchronized (state) {
            if (!state.loginAuthenticated || !state.clientInitialized) {
                return PlayerEquipmentService.Result.EMPTY_EQUIPMENT_SLOT;
            }
            List<Optional<ru.nexora.inventory.ItemStack>> previousInventory = state.inventory.snapshot();
            int previousSelectedSlot = state.inventory.selectedHotbarSlot();
            PlayerEquipment.Snapshot previousEquipment = state.equipment.snapshot();
            PlayerEquipmentService.Result result = playerEquipmentService.unequipToInventory(
                    state.inventory, state.equipment, slot);
            if (result != PlayerEquipmentService.Result.UNEQUIPPED) return result;
            try {
                saveInventory(state);
                saveEquipment(state);
            } catch (IOException | RuntimeException exception) {
                state.inventory.restore(previousInventory, previousSelectedSlot);
                state.equipment.restore(previousEquipment);
                try {
                    saveInventory(state);
                    saveEquipment(state);
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                if (exception instanceof IOException ioException) throw ioException;
                throw exception;
            }
            sendInventoryAndEquipmentState(networkId, state);
            return result;
        }
    }

    private void sendInventoryAndEquipmentState(String networkId, SessionState state) {
        List<BedrockPacket> packets = new java.util.ArrayList<>();
        packets.add(BedrockInventoryContentCodec.createPlayerInventory(state.inventory, itemRegistry));
        packets.addAll(createEquipmentReconciliationPackets(state));
        packets.add(BedrockInventoryContentCodec.createPlayerUiInventory(state.craftingUiInventory, itemRegistry));
        sendPackets(networkId, packets, true);
    }

    /**
     * Installs the protocol item registry before clients connect. The registry is
     * immutable so it cannot change underneath an active inventory transaction.
     */
    public synchronized void setItemRegistry(BedrockItemRegistry registry) {
        if (!sessions.isEmpty()) {
            throw new IllegalStateException("The Bedrock item registry must be installed before sessions are created");
        }
        this.itemRegistry = Objects.requireNonNull(registry, "registry");
    }

    /** Installs server-owned armor compatibility rules before clients connect. */
    public synchronized void setArmorSlotRegistry(ArmorSlotRegistry registry) {
        if (!sessions.isEmpty()) {
            throw new IllegalStateException("The armor slot registry must be installed before sessions are created");
        }
        this.armorSlotRegistry = Objects.requireNonNull(registry, "registry");
    }

    /** Installs server-owned magical item compatibility and attribute definitions before clients connect. */
    public synchronized void setEquipmentSlotRegistry(EquipmentSlotRegistry registry) {
        if (!sessions.isEmpty()) {
            throw new IllegalStateException("The magical equipment registry must be installed before sessions are created");
        }
        this.equipmentSlotRegistry = Objects.requireNonNull(registry, "registry");
    }

    public Optional<PlayerPosition> playerPosition(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            if (!state.clientInitialized) return Optional.empty();
            return Optional.of(new PlayerPosition(state.positionX, state.positionY, state.positionZ,
                    state.yaw, state.pitch, state.lastInputTick));
        }
    }

    private void handleCompleteMessage(NetherNetInboundPipeline.InboundMessage message) {
        long processingStartedNanos = nanoTime.getAsLong();
        SessionState state = sessions.computeIfAbsent(message.networkId(), ignored -> new SessionState());
        state.combatLock.lock();
        try {
          synchronized (state) {
            if (state.rejected) return;

            if (!state.compressionNegotiated) {
                handleNetworkSettingsRequest(message, state);
                return;
            }

            List<BedrockPacket> packets = BedrockBatchCodec.decode(message.payload(), true);
            boolean resourcePacksInfoWasSent = state.resourcePacksInfoSent;
            boolean resourcePackStackWasSent = state.resourcePackStackSent;
            for (BedrockPacket packet : packets) {
                switch (packet.packetId()) {
                    case BedrockLoginPacketParser.LOGIN_PACKET_ID -> handleLogin(message, state, packet);
                    case BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID ->
                            handleClientHandshake(message.networkId(), state, packet);
                    case BedrockBatchCodec.RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID ->
                            handleResourcePackResponse(message.networkId(), state, packet,
                                    resourcePacksInfoWasSent, resourcePackStackWasSent);
                    case 0x71 -> handleClientInitialized(message, state, packet);
                    case BedrockPlayerAuthInputCodec.PLAYER_AUTH_INPUT_PACKET_ID ->
                            handlePlayerAuthInput(message.networkId(), state, packet);
                    case BedrockPlayerActionCodec.PLAYER_ACTION_PACKET_ID ->
                            handlePlayerAction(message, state, packet);
                    case BedrockInventoryTransactionCodec.INVENTORY_TRANSACTION_PACKET_ID ->
                            handleInventoryTransaction(message, state, packet);
                    case BedrockContainerPacketCodec.CONTAINER_CLOSE_PACKET_ID ->
                            handleContainerClose(message, state, packet);
                    case BedrockCraftingEventCodec.CRAFTING_EVENT_PACKET_ID ->
                            handleCraftingEvent(message, state, packet);
                    case BedrockMobEquipmentCodec.MOB_EQUIPMENT_PACKET_ID ->
                            handleMobEquipment(message, state, packet);
                    case BedrockCommandRequestCodec.COMMAND_REQUEST_PACKET_ID ->
                            handleCommandRequest(message, state, packet);
                    case BedrockModalFormCodec.MODAL_FORM_RESPONSE_PACKET_ID ->
                            handleModalFormResponse(message, state, packet);
                    case BedrockTakeItemActorCodec.TAKE_ITEM_ACTOR_PACKET_ID ->
                            handleTakeItemActor(message, state, packet);
                    default -> packetSink.accept(new PacketEvent(
                            message.networkId(), message.channelLabel(), packet));
                }
            }
          }
        } finally {
            state.combatLock.unlock();
            ru.nexora.ai.NexoraAiDiagnostics diagnostics = aiDiagnostics;
            if (diagnostics != null) {
                diagnostics.recordSlowOperation("bedrock-inbound",
                        Math.max(0L, nanoTime.getAsLong() - processingStartedNanos), 50_000_000L);
            }
        }
    }

    private void handleCraftingEvent(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        String networkId = message.networkId();
        if (!message.reliable()) {
            throw new SecurityException("CraftingEvent must arrive on ReliableDataChannel");
        }
        if (!state.loginAuthenticated || !state.clientInitialized || state.dead) {
            throw new SecurityException("CraftingEvent arrived before the player was ready");
        }

        BedrockCraftingEventCodec.DecodedEvent event;
        try {
            event = BedrockCraftingEventCodec.decode(packet, itemRegistry);
        } catch (IllegalArgumentException exception) {
            state.rejected = true;
            throw new SecurityException("Malformed Bedrock CraftingEvent", exception);
        }

        CraftingRecipe recipe = craftingRecipes.findByUuid(event.recipeId());
        if (state.openContainerIsCraftingTable && state.openContainerInventory != null
                && event.windowId() == state.openContainerWindowId) {
            handleCraftingTableEvent(networkId, state, event, recipe);
            return;
        }
        if (recipe == null || event.windowId() != 0 || event.input().size() != 4
                || event.output().size() != 1 || event.output().getFirst().isEmpty()
                || event.type() != (recipe.isShaped()
                        ? BedrockCraftingEventCodec.TYPE_SHAPED : BedrockCraftingEventCodec.TYPE_SHAPELESS)
                || !event.output().getFirst().orElseThrow().itemId().equals(recipe.result().itemId())
                || event.output().getFirst().orElseThrow().count() != recipe.result().count()
                || !craftingUiGridMatches(state, event.input())
                || !(recipe.isShaped()
                        ? matchesShapedCraftingInput(recipe, event.input())
                        : matchesShapelessCraftingInput(recipe, event.input()))) {
            sendInventoryAndEquipmentState(networkId, state);
            return;
        }

        List<Optional<ru.nexora.inventory.ItemStack>> before = state.inventory.snapshot();
        int selectedSlot = state.inventory.selectedHotbarSlot();
        List<Optional<ru.nexora.inventory.ItemStack>> beforeGrid = state.craftingUiInventory.snapshot();
        CraftingService.Result result = craftingService.craftFromGrid(
                state.inventory, state.craftingUiInventory, 28, recipe);
        if (result == CraftingService.Result.CRAFTED) {
            try {
                saveInventory(state);
            } catch (IOException | RuntimeException exception) {
                state.inventory.restore(before, selectedSlot);
                state.craftingUiInventory.restore(beforeGrid);
                try {
                    saveInventory(state);
                } catch (IOException | RuntimeException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                System.err.println("NEXORA: crafting transaction rolled back because persistence failed: "
                        + exception.getMessage());
            }
        } else {
            System.err.println("NEXORA: rejected crafting event for " + recipe.id() + ": " + result);
        }
        sendInventoryAndEquipmentState(networkId, state);
    }

    private void handleCraftingTableEvent(
            String networkId, SessionState state,
            BedrockCraftingEventCodec.DecodedEvent event, CraftingRecipe recipe
    ) {
        ContainerInventory grid = state.openContainerInventory;
        if (recipe == null || grid == null || event.input().size() != 9
                || event.output().size() != 1 || event.output().getFirst().isEmpty()
                || event.type() != (recipe.isShaped()
                        ? BedrockCraftingEventCodec.TYPE_SHAPED : BedrockCraftingEventCodec.TYPE_SHAPELESS)
                || !event.output().getFirst().orElseThrow().itemId().equals(recipe.result().itemId())
                || event.output().getFirst().orElseThrow().count() != recipe.result().count()
                || !event.input().equals(grid.snapshot())) {
            sendPackets(networkId, List.of(
                    BedrockInventoryContentCodec.createPlayerInventory(state.inventory, itemRegistry),
                    BedrockInventoryContentCodec.createContainerInventory(
                            state.openContainerWindowId, grid, itemRegistry)), true);
            return;
        }

        List<Optional<ItemStack>> beforePlayer = state.inventory.snapshot();
        int selectedSlot = state.inventory.selectedHotbarSlot();
        List<Optional<ItemStack>> beforeGrid = grid.snapshot();
        CraftingService.Result result = craftingService.craftFromGrid(
                state.inventory, grid, 0, 3, 3, recipe);
        if (result == CraftingService.Result.CRAFTED) {
            try {
                saveInventory(state);
            } catch (IOException | RuntimeException exception) {
                state.inventory.restore(beforePlayer, selectedSlot);
                grid.restore(beforeGrid);
                try { saveInventory(state); }
                catch (IOException | RuntimeException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                System.err.println("NEXORA: crafting table transaction rolled back: " + exception.getMessage());
            }
        } else {
            System.err.println("NEXORA: rejected crafting table event for " + recipe.id() + ": " + result);
        }
        sendPackets(networkId, List.of(
                BedrockInventoryContentCodec.createPlayerInventory(state.inventory, itemRegistry),
                BedrockInventoryContentCodec.createContainerInventory(
                        state.openContainerWindowId, grid, itemRegistry)), true);
    }

    private static boolean craftingUiGridMatches(
            SessionState state, List<Optional<ru.nexora.inventory.ItemStack>> input
    ) {
        List<Optional<ru.nexora.inventory.ItemStack>> ui = state.craftingUiInventory.snapshot();
        return input.equals(ui.subList(28, 32));
    }

    private static boolean matchesShapelessCraftingInput(
            CraftingRecipe recipe, List<Optional<ru.nexora.inventory.ItemStack>> input
    ) {
        java.util.Map<String, Integer> actual = new java.util.HashMap<>();
        for (Optional<ru.nexora.inventory.ItemStack> slot : input) {
            if (slot.isPresent()) actual.merge(slot.orElseThrow().itemId(), 1, Integer::sum);
        }
        return actual.equals(recipe.ingredients());
    }

    private static boolean matchesShapedCraftingInput(
            CraftingRecipe recipe, List<Optional<ru.nexora.inventory.ItemStack>> input
    ) {
        int gridWidth = 2;
        int gridHeight = 2;
        if (recipe.width() > gridWidth || recipe.height() > gridHeight) return false;
        for (int offsetY = 0; offsetY <= gridHeight - recipe.height(); offsetY++) {
            for (int offsetX = 0; offsetX <= gridWidth - recipe.width(); offsetX++) {
                for (boolean mirrored : new boolean[] {false, true}) {
                    boolean matches = true;
                    for (int y = 0; y < gridHeight && matches; y++) {
                        for (int x = 0; x < gridWidth; x++) {
                            String expectedItem = null;
                            int patternX = x - offsetX;
                            int patternY = y - offsetY;
                            if (patternX >= 0 && patternX < recipe.width()
                                    && patternY >= 0 && patternY < recipe.height()) {
                                if (mirrored) patternX = recipe.width() - 1 - patternX;
                                char symbol = recipe.pattern().get(patternY).charAt(patternX);
                                if (symbol != ' ') expectedItem = recipe.key().get(symbol);
                            }
                            Optional<ru.nexora.inventory.ItemStack> actual = input.get(y * gridWidth + x);
                            if (expectedItem == null ? actual.isPresent()
                                    : actual.isEmpty() || !actual.orElseThrow().itemId().equals(expectedItem)) {
                                matches = false;
                                break;
                            }
                        }
                    }
                    if (matches) return true;
                }
            }
        }
        return false;
    }

    private void handleContainerClose(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        if (!message.reliable()) {
            throw new SecurityException("ContainerClose must arrive on ReliableDataChannel");
        }
        if (!state.loginAuthenticated || !state.clientInitialized) {
            throw new SecurityException("ContainerClose arrived before player initialization");
        }

        BedrockContainerPacketCodec.DecodedClose close;
        try {
            close = BedrockContainerPacketCodec.decodeClose(packet);
        } catch (IllegalArgumentException exception) {
            state.rejected = true;
            throw new SecurityException("Malformed Bedrock ContainerClose", exception);
        }
        if (close.serverInitiated()) {
            throw new SecurityException("Client cannot mark ContainerClose as server initiated");
        }
        if (state.openContainerWindowId == 0
                || close.windowId() != state.openContainerWindowId
                || close.type() != state.openContainerType) {
            return;
        }

        saveAndClearContainerWindow(state);
    }

    private void openCraftingTableWindow(String networkId, SessionState state, int x, int y, int z) {
        if (world.getBlock(x, y, z) != BlockType.CRAFTING_TABLE || !isWithinBlockReach(state, x, y, z)) return;
        if (state.openContainerWindowId != 0) closeContainerWindow(networkId, state, true);

        int windowId = nextContainerWindowId(state);
        try {
            ContainerInventory grid = new ContainerInventory(9);
            BedrockPacket open = BedrockContainerPacketCodec.createOpen(
                    windowId, BedrockContainerPacketCodec.WORKBENCH_TYPE, x, y, z,
                    BedrockContainerPacketCodec.BLOCK_CONTAINER_ENTITY_ID);
            BedrockPacket content = BedrockInventoryContentCodec.createContainerInventory(
                    windowId, grid, itemRegistry);
            sendPackets(networkId, List.of(open, content), true);
            state.openContainerWindowId = windowId;
            state.openContainerType = BedrockContainerPacketCodec.WORKBENCH_TYPE;
            state.openContainerIsCraftingTable = true;
            state.openContainerIsFurnace = false;
            state.openContainerX = x;
            state.openContainerY = y;
            state.openContainerZ = z;
            state.openContainerInventory = grid;
        } catch (RuntimeException exception) {
            System.err.println("NEXORA: unable to open crafting table at " + x + "," + y + "," + z
                    + ": " + exception.getMessage());
        }
    }

    private static int nextContainerWindowId(SessionState state) {
        int windowId = state.nextContainerWindowId++;
        if (state.nextContainerWindowId > ContainerInventoryTransactionService.MAX_CONTAINER_WINDOW_ID) {
            state.nextContainerWindowId = 1;
        }
        return windowId;
    }

    private void openChestWindow(String networkId, SessionState state, int x, int y, int z) {
        if (world.getBlock(x, y, z) != BlockType.CHEST || !isWithinBlockReach(state, x, y, z)) return;
        WorldContainerManager manager = world.containers().orElse(null);
        if (manager == null) return;

        if (state.openContainerWindowId != 0) {
            closeContainerWindow(networkId, state, true);
        }

        int windowId = nextContainerWindowId(state);

        try {
            ContainerInventory inventory = manager.getOrCreate(x, y, z);
            BedrockPacket content = BedrockInventoryContentCodec.createContainerInventory(
                    windowId, inventory, itemRegistry);
            BedrockPacket open = BedrockContainerPacketCodec.createChestOpen(windowId, x, y, z);
            sendPackets(networkId, List.of(open, content), true);
            state.openContainerWindowId = windowId;
            state.openContainerType = BedrockContainerPacketCodec.CONTAINER_TYPE;
            state.openContainerIsCraftingTable = false;
            state.openContainerIsFurnace = false;
            state.openContainerX = x;
            state.openContainerY = y;
            state.openContainerZ = z;
            state.openContainerInventory = inventory;
        } catch (IOException | RuntimeException exception) {
            System.err.println("NEXORA: unable to open chest at " + x + "," + y + "," + z
                    + ": " + exception.getMessage());
        }
    }

    private void openFurnaceWindow(String networkId, SessionState state, int x, int y, int z) {
        openFurnaceWindow(networkId, state, x, y, z, BlockType.FURNACE);
    }

    private void openFurnaceWindow(
            String networkId, SessionState state, int x, int y, int z, BlockType blockType
    ) {
        FurnaceService.Kind kind = furnaceKind(blockType);
        if (kind == null || world.getBlock(x, y, z) != blockType || !isWithinBlockReach(state, x, y, z)) return;
        WorldFurnaceManager manager = world.furnaces().orElse(null);
        if (manager == null) return;
        if (state.openContainerWindowId != 0) closeContainerWindow(networkId, state, true);
        int windowId = nextContainerWindowId(state);
        try {
            ContainerInventory inventory = manager.getOrCreate(x, y, z, kind);
            int containerType = furnaceContainerType(blockType);
            BedrockPacket open = BedrockContainerPacketCodec.createOpen(
                    windowId, containerType, x, y, z,
                    BedrockContainerPacketCodec.BLOCK_CONTAINER_ENTITY_ID);
            BedrockPacket content = BedrockInventoryContentCodec.createContainerInventory(
                    windowId, inventory, itemRegistry);
            var furnaceState = manager.tick(x, y, z, kind, 0);
            sendPackets(networkId, List.of(open, content,
                    BedrockContainerSetDataCodec.create(windowId, 0, furnaceState.cookProgress()),
                    BedrockContainerSetDataCodec.create(windowId, 1, furnaceState.burnTicksRemaining()),
                    BedrockContainerSetDataCodec.create(windowId, 2, furnaceState.burnTicksTotal())), true);
            state.openContainerWindowId = windowId;
            state.openContainerType = containerType;
            state.openContainerIsCraftingTable = false;
            state.openContainerIsFurnace = true;
            state.openContainerX = x;
            state.openContainerY = y;
            state.openContainerZ = z;
            state.openContainerInventory = inventory;
        } catch (IOException | RuntimeException exception) {
            System.err.println("NEXORA: unable to open " + blockType.name().toLowerCase(Locale.ROOT)
                    + " at " + x + "," + y + "," + z + ": " + exception.getMessage());
        }
    }

    private static FurnaceService.Kind furnaceKind(BlockType blockType) {
        return switch (blockType) {
            case FURNACE -> FurnaceService.Kind.FURNACE;
            case BLAST_FURNACE -> FurnaceService.Kind.BLAST_FURNACE;
            case SMOKER -> FurnaceService.Kind.SMOKER;
            default -> null;
        };
    }

    private static int furnaceContainerType(BlockType blockType) {
        return switch (blockType) {
            case FURNACE -> BedrockContainerPacketCodec.FURNACE_TYPE;
            case BLAST_FURNACE -> BedrockContainerPacketCodec.BLAST_FURNACE_TYPE;
            case SMOKER -> BedrockContainerPacketCodec.SMOKER_TYPE;
            default -> throw new IllegalArgumentException("Block is not a furnace: " + blockType);
        };
    }

    private void closeContainerWindow(String networkId, SessionState state, boolean serverInitiated) {
        if (state.openContainerWindowId == 0) return;
        int windowId = state.openContainerWindowId;
        if (state.openContainerIsFurnace) {
            try {
                world.furnaces().ifPresent(manager -> {
                    try { manager.save(state.openContainerX, state.openContainerY, state.openContainerZ); }
                    catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
                });
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to save furnace while closing: " + exception.getMessage());
            }
        } else if (!state.openContainerIsCraftingTable) {
            try {
                world.containers().ifPresent(manager -> {
                    try {
                        manager.save(state.openContainerX, state.openContainerY, state.openContainerZ);
                    } catch (IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                });
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to save chest while closing: " + exception.getMessage());
            }
        }
        try {
            sendPacket(networkId, BedrockContainerPacketCodec.createClose(
                    windowId, state.openContainerType, serverInitiated), true);
        } finally {
            if (state.openContainerIsCraftingTable) returnCraftingTableGridItems(state);
            clearContainerWindow(state);
        }
    }

    private void saveAndClearContainerWindow(SessionState state) {
        if (state.openContainerWindowId == 0) return;
        if (state.openContainerIsCraftingTable) {
            returnCraftingTableGridItems(state);
            clearContainerWindow(state);
            return;
        }
        try {
            if (state.openContainerIsFurnace) {
                world.furnaces().ifPresent(manager -> {
                    try { manager.save(state.openContainerX, state.openContainerY, state.openContainerZ); }
                    catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
                });
            } else {
                WorldContainerManager manager = world.containers().orElse(null);
                if (manager != null) manager.save(state.openContainerX, state.openContainerY, state.openContainerZ);
            }
        } catch (IOException | RuntimeException exception) {
            System.err.println("NEXORA: unable to save closed container: " + exception.getMessage());
        } finally {
            clearContainerWindow(state);
        }
    }

    private void returnCraftingTableGridItems(SessionState state) {
        ContainerInventory grid = state.openContainerInventory;
        if (grid == null) return;
        List<Optional<ItemStack>> playerBefore = state.inventory.snapshot();
        int selectedSlot = state.inventory.selectedHotbarSlot();
        List<Optional<ItemStack>> gridBefore = grid.snapshot();
        List<Long> spawned = new ArrayList<>();
        try {
            for (int slot = 0; slot < grid.size(); slot++) {
                ItemStack stack = grid.getSlot(slot).orElse(null);
                if (stack == null) continue;
                int overflow = state.inventory.add(stack);
                if (overflow > 0) {
                    var dropped = world.itemEntities().spawn(
                            stack.withCount(overflow),
                            state.positionX, state.positionY + 0.5, state.positionZ,
                            0, 0.15, 0, System.currentTimeMillis(),
                            WorldItemEntityManager.DEFAULT_PICKUP_DELAY_MILLIS,
                            state.identity == null ? null : state.identity.xuid());
                    spawned.add(dropped.runtimeId());
                }
                grid.setSlot(slot, null);
            }
            saveInventory(state);
        } catch (IOException | RuntimeException exception) {
            state.inventory.restore(playerBefore, selectedSlot);
            grid.restore(gridBefore);
            for (long runtimeId : spawned) {
                try { world.itemEntities().remove(runtimeId); }
                catch (RuntimeException rollbackFailure) { exception.addSuppressed(rollbackFailure); }
            }
            System.err.println("NEXORA: crafting table grid return failed: " + exception.getMessage());
        }
    }

    private static void clearContainerWindow(SessionState state) {
        state.openContainerWindowId = 0;
        state.openContainerType = BedrockContainerPacketCodec.CONTAINER_TYPE;
        state.openContainerIsCraftingTable = false;
        state.openContainerIsFurnace = false;
        state.openContainerInventory = null;
        state.openContainerX = 0;
        state.openContainerY = 0;
        state.openContainerZ = 0;
    }

    private void handleFurnaceUpdate(WorldFurnaceManager.FurnaceUpdate update) {
        if (itemRegistry == null) return;
        for (var entry : sessions.entrySet()) {
            SessionState state = entry.getValue();
            synchronized (state) {
                if (!state.clientInitialized || !state.openContainerIsFurnace
                        || state.openContainerWindowId == 0
                        || state.openContainerX != update.x()
                        || state.openContainerY != update.y()
                        || state.openContainerZ != update.z()) continue;
                List<BedrockPacket> packets = new ArrayList<>();
                if (update.result().inventoryChanged()) {
                    packets.add(BedrockInventoryContentCodec.createContainerInventory(
                            state.openContainerWindowId, update.inventory(), itemRegistry));
                }
                packets.add(BedrockContainerSetDataCodec.create(
                        state.openContainerWindowId, 0, update.result().cookProgress()));
                packets.add(BedrockContainerSetDataCodec.create(
                        state.openContainerWindowId, 1, update.result().burnTicksRemaining()));
                packets.add(BedrockContainerSetDataCodec.create(
                        state.openContainerWindowId, 2, update.result().burnTicksTotal()));
                sendPackets(entry.getKey(), packets, true);
            }
        }
    }

    private void sendContainerReconciliation(String networkId, SessionState state) {
        List<BedrockPacket> packets = new java.util.ArrayList<>();
        packets.add(BedrockInventoryContentCodec.createPlayerInventory(state.inventory, itemRegistry));
        packets.addAll(createEquipmentReconciliationPackets(state));
        packets.add(BedrockInventoryContentCodec.createPlayerUiInventory(state.craftingUiInventory, itemRegistry));
        if (state.openContainerWindowId != 0 && state.openContainerInventory != null) {
            packets.add(BedrockInventoryContentCodec.createContainerInventory(
                    state.openContainerWindowId, state.openContainerInventory, itemRegistry));
        }
        sendPackets(networkId, packets, true);
    }

    private void handlePlayerAction(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        if (!message.reliable()) throw new SecurityException("PlayerAction must arrive on ReliableDataChannel");
        if (!state.loginAuthenticated || !state.clientInitialized) {
            throw new SecurityException("PlayerAction arrived before player initialization");
        }
        BedrockPlayerActionCodec.PlayerAction action;
        try {
            action = BedrockPlayerActionCodec.decode(packet);
        } catch (IllegalArgumentException exception) {
            throw new SecurityException("Malformed Bedrock PlayerAction", exception);
        }
        if (action.runtimeEntityId() != state.entityId) {
            throw new SecurityException("PlayerAction runtime ID does not match authenticated player");
        }
        if (action.action() == BedrockPlayerActionCodec.ACTION_RESPAWN && state.dead) {
            respawnPlayer(message.networkId(), state);
            return;
        }
        if (action.action() == BedrockPlayerActionCodec.ACTION_START_USING_ITEM && !state.dead) {
            startSpearChargeIfHeld(state);
            return;
        }
        if (action.action() == BedrockPlayerActionCodec.ACTION_DROP_ITEM && !state.dead) {
            // The protocol-2225 PlayerAction DROP_ITEM action drops one item from
            // the selected hotbar slot. The client never chooses the item/count:
            // both are resolved from the authenticated server inventory.
            int selectedSlot = state.inventory.selectedHotbarSlot();
            double yaw = Math.toRadians(state.yaw);
            double velocityX = -Math.sin(yaw) * 0.2;
            double velocityZ = Math.cos(yaw) * 0.2;
            try {
                dropPlayerItem(message.networkId(), selectedSlot, 1,
                        velocityX, 0.2, velocityZ);
            } catch (IOException | RuntimeException exception) {
                // Restore the authoritative slot/equipment view if persistence or
                // world-actor creation failed after the client's local prediction.
                sendInventoryAndEquipmentState(message.networkId(), state);
                System.err.println("NEXORA: client item drop rejected: " + exception.getMessage());
            }
        }
    }

    private void handleDeath(String networkId, SessionState state, String deathCause) {
        if (state.dead && state.deathPacketsSent) return;
        state.dead = true;
        clearUsingItem(state);
        closeContainerWindow(networkId, state, true);
        state.deathPacketsSent = true;
        sendPackets(networkId, List.of(
                BedrockRespawnCodec.createDeathInfo(deathCause),
                BedrockRespawnCodec.createSearchingForSpawn(
                        world.spawnX() + 0.5, world.spawnY(), world.spawnZ() + 0.5, state.entityId)
        ), true);
    }

    private void respawnPlayer(String networkId, SessionState state) {
        double x = world.spawnX() + 0.5;
        double y = world.spawnY();
        double z = world.spawnZ() + 0.5;
        PlayerPhysics.State physics = PlayerPhysics.spawn(world, x, y, z);
        state.positionX = (float) x;
        state.positionY = (float) y;
        state.positionZ = (float) z;
        state.yaw = 0;
        state.pitch = 0;
        state.physicsState = physics;
        state.vitals = PlayerVitals.DEFAULT;
        clearUsingItem(state);
        state.dead = false;
        state.deathPacketsSent = false;
        state.lastMovementNanos = nanoTime.getAsLong();
        sendPacket(networkId, BedrockRespawnCodec.createRespawnMove(
                state.entityId, x, y, z, state.pitch, state.yaw,
                physics.onGround(), Math.max(0, state.lastInputTick)), true);
        sendInitialPlayerInventory(networkId, state);
        int chunkX = Math.floorDiv((int) Math.floor(x), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(z), 16);
        sendPacket(networkId, BedrockStartGameCodec.createChunkPublisherUpdate(
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)), true);
        sendChunkArea(networkId, state, chunkX, chunkZ);
    }

    private void handleCommandRequest(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        if (!message.reliable()) {
            throw new SecurityException("CommandRequest must arrive on ReliableDataChannel");
        }
        if (!state.loginAuthenticated || !state.clientInitialized) {
            throw new SecurityException("CommandRequest arrived before player initialization");
        }
        BedrockCommandRequestCodec.DecodedCommand command;
        try {
            command = BedrockCommandRequestCodec.decode(packet);
        } catch (IllegalArgumentException exception) {
            throw new SecurityException("Malformed Bedrock CommandRequest", exception);
        }
        if (!"Player".equals(command.originType()) || command.internal()
                || command.playerId() != state.entityId) {
            throw new SecurityException("CommandRequest origin does not match the authenticated player");
        }

        String normalized = command.command().trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("/equipment") || normalized.equals("/equip")) {
            openEquipmentMenu(message.networkId(), state, "");
            return;
        }
        if (normalized.equals("/ai spawner") || normalized.startsWith("/ai spawner ")) {
            if (!isAiAdmin(state)) {
                sendAiMessage(message.networkId(), "§c[NEXORA AI] Нет доступа к управлению спавнерами.");
            } else {
                handleMobSpawnerCommand(message.networkId(), command.command().trim()
                        .substring("/ai spawner".length()).trim());
            }
            return;
        }
        if (normalized.equals("/ai mob") || normalized.equals("/ai mob stats")
                || normalized.equals("/ai mob stuck")) {
            if (!isAiAdmin(state)) {
                sendAiMessage(message.networkId(), "§c[NEXORA AI] Нет доступа к диагностике Mob AI.");
            } else if (normalized.equals("/ai mob stuck")) {
                sendAiMessage(message.networkId(), "§b[NEXORA AI MOB] §f" + mobAiStuckSummary());
            } else {
                sendAiMessage(message.networkId(), "§b[NEXORA AI MOB] §f" + mobAiDiagnosticsSummary());
            }
            return;
        }
        if (normalized.equals("/ai guard") || normalized.startsWith("/ai guard ")) {
            if (!isAiAdmin(state)) {
                sendAiMessage(message.networkId(), "§c[NEXORA AI] Нет доступа к панели Guard.");
            } else {
                handleAiGuardCommand(message.networkId(), state,
                        command.command().trim().substring("/ai guard".length()).trim());
            }
            return;
        }
        if (normalized.equals("/ai admin")) {
            if (!isAiAdmin(state)) {
                sendAiMessage(message.networkId(), "§c[NEXORA AI] Нет доступа к режиму администрации.");
            } else if (aiAssistant != null) {
                sendAiMessage(message.networkId(), "§b[NEXORA AI ADMIN] §f" + aiAssistant.adminStatus());
            }
            return;
        }
        if (normalized.startsWith("/ai admin ")) {
            if (!isAiAdmin(state)) {
                sendAiMessage(message.networkId(), "§c[NEXORA AI] Нет доступа к режиму администрации.");
            } else {
                handleAiChat(message.networkId(), state, command.command().trim().substring(10).trim(), true);
            }
            return;
        }
        if (normalized.equals("/ai")) {
            if (aiAssistant == null || !aiAssistant.isEnabled()) {
                sendAiMessage(message.networkId(), "§b[NEXORA AI] §fИИ пока не настроен. Администратору нужно задать NEXORA_AI_API_KEY.");
            } else {
                openAiForm(message.networkId(), state);
            }
            return;
        }
        if (normalized.startsWith("/ai ")) {
            handleAiChat(message.networkId(), state, command.command().trim().substring(4).trim(), false);
            return;
        }
        packetSink.accept(new PacketEvent(message.networkId(), message.channelLabel(), packet));
    }

    /** Installs the optional runtime diagnostics observer. */
    public void setAiDiagnostics(ru.nexora.ai.NexoraAiDiagnostics diagnostics) {
        this.aiDiagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** Installs the optional in-game assistant. */
    public void setAiAssistant(ru.nexora.ai.NexoraAiAssistant assistant) {
        this.aiAssistant = Objects.requireNonNull(assistant, "assistant");
    }

    private void openAiForm(String networkId, SessionState state) {
        long next = nextFormId.getAndIncrement();
        if (next < 1 || next > Integer.MAX_VALUE) {
            throw new IllegalStateException("Bedrock form ID space exhausted");
        }
        com.fasterxml.jackson.databind.node.ObjectNode form = MAPPER.createObjectNode();
        form.put("type", "custom_form");
        form.put("title", "NEXORA AI");
        com.fasterxml.jackson.databind.node.ArrayNode content = form.putArray("content");
        content.addObject().put("type", "input")
                .put("text", "Задай вопрос игровому помощнику")
                .put("placeholder", "Например: как работает копьё?")
                .put("default", "");
        state.pendingAiFormId = (int) next;
        try {
            sendPacket(networkId, BedrockModalFormCodec.createRequest(
                    (int) next, MAPPER.writeValueAsString(form)), true);
        } catch (IOException exception) {
            state.pendingAiFormId = 0;
            throw new IllegalStateException("Unable to open NexoraAI chat form", exception);
        }
    }

    private boolean isAiAdmin(SessionState state) {
        ru.nexora.ai.NexoraAiAssistant assistant = aiAssistant;
        return assistant != null && state.identity != null && assistant.isAdmin(state.identity.xuid());
    }

    private void handleAiChat(String networkId, SessionState state, String prompt, boolean admin) {
        if (prompt.equalsIgnoreCase("help")) {
            sendAiMessage(networkId, "§b[NEXORA AI] §fКоманды: /ai, /ai <вопрос>, /ai clear; "
                    + "операторам: /ai mob stats, /ai mob stuck, /ai guard, /ai admin, /ai admin plan <проблема>, /ai admin proposals, "
                    + "/ai admin proposal <id>, /ai admin approve <id>, /ai admin reject <id>.");
            return;
        }
        if (admin && handleAiAdminProposalCommand(networkId, state, prompt)) return;
        if (prompt.equalsIgnoreCase("clear") || prompt.equalsIgnoreCase("reset")) {
            String conversationId = state.identity != null && state.identity.xuid() != null
                    && !state.identity.xuid().isBlank() ? state.identity.xuid() : networkId;
            ru.nexora.ai.NexoraAiAssistant assistant = aiAssistant;
            if (assistant != null) assistant.clearConversation(conversationId, admin);
            sendAiMessage(networkId, "§b[NEXORA AI] §fКонтекст этого диалога очищен.");
            return;
        }
        if (prompt.equalsIgnoreCase("status")) {
            ru.nexora.ai.NexoraAiAssistant configured = aiAssistant;
            sendAiMessage(networkId, "§b[NEXORA AI] §fСтатус: "
                    + (configured != null && configured.isEnabled() ? "готов к работе" : "ожидает настройки API-ключа"));
            return;
        }
        if (prompt.isBlank()) {
            sendAiMessage(networkId, "§b[NEXORA AI] §fНапиши вопрос после /ai.");
            return;
        }
        if (prompt.length() > 600) {
            sendAiMessage(networkId, "§b[NEXORA AI] §fМаксимальная длина вопроса — 600 символов.");
            return;
        }
        if (admin && !isAiAdmin(state)) {
            sendAiMessage(networkId, "§c[NEXORA AI] Нет доступа к режиму администрации.");
            return;
        }
        ru.nexora.ai.NexoraAiAssistant assistant = aiAssistant;
        if (assistant == null || !assistant.isEnabled()) {
            sendAiMessage(networkId, "§b[NEXORA AI] §fИИ не настроен. Задай NEXORA_AI_API_KEY в окружении сервера.");
            return;
        }
        String playerName = state.identity == null || state.identity.displayName() == null
                ? "Игрок" : state.identity.displayName();
        String conversationId = state.identity != null && state.identity.xuid() != null
                && !state.identity.xuid().isBlank() ? state.identity.xuid() : networkId;
        ru.nexora.ai.NexoraAiAssistant.AskResult result = assistant.ask(
                conversationId, playerName, prompt, admin, reply -> {
                    SessionState current = sessions.get(networkId);
                    if (current == state && current.loginAuthenticated && current.clientInitialized) {
                        sendAiMessage(networkId, "§b[NEXORA AI] §f" + reply);
                    }
                });
        if (result == ru.nexora.ai.NexoraAiAssistant.AskResult.RATE_LIMITED) {
            sendAiMessage(networkId, "§b[NEXORA AI] §fПодожди несколько секунд перед следующим вопросом.");
        } else if (result == ru.nexora.ai.NexoraAiAssistant.AskResult.ACCEPTED) {
            sendAiMessage(networkId, "§b[NEXORA AI] §fДумаю...");
        } else {
            sendAiMessage(networkId, "§b[NEXORA AI] §fИИ не настроен.");
        }
    }

    private boolean handleAiAdminProposalCommand(String networkId, SessionState state, String prompt) {
        if (!prompt.equalsIgnoreCase("proposals")
                && !prompt.toLowerCase(Locale.ROOT).startsWith("plan ")
                && !prompt.toLowerCase(Locale.ROOT).startsWith("proposal ")
                && !prompt.toLowerCase(Locale.ROOT).startsWith("approve ")
                && !prompt.toLowerCase(Locale.ROOT).startsWith("reject ")) {
            return false;
        }
        if (!isAiAdmin(state)) {
            sendAiMessage(networkId, "§c[NEXORA AI] Нет доступа к предложениям исправлений.");
            return true;
        }
        ru.nexora.ai.NexoraAiAssistant assistant = aiAssistant;
        if (assistant == null) {
            sendAiMessage(networkId, "§b[NEXORA AI] §fСервис NexoraAI недоступен.");
            return true;
        }

        String normalized = prompt.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.equals("proposals")) {
            java.util.List<ru.nexora.ai.NexoraAiRepairProposals.Proposal> proposals =
                    assistant.repairProposals(state.identity.xuid());
            if (proposals.isEmpty()) {
                sendAiMessage(networkId, "§b[NEXORA AI ADMIN] §fПредложений пока нет.");
                return true;
            }
            StringBuilder listing = new StringBuilder("§b[NEXORA AI ADMIN] §fПредложения:");
            for (ru.nexora.ai.NexoraAiRepairProposals.Proposal proposal : proposals.stream().limit(8).toList()) {
                listing.append("\n").append(proposal.id()).append(" — ")
                        .append(repairProposalStatus(proposal.status()));
            }
            sendAiMessage(networkId, listing.toString());
            return true;
        }

        if (lower.startsWith("plan ")) {
            if (!assistant.isEnabled()) {
                sendAiMessage(networkId, "§b[NEXORA AI] §fДля создания плана настрой NEXORA_AI_API_KEY.");
                return true;
            }
            String issue = normalized.substring(5).trim();
            if (issue.isEmpty() || issue.length() > 300) {
                sendAiMessage(networkId, "§b[NEXORA AI ADMIN] §fУкажи проблему (1–300 символов).");
                return true;
            }
            String xuid = state.identity.xuid();
            String operatorName = state.identity.displayName() == null ? "Оператор" : state.identity.displayName();
            ru.nexora.ai.NexoraAiAssistant.AskResult result = assistant.createRepairProposal(
                    xuid, operatorName, issue, proposal -> {
                        SessionState current = sessions.get(networkId);
                        if (current != state || !current.loginAuthenticated || !current.clientInitialized) return;
                        if (proposal == null) {
                            sendAiMessage(networkId, "§b[NEXORA AI ADMIN] §fНе удалось создать план. Проверь ответ провайдера и повтори позже.");
                        } else {
                            sendAiMessage(networkId, "§b[NEXORA AI ADMIN] §fСоздано " + proposal.id()
                                    + " (ожидает проверки). Одобрение не применяет изменения. "
                                    + "Посмотреть: /ai admin proposal " + proposal.id());
                        }
                    });
            if (result == ru.nexora.ai.NexoraAiAssistant.AskResult.RATE_LIMITED) {
                sendAiMessage(networkId, "§b[NEXORA AI ADMIN] §fПодожди несколько секунд.");
            } else if (result == ru.nexora.ai.NexoraAiAssistant.AskResult.ACCEPTED) {
                sendAiMessage(networkId, "§b[NEXORA AI ADMIN] §fГотовлю план. Он будет только рекомендацией.");
            } else {
                sendAiMessage(networkId, "§b[NEXORA AI ADMIN] §fИИ не настроен.");
            }
            return true;
        }

        String[] parts = normalized.split("\\s+", 2);
        if (parts.length != 2 || parts[1].isBlank()) {
            sendAiMessage(networkId, "§b[NEXORA AI ADMIN] §fУкажи ID предложения.");
            return true;
        }
        String id = parts[1].trim();
        if (lower.startsWith("proposal ")) {
            ru.nexora.ai.NexoraAiRepairProposals.Proposal proposal =
                    assistant.repairProposal(state.identity.xuid(), id).orElse(null);
            if (proposal == null) {
                sendAiMessage(networkId, "§b[NEXORA AI ADMIN] §fПредложение не найдено или истекло.");
            } else {
                sendAiMessage(networkId, "§b[NEXORA AI ADMIN] §f" + proposal.id() + " — "
                        + repairProposalStatus(proposal.status()) + "\nСоздал: " + proposal.requesterXuid()
                        + "\nРешение: " + (proposal.reviewedByXuid() == null ? "ещё не принято"
                        : proposal.reviewedByXuid() + " в " + proposal.reviewedAt())
                        + "\nПроблема: " + proposal.issue() + "\nПлан: " + proposal.plan());
            }
            return true;
        }
        if (lower.startsWith("approve ")) {
            ru.nexora.ai.NexoraAiRepairProposals.Proposal proposal =
                    assistant.approveRepairProposal(state.identity.xuid(), id).orElse(null);
            sendAiMessage(networkId, proposal == null
                    ? "§b[NEXORA AI ADMIN] §fНе удалось одобрить: проверь ID и статус."
                    : "§b[NEXORA AI ADMIN] §f" + proposal.id()
                    + " одобрено только для ручной проверки. Ничего не применено.");
            return true;
        }
        if (lower.startsWith("reject ")) {
            ru.nexora.ai.NexoraAiRepairProposals.Proposal proposal =
                    assistant.rejectRepairProposal(state.identity.xuid(), id).orElse(null);
            sendAiMessage(networkId, proposal == null
                    ? "§b[NEXORA AI ADMIN] §fНе удалось отклонить: проверь ID и статус."
                    : "§b[NEXORA AI ADMIN] §f" + proposal.id() + " отклонено.");
            return true;
        }
        return false;
    }

    private static String repairProposalStatus(ru.nexora.ai.NexoraAiRepairProposals.Status status) {
        return switch (status) {
            case PENDING_REVIEW -> "ожидает проверки";
            case APPROVED_FOR_MANUAL_REVIEW -> "одобрено для ручной проверки";
            case REJECTED -> "отклонено";
        };
    }

    private void sendAiMessage(String networkId, String message) {
        try {
            sendPacket(networkId, BedrockTextCodec.createSystemMessage(message), true);
        } catch (RuntimeException exception) {
            System.err.println("NEXORA AI: unable to send reply: " + exception.getMessage());
        }
    }

    private void handleModalFormResponse(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        if (!message.reliable()) {
            throw new SecurityException("ModalFormResponse must arrive on ReliableDataChannel");
        }
        if (!state.loginAuthenticated || !state.clientInitialized) {
            throw new SecurityException("ModalFormResponse arrived before player initialization");
        }
        BedrockModalFormCodec.DecodedResponse response;
        try {
            response = BedrockModalFormCodec.decodeResponse(packet);
        } catch (IllegalArgumentException exception) {
            throw new SecurityException("Malformed Bedrock ModalFormResponse", exception);
        }
        if (state.pendingAiFormId != 0 && state.pendingAiFormId == response.formId()) {
            state.pendingAiFormId = 0;
            if (response.cancelled()) return;
            try {
                com.fasterxml.jackson.databind.JsonNode answer = MAPPER.readTree(response.responseJson());
                if (answer != null && answer.isArray() && answer.size() > 0 && answer.get(0).isTextual()) {
                    handleAiChat(message.networkId(), state, answer.get(0).asText().trim(), false);
                }
            } catch (IOException exception) {
                throw new SecurityException("Invalid JSON in NexoraAI form response", exception);
            }
            return;
        }
        PendingEquipmentForm pending = state.pendingEquipmentForm;
        if (pending == null || pending.formId() != response.formId()) return;
        state.pendingEquipmentForm = null;
        if (response.cancelled()) return;

        final com.fasterxml.jackson.databind.JsonNode selected;
        try {
            selected = MAPPER.readTree(response.responseJson());
        } catch (IOException exception) {
            throw new SecurityException("Invalid JSON in ModalFormResponse", exception);
        }
        if (selected == null || !selected.isIntegralNumber() || !selected.canConvertToInt()) return;
        int index = selected.intValue();

        if (pending.kind() == EquipmentFormKind.MAIN) {
            EquipmentSlot[] slots = EquipmentSlot.values();
            if (index < 0 || index >= slots.length) return;
            openEquipmentSlotMenu(message.networkId(), state, slots[index], "");
            return;
        }

        if (index >= 0 && index < pending.inventorySlots().size()) {
            int inventorySlot = pending.inventorySlots().get(index);
            try {
                PlayerEquipmentService.Result result = equipMagicItem(
                        message.networkId(), pending.slot(), inventorySlot);
                String status = result == PlayerEquipmentService.Result.EQUIPPED
                        ? "Предмет надет." : "Не удалось надеть предмет: " + result.name();
                openEquipmentSlotMenu(message.networkId(), state, pending.slot(), status);
            } catch (IOException exception) {
                openEquipmentSlotMenu(message.networkId(), state, pending.slot(),
                        "Не удалось сохранить экипировку. Предмет возвращён в исходное состояние.");
            }
            return;
        }

        int unequipIndex = pending.inventorySlots().size();
        if (pending.hasUnequip() && index == unequipIndex) {
            try {
                PlayerEquipmentService.Result result = unequipMagicItem(message.networkId(), pending.slot());
                String status = result == PlayerEquipmentService.Result.UNEQUIPPED
                        ? "Предмет снят." : "Не удалось снять предмет: " + result.name();
                openEquipmentSlotMenu(message.networkId(), state, pending.slot(), status);
            } catch (IOException exception) {
                openEquipmentSlotMenu(message.networkId(), state, pending.slot(),
                        "Не удалось сохранить экипировку. Изменения отменены.");
            }
            return;
        }

        int backIndex = pending.inventorySlots().size() + (pending.hasUnequip() ? 1 : 0);
        if (index == backIndex) openEquipmentMenu(message.networkId(), state, "");
    }

    private void openEquipmentMenu(String networkId, SessionState state, String status) {
        com.fasterxml.jackson.databind.node.ObjectNode form = MAPPER.createObjectNode();
        form.put("type", "form");
        form.put("title", "NEXORA — Экипировка");
        StringBuilder description = new StringBuilder("Магическая экипировка персонажа.");
        if (status != null && !status.isBlank()) description.append("\n").append(status);
        description.append("\nВыбери слот:");
        form.put("content", description.toString());
        com.fasterxml.jackson.databind.node.ArrayNode buttons = form.putArray("buttons");
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            String item = state.equipment.magicSlot(slot).map(stack -> equipmentItemDisplayName(stack.itemId()))
                    .orElse("пусто");
            buttons.addObject().put("text", slot.displayName() + " — " + item);
        }
        sendEquipmentForm(networkId, state, form, new PendingEquipmentForm(
                0, EquipmentFormKind.MAIN, null, List.of(), false));
    }

    private void openEquipmentSlotMenu(
            String networkId, SessionState state, EquipmentSlot slot, String status
    ) {
        List<Integer> inventorySlots = new java.util.ArrayList<>();
        List<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < state.inventory.size(); i++) {
            Optional<ru.nexora.inventory.ItemStack> optional = state.inventory.getSlot(i);
            if (optional.isEmpty()) continue;
            ru.nexora.inventory.ItemStack stack = optional.orElseThrow();
            if (!equipmentSlotRegistry.allows(stack, slot)) continue;
            inventorySlots.add(i);
            labels.add("Инвентарь " + (i + 1) + " — " + equipmentItemDisplayName(stack.itemId())
                    + " (" + formatEquipmentAttributes(stack.itemId()) + ")");
        }

        boolean hasUnequip = state.equipment.magicSlot(slot).isPresent();
        com.fasterxml.jackson.databind.node.ObjectNode form = MAPPER.createObjectNode();
        form.put("type", "form");
        form.put("title", "Экипировка — " + slot.displayName());
        Optional<ru.nexora.inventory.ItemStack> currentStack = state.equipment.magicSlot(slot);
        String current = currentStack.map(stack -> equipmentItemDisplayName(stack.itemId())).orElse("пусто");
        StringBuilder description = new StringBuilder("Сейчас: ").append(current);
        currentStack.ifPresent(stack -> description.append("\n").append(formatEquipmentAttributes(stack.itemId())));
        if (status != null && !status.isBlank()) description.append("\n").append(status);
        if (labels.isEmpty()) description.append("\nВ инвентаре нет подходящих предметов.");
        else description.append("\nВыбери предмет для этого слота:");
        form.put("content", description.toString());

        com.fasterxml.jackson.databind.node.ArrayNode buttons = form.putArray("buttons");
        for (String label : labels) buttons.addObject().put("text", label);
        if (hasUnequip) buttons.addObject().put("text", "Снять надетый предмет");
        buttons.addObject().put("text", "Назад");

        sendEquipmentForm(networkId, state, form, new PendingEquipmentForm(
                0, EquipmentFormKind.SLOT, slot, List.copyOf(inventorySlots), hasUnequip));
    }

    private String formatEquipmentAttributes(String itemId) {
        var attributes = equipmentSlotRegistry.attributes(itemId);
        if (attributes.isEmpty()) return "Бонусы: нет";
        List<String> values = new java.util.ArrayList<>();
        attributes.forEach((attribute, value) -> {
            String name = switch (attribute) {
                case MAX_HEALTH -> "Здоровье";
                case ARMOR -> "Броня";
                case ATTACK_DAMAGE -> "Урон";
                case MOVEMENT_SPEED -> "Скорость";
                case MAX_MANA -> "Макс. мана";
                case MANA_REGENERATION -> "Реген. маны";
                case MAGIC_POWER -> "Сила магии";
                case COOLDOWN_REDUCTION -> "Снижение перезарядки";
                case FIRE_RESISTANCE -> "Сопротивление огню";
                case ICE_RESISTANCE -> "Сопротивление льду";
                case LIGHTNING_RESISTANCE -> "Сопротивление молнии";
            };
            boolean percentage = attribute == ru.nexora.inventory.EquipmentAttribute.COOLDOWN_REDUCTION
                    || attribute == ru.nexora.inventory.EquipmentAttribute.FIRE_RESISTANCE
                    || attribute == ru.nexora.inventory.EquipmentAttribute.ICE_RESISTANCE
                    || attribute == ru.nexora.inventory.EquipmentAttribute.LIGHTNING_RESISTANCE;
            String formatted = percentage
                    ? String.format(Locale.ROOT, "%+.0f%%", value * 100.0)
                    : String.format(Locale.ROOT, "%+.1f", value);
            values.add(name + " " + formatted);
        });
        return "Бонусы: " + String.join(", ", values);
    }

    private static String equipmentItemDisplayName(String itemId) {
        return switch (itemId) {
            case "minecraft:blaze_rod" -> "Огненный жезл (временный предмет)";
            case "minecraft:gold_nugget" -> "Золотое кольцо (временный предмет)";
            case "minecraft:nautilus_shell" -> "Амулет моря (временный предмет)";
            case "minecraft:amethyst_shard" -> "Талисман аметиста (временный предмет)";
            case "minecraft:heart_of_the_sea" -> "Сердце моря — артефакт";
            case "minecraft:ghast_tear" -> "Слеза гаста — артефакт";
            case "nexora:ember_staff" -> "Посох углей";
            case "nexora:power_ring" -> "Кольцо силы";
            case "nexora:ward_ring" -> "Кольцо защиты";
            case "nexora:moon_amulet" -> "Лунный амулет";
            case "nexora:luck_talisman" -> "Талисман удачи";
            case "nexora:ancient_eye" -> "Око древних";
            case "nexora:phoenix_core" -> "Ядро феникса";
            default -> itemId;
        };
    }

    private void sendEquipmentForm(
            String networkId, SessionState state,
            com.fasterxml.jackson.databind.node.ObjectNode form, PendingEquipmentForm pending
    ) {
        long next = nextFormId.getAndIncrement();
        if (next < 1 || next > Integer.MAX_VALUE) {
            throw new IllegalStateException("Bedrock form ID space exhausted");
        }
        int formId = (int) next;
        state.pendingEquipmentForm = new PendingEquipmentForm(
                formId, pending.kind(), pending.slot(), pending.inventorySlots(), pending.hasUnequip());
        try {
            sendPacket(networkId, BedrockModalFormCodec.createRequest(
                    formId, MAPPER.writeValueAsString(form)), true);
        } catch (IOException exception) {
            state.pendingEquipmentForm = null;
            throw new IllegalStateException("Unable to encode NEXORA equipment form", exception);
        }
    }

    private List<BedrockPacket> createEquipmentReconciliationPackets(SessionState state) {
        List<BedrockPacket> packets = new java.util.ArrayList<>();
        packets.add(BedrockMobEquipmentCodec.createPlayerEquipment(state.entityId, state.inventory, itemRegistry));
        packets.addAll(BedrockPlayerEquipmentCodec.createInitialEquipment(state.equipment, itemRegistry));
        return List.copyOf(packets);
    }

    private void handleInventoryTransaction(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        String networkId = message.networkId();
        if (!message.reliable()) {
            throw new SecurityException("InventoryTransaction must arrive on ReliableDataChannel");
        }
        if (!state.loginAuthenticated || !state.clientInitialized) {
            throw new SecurityException("InventoryTransaction arrived before player initialization");
        }

        BedrockItemRegistry registry = itemRegistry;
        BedrockPacket authoritativeContent;
        try {
            authoritativeContent = BedrockInventoryContentCodec.createPlayerInventory(state.inventory, registry);
        } catch (IllegalArgumentException exception) {
            // If a persisted item has no protocol definition, do not mutate the inventory.
            System.err.println("NEXORA: inventory transaction blocked because an item has no Bedrock definition");
            return;
        }

        int transactionType;
        try {
            transactionType = BedrockInventoryTransactionCodec.transactionType(packet);
        } catch (UnsupportedOperationException exception) {
            sendInventoryReconciliation(networkId, state, authoritativeContent);
            return;
        } catch (IllegalArgumentException exception) {
            state.rejected = true;
            throw new SecurityException("Malformed Bedrock InventoryTransaction header", exception);
        }
        if (transactionType == 3) {
            try {
                BedrockInventoryTransactionCodec.DecodedUseOnActor actor =
                        BedrockInventoryTransactionCodec.decodeUseOnActor(packet, registry);
                handleUseOnActorTransaction(networkId, state, actor);
            } catch (UnsupportedOperationException exception) {
                sendInventoryReconciliation(networkId, state, authoritativeContent);
            } catch (IllegalArgumentException exception) {
                state.rejected = true;
                throw new SecurityException("Malformed Bedrock ItemUseOnActor transaction", exception);
            }
            return;
        }
        if (transactionType == 4) {
            try {
                BedrockInventoryTransactionCodec.DecodedItemRelease release =
                        BedrockInventoryTransactionCodec.decodeItemRelease(packet, registry);
                handleItemRelease(networkId, state, release);
            } catch (UnsupportedOperationException exception) {
                sendInventoryReconciliation(networkId, state, authoritativeContent);
            } catch (IllegalArgumentException exception) {
                state.rejected = true;
                throw new SecurityException("Malformed Bedrock ItemRelease transaction", exception);
            }
            return;
        }

        BedrockInventoryTransactionCodec.DecodedTransaction decoded;
        try {
            decoded = BedrockInventoryTransactionCodec.decode(packet, registry);
        } catch (UnsupportedOperationException exception) {
            // Unsupported transaction variants are not interpreted; restore the client's prediction.
            List<BedrockPacket> reconciliation = new java.util.ArrayList<>();
            reconciliation.add(authoritativeContent);
            reconciliation.addAll(createEquipmentReconciliationPackets(state));
            sendPackets(networkId, reconciliation, true);
            return;
        } catch (IllegalArgumentException exception) {
            state.rejected = true;
            throw new SecurityException("Malformed Bedrock InventoryTransaction", exception);
        }

        if (decoded.actions().stream().anyMatch(action ->
                action.containerId() == BedrockInventoryContentCodec.PLAYER_UI_CONTAINER_ID
                        && action.slot() >= 28 && action.slot() <= 31)) {
            handleCraftingUiTransaction(networkId, state, decoded.actions());
            return;
        }

        if (state.openContainerWindowId != 0 && state.openContainerInventory != null
                && decoded.actions().stream().anyMatch(
                        action -> action.containerId() == state.openContainerWindowId)) {
            handleOpenContainerTransaction(networkId, state, decoded.actions());
            return;
        }

        InventoryTransactionService.Result result =
                inventoryTransactionService.applyPlayerInventoryAndUiTransaction(
                        state.inventory, state.equipment, decoded.actions(), armorSlotRegistry);
        if (result == InventoryTransactionService.Result.APPLIED) {
            authoritativeContent = BedrockInventoryContentCodec.createPlayerInventory(state.inventory, registry);
        }
        // Always reconcile client prediction, including stale, unsupported, and rejected actions.
        List<BedrockPacket> reconciliation = new java.util.ArrayList<>();
        reconciliation.add(authoritativeContent);
        reconciliation.addAll(createEquipmentReconciliationPackets(state));
        clearSpearChargeIfItemChanged(state);
        sendPackets(networkId, reconciliation, true);
    }

    private void handleUseOnActorTransaction(
            String networkId, SessionState attacker,
            BedrockInventoryTransactionCodec.DecodedUseOnActor transaction
    ) {
        if (!attacker.loginAuthenticated || !attacker.clientInitialized
                || attacker.dead || attacker.vitals.dead()
                || transaction.actionType() != 1 || transaction.hand() != 0
                || transaction.slot() != attacker.inventory.selectedHotbarSlot()) {
            return;
        }

        ItemStack weapon = attacker.inventory.heldItem().orElse(null);
        if (weapon == null) {
            if (transaction.item().isPresent()) return;
        } else if (!transaction.item().filter(weapon::equals).isPresent()) {
            return;
        }

        double originX = transaction.fromX() - attacker.positionX;
        double originY = transaction.fromY() - attacker.positionY;
        double originZ = transaction.fromZ() - attacker.positionZ;
        if (originX * originX + originY * originY + originZ * originZ > 2.25) return;

        long now = nanoTime.getAsLong();
        if (attacker.lastAttackNanos != Long.MIN_VALUE
                && now - attacker.lastAttackNanos < weaponAttackCooldownNanos(weapon)) return;

        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            String targetNetworkId = entry.getKey();
            SessionState target = entry.getValue();
            if (target == attacker || target.entityId != transaction.runtimeEntityId()) continue;

            // Never wait on another player's session lock while holding this one.
            // Simultaneous reciprocal attacks are safely dropped rather than deadlocking.
            if (!target.combatLock.tryLock()) return;
            try {
                if (!target.loginAuthenticated || !target.clientInitialized
                        || target.dead || target.vitals.dead()) return;
                if (target.lastCombatDamageNanos != Long.MIN_VALUE
                        && now - target.lastCombatDamageNanos < 500_000_000L) return;
                double dx = target.positionX - attacker.positionX;
                double dy = target.positionY - attacker.positionY;
                double dz = target.positionZ - attacker.positionZ;
                double distanceSquared = dx * dx + dy * dy + dz * dz;
                aiGuard.recordOutOfReachAttack(
                        attacker.identity == null ? networkId : attacker.identity.xuid(),
                        Math.sqrt(distanceSquared), maximumWeaponReach(weapon), now);
                if (!isWithinWeaponReach(weapon, distanceSquared)) return;
                int targetBlockX = (int) Math.floor(target.positionX);
                int targetBlockY = (int) Math.floor(target.positionY + 1.0);
                int targetBlockZ = (int) Math.floor(target.positionZ);
                if (!WorldRaycast.hasLineOfSight(world,
                        attacker.positionX, attacker.positionY + 1.62, attacker.positionZ,
                        targetBlockX, targetBlockY, targetBlockZ)) return;

                attacker.lastAttackNanos = now;
                target.lastCombatDamageNanos = now;
                broadcastPacketExcept(networkId,
                        BedrockAnimateCodec.createSwing(attacker.entityId, BedrockAnimateCodec.MAIN_HAND));
                PlayerPhysics.State targetPhysics = target.physicsState;
                var chargeEffect = consumeSpearCharge(attacker, weapon,
                        targetPhysics == null ? target.velocityX : targetPhysics.velocityX(),
                        targetPhysics == null ? target.velocityY : targetPhysics.velocityY(),
                        targetPhysics == null ? target.velocityZ : targetPhysics.velocityZ(), now);
                float baseDamage = chargeEffect != null && chargeEffect.hit()
                        ? chargeEffect.damage() : baseAttackDamage(weapon);
                ru.nexora.combat.EnchantmentCombatService.AttackEffect effect =
                        ru.nexora.combat.EnchantmentCombatService.resolveAttack(
                                baseDamage, weapon,
                                ru.nexora.combat.EnchantmentCombatService.TargetType.NORMAL,
                                attacker.physicsState == null ? 0.0f
                                        : (float) attacker.physicsState.fallDistance());
                List<ItemStack> targetArmor = target.equipment.armorSnapshot().stream()
                        .flatMap(Optional::stream).toList();
                float finalDamage = ru.nexora.combat.EnchantmentCombatService
                        .applyIncomingDamage(effect.damage(), targetArmor,
                                ru.nexora.combat.EnchantmentCombatService.DamageType.GENERIC,
                                effect.breachLevel());
                if (finalDamage > 0.0f) {
                    applyDamage(targetNetworkId, target, finalDamage,
                            target.lastInputTick, "death.attack.generic");
                    aiGuard.recordSuccessfulAttack(
                            attacker.identity == null ? networkId : attacker.identity.xuid(),
                            attacker.identity == null ? null : attacker.identity.displayName(),
                            target.identity == null ? targetNetworkId : target.identity.xuid(), now);
                }
                applyKnockback(targetNetworkId, target, attacker,
                        Math.max(effect.knockbackLevel(),
                                chargeEffect != null && chargeEffect.knockback() ? 1 : 0));
                applySpearLunge(networkId, attacker, weapon);
                if (effect.fireDurationSeconds() > 0) {
                    if (target.fireUntilNanos <= now) {
                        target.nextFireDamageNanos = now + 1_000_000_000L;
                    }
                    target.fireUntilNanos = Math.max(target.fireUntilNanos,
                            now + effect.fireDurationSeconds() * 1_000_000_000L);
                }

                List<ItemStack> armor = target.equipment.armorSnapshot().stream()
                        .flatMap(Optional::stream).toList();
                if (!armor.isEmpty()) {
                    double[] rolls = new double[armor.size()];
                    int[] damageRolls = new int[armor.size()];
                    java.util.concurrent.ThreadLocalRandom random =
                            java.util.concurrent.ThreadLocalRandom.current();
                    for (int i = 0; i < armor.size(); i++) {
                        rolls[i] = random.nextDouble();
                        damageRolls[i] = random.nextInt(1, 5);
                    }
                    int reflected = ru.nexora.combat.EnchantmentCombatService
                            .resolveThornsDamage(armor, rolls, damageRolls);
                    if (reflected > 0 && !attacker.vitals.dead()) {
                        applyDamage(networkId, attacker, reflected,
                                attacker.lastInputTick, "death.attack.thorns");
                    }
                }
                return;
            } finally {
                target.combatLock.unlock();
            }
        }
        handleLivingActorAttack(networkId, attacker, transaction, weapon, now);
    }

    private void handleLivingActorAttack(
            String attackerNetworkId, SessionState attacker,
            BedrockInventoryTransactionCodec.DecodedUseOnActor transaction,
            ItemStack weapon, long nowNanos
    ) {
        WorldLivingEntity target = world.livingEntities().find(transaction.runtimeEntityId()).orElse(null);
        if (target == null) return;
        double dx = target.x() - attacker.positionX;
        double dy = target.y() - attacker.positionY;
        double dz = target.z() - attacker.positionZ;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        aiGuard.recordOutOfReachAttack(
                attacker.identity == null ? attackerNetworkId : attacker.identity.xuid(),
                Math.sqrt(distanceSquared), maximumWeaponReach(weapon), nowNanos);
        if (!isWithinWeaponReach(weapon, distanceSquared)) return;
        int targetBlockX = (int) Math.floor(target.x());
        int targetBlockY = (int) Math.floor(target.y() + 1.0);
        int targetBlockZ = (int) Math.floor(target.z());
        if (!WorldRaycast.hasLineOfSight(world,
                attacker.positionX, attacker.positionY + 1.62, attacker.positionZ,
                targetBlockX, targetBlockY, targetBlockZ)) return;

        attacker.lastAttackNanos = nowNanos;
        var chargeEffect = consumeSpearCharge(attacker, weapon,
                target.velocityX(), target.velocityY(), target.velocityZ(), nowNanos);
        ru.nexora.combat.EnchantmentCombatService.TargetType targetType = switch (target.family()) {
            case NORMAL -> ru.nexora.combat.EnchantmentCombatService.TargetType.NORMAL;
            case UNDEAD -> ru.nexora.combat.EnchantmentCombatService.TargetType.UNDEAD;
            case ARTHROPOD -> ru.nexora.combat.EnchantmentCombatService.TargetType.ARTHROPOD;
        };
        ru.nexora.combat.EnchantmentCombatService.AttackEffect effect =
                ru.nexora.combat.EnchantmentCombatService.resolveAttack(
                        chargeEffect != null && chargeEffect.hit()
                                ? chargeEffect.damage() : baseAttackDamage(weapon),
                        weapon, targetType,
                        attacker.physicsState == null ? 0.0f
                                : (float) attacker.physicsState.fallDistance());
        var damage = world.livingEntities().damage(target.runtimeId(), effect.damage()).orElse(null);
        if (damage == null) return;
        aiGuard.recordSuccessfulAttack(
                attacker.identity == null ? attackerNetworkId : attacker.identity.xuid(),
                attacker.identity == null ? null : attacker.identity.displayName(),
                "entity:" + target.runtimeId(), nowNanos);
        broadcastPacketExcept(attackerNetworkId,
                BedrockAnimateCodec.createSwing(attacker.entityId, BedrockAnimateCodec.MAIN_HAND));
        applySpearLunge(attackerNetworkId, attacker, weapon);
        if (damage.killed()) {
            grantMobRewards(attackerNetworkId, attacker, target, weapon == null ? 0 : weapon.enchantments().getOrDefault("minecraft:looting", 0));
            cleanupLivingEntityRuntimeState(target.runtimeId());
            broadcastLivingActorPacket(BedrockAddItemActorCodec.createRemoveActor(target.uniqueId()));
            return;
        }
        livingEntityAggroTargets.put(target.runtimeId(), attackerNetworkId);
        if (effect.fireDurationSeconds() > 0) {
            livingEntityKillCredit.put(target.runtimeId(), new MobKillCredit(attackerNetworkId,
                    weapon.enchantments().getOrDefault("minecraft:looting", 0)));
        }
        WorldLivingEntity updated = damage.current();
        broadcastLivingActorPacket(BedrockVitalsCodec.createActorHealthUpdate(
                updated.runtimeId(), updated.health(), updated.maxHealth(),
                Math.max(0, attacker.lastInputTick)));
        if ("minecraft:enderman".equals(updated.identifier())) {
            teleportEndermanAway(updated, attacker.positionX, attacker.positionY, attacker.positionZ,
                    Math.max(0, nowNanos / 1_000_000L), attacker.lastInputTick);
        }
        if (effect.fireDurationSeconds() > 0) {
            updated = world.livingEntities().ignite(updated.runtimeId(), effect.fireDurationSeconds(),
                    Math.max(0, nowNanos / 1_000_000L)).orElse(updated);
        }
        int chargeKnockbackLevel = Math.max(effect.knockbackLevel(),
                chargeEffect != null && chargeEffect.knockback() ? 1 : 0);
        if (chargeKnockbackLevel > 0) {
            double horizontal = Math.hypot(dx, dz);
            if (horizontal > 0.0001) {
                double strength = 0.4 + 0.1 * chargeKnockbackLevel;
                updated = world.livingEntities().setVelocity(updated.runtimeId(),
                        updated.velocityX() + dx / horizontal * strength,
                        Math.max(updated.velocityY(), 0.1),
                        updated.velocityZ() + dz / horizontal * strength).orElse(updated);
                broadcastLivingActorPacket(BedrockActorMotionCodec.createSetActorMotion(
                        updated.runtimeId(), updated.velocityX(), updated.velocityY(),
                        updated.velocityZ(), Math.max(0, attacker.lastInputTick)));
            }
        }
    }

    private void grantMobRewards(
            String networkId, SessionState killer, WorldLivingEntity mob, int lootingLevel
    ) {
        int boundedLooting = Math.max(0, Math.min(3, lootingLevel));
        int bonusRoll = boundedLooting == 0 ? 0
                : java.util.concurrent.ThreadLocalRandom.current().nextInt(boundedLooting + 1);
        ru.nexora.combat.MobLootService.Loot loot =
                ru.nexora.combat.MobLootService.dropsFor(mob.identifier(), boundedLooting, bonusRoll);
        if (loot.experienceMilliPoints() > 0) {
            awardExperienceMilli(networkId, killer, loot.experienceMilliPoints());
        }
        String ownerXuid = killer.identity == null ? null : killer.identity.xuid();
        long nowMillis = System.currentTimeMillis();
        for (int i = 0; i < loot.drops().size(); i++) {
            ItemStack drop = loot.drops().get(i);
            if (itemRegistry.findByIdentifier(drop.itemId()) == null) continue;
            WorldItemEntity entity = null;
            try {
                entity = world.itemEntities().spawn(drop,
                        mob.x(), mob.y() + 0.25, mob.z(),
                        (i - (loot.drops().size() - 1) / 2.0) * 0.12, 0.18, 0.08,
                        nowMillis, WorldItemEntityManager.DEFAULT_PICKUP_DELAY_MILLIS, ownerXuid);
                broadcastItemActor(entity, BedrockAddItemActorCodec.createAddItemActor(entity, itemRegistry));
            } catch (RuntimeException exception) {
                if (entity != null) {
                    try {
                        world.itemEntities().remove(entity.runtimeId());
                    } catch (RuntimeException rollbackFailure) {
                        exception.addSuppressed(rollbackFailure);
                    }
                }
                System.err.println("NEXORA: unable to create mob loot for " + mob.identifier()
                        + ": " + exception.getMessage());
            }
        }
    }

    private void awardExperienceMilli(String networkId, SessionState state, int amountMilli) {
        if (amountMilli <= 0 || !state.loginAuthenticated || state.dead || state.vitals.dead()) return;
        long previous = (long) state.experiencePoints * 1000L + state.experienceRemainderMilli;
        long updated = Math.min(2_000_000_000_000L, previous + amountMilli);
        if (updated == previous) return;
        state.experiencePoints = (int) (updated / 1000L);
        state.experienceRemainderMilli = (int) (updated % 1000L);
        try {
            savePlayer(state);
        } catch (IOException | RuntimeException exception) {
            state.experiencePoints = (int) (previous / 1000L);
            state.experienceRemainderMilli = (int) (previous % 1000L);
            System.err.println("NEXORA: mob XP reward could not be persisted for " + networkId
                    + ": " + exception.getMessage());
            return;
        }
        sendPacket(networkId, createPlayerAttributes(state, Math.max(0, state.lastInputTick)), true);
    }

    private void tickWorldLivingActors(long tick) {
        long nowMillis = Math.max(0, nanoTime.getAsLong() / 1_000_000L);
        for (WorldLivingEntityManager.DamageResult result : world.livingEntities().tickFire(nowMillis)) {
            WorldLivingEntity entity = result.current();
            if (result.killed()) {
                MobKillCredit credit = livingEntityKillCredit.get(entity.runtimeId());
                cleanupLivingEntityRuntimeState(entity.runtimeId());
                SessionState killer = credit == null ? null : sessions.get(credit.networkId());
                if (killer != null) {
                    killer.combatLock.lock();
                    try {
                        synchronized (killer) {
                            grantMobRewards(credit.networkId(), killer, entity, credit.lootingLevel());
                        }
                    } finally {
                        killer.combatLock.unlock();
                    }
                }
                broadcastLivingActorPacket(BedrockAddItemActorCodec.createRemoveActor(entity.uniqueId()));
            } else {
                broadcastLivingActorPacket(BedrockVitalsCodec.createActorHealthUpdate(
                        entity.runtimeId(), entity.health(), entity.maxHealth(), Math.max(0, tick)));
            }
        }

        long previousTick = lastLivingAiTickMillis.get();
        if (previousTick != Long.MIN_VALUE && nowMillis - previousTick < 50) return;
        if (!lastLivingAiTickMillis.compareAndSet(previousTick, nowMillis)) return;
        long aiStartedNanos = nanoTime.getAsLong();
        try {
            tickLivingEntityAi(nowMillis, tick);
        } catch (RuntimeException failure) {
            ru.nexora.ai.NexoraAiDiagnostics diagnostics = aiDiagnostics;
            if (diagnostics != null) diagnostics.recordFailure(failure);
            throw failure;
        } finally {
            ru.nexora.ai.NexoraAiDiagnostics diagnostics = aiDiagnostics;
            if (diagnostics != null) {
                diagnostics.recordSlowOperation("mob-ai",
                        Math.max(0L, nanoTime.getAsLong() - aiStartedNanos), 50_000_000L);
            }
        }
    }

    private void tickLivingEntityAi(long nowMillis, long tick) {
        List<ru.nexora.combat.LivingEntityAiService.PlayerTarget> targets = new ArrayList<>();
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState player = entry.getValue();
            if (!player.loginAuthenticated || !player.clientInitialized || !player.positionInitialized
                    || player.dead || player.vitals.dead()) continue;
            targets.add(new ru.nexora.combat.LivingEntityAiService.PlayerTarget(
                    entry.getKey(), player.positionX, player.positionY, player.positionZ, true));
        }
        List<ru.nexora.world.MobSpawnerManager.PlayerPosition> spawnerPlayers = new ArrayList<>();
        for (ru.nexora.combat.LivingEntityAiService.PlayerTarget player : targets) {
            spawnerPlayers.add(new ru.nexora.world.MobSpawnerManager.PlayerPosition(
                    player.x(), player.y(), player.z()));
        }
        world.mobSpawners().tick(world, Math.max(0L, nowMillis / 50L), spawnerPlayers, request -> {
            try {
                spawnLivingEntity(request.identifier(), mobMaxHealth(request.identifier()),
                        request.x(), request.y(), request.z());
                return true;
            } catch (IllegalStateException capacityReached) {
                return false;
            }
        });
        for (WorldLivingEntity mob : world.livingEntities().snapshot()) {
            if (!ru.nexora.world.WorldPathfinder.isMobFootprintLoaded(world, mob.x(), mob.z())) continue;
            ru.nexora.combat.LivingEntityAiService.PlayerTarget nearestPlayer =
                    ru.nexora.combat.LivingEntityAiService.nearestAlivePlayer(mob, targets);
            if ("minecraft:creeper".equals(mob.identifier())
                    && tickCreeperBehavior(mob, nearestPlayer, nowMillis, tick)) {
                mobStuckTracker.clear(mob.runtimeId());
                continue;
            }
            if (tickRangedMobBehavior(mob, nearestPlayer, nowMillis)) {
                mobStuckTracker.clear(mob.runtimeId());
                continue;
            }
            double nearestPlayerDistanceSquared = Double.POSITIVE_INFINITY;
            if (nearestPlayer != null) {
                double dx = nearestPlayer.x() - mob.x();
                double dy = nearestPlayer.y() - mob.y();
                double dz = nearestPlayer.z() - mob.z();
                nearestPlayerDistanceSquared = dx * dx + dy * dy + dz * dz;
            }
            boolean hasActiveTarget = livingEntityAggroTargets.containsKey(mob.runtimeId());
            if (!mobAiLoadController.shouldUpdate(mob.runtimeId(), tick,
                    nearestPlayerDistanceSquared, hasActiveTarget)) {
                continue;
            }
            long nextAttackAt = nextMobAttackMillis.getOrDefault(mob.runtimeId(), 0L);
            ru.nexora.combat.LivingEntityAiService.Decision decision =
                    livingEntityAi.decide(mob, targets, nowMillis, nextAttackAt,
                            livingEntityAggroTargets.get(mob.runtimeId()), nearestPlayer);
            if (decision.attack()) {
                mobStuckTracker.clear(mob.runtimeId());
                if (damagePlayerFromMob(decision.targetNetworkId(), mob, decision.attackDamage(), tick)) {
                    nextMobAttackMillis.put(mob.runtimeId(),
                            nowMillis + ru.nexora.combat.LivingEntityAiService.ATTACK_COOLDOWN_MILLIS);
                }
                continue;
            }
            if (!decision.moving()) {
                mobStuckTracker.clear(mob.runtimeId());
                continue;
            }
            double nextX = mob.x() + decision.moveX();
            double nextY = mob.y() + decision.moveY();
            double nextZ = mob.z() + decision.moveZ();
            if (!ru.nexora.world.WorldPathfinder.isMobFootprintLoaded(world, nextX, nextZ)
                    || !WorldCollision.canMove(world, mob.x(), mob.y(), mob.z(), nextX, nextY, nextZ)) {
                double desiredX = mob.x() + decision.moveX() * 8.0;
                double desiredZ = mob.z() + decision.moveZ() * 8.0;
                long pathStartedNanos = nanoTime.getAsLong();
                WorldPathfinder.Step waypoint;
                try {
                    waypoint = mobPathCache.findNextStep(mob.runtimeId(), world,
                            mob.x(), mob.y(), mob.z(), desiredX, desiredZ, nowMillis).orElse(null);
                } finally {
                    ru.nexora.ai.NexoraAiDiagnostics diagnostics = aiDiagnostics;
                    if (diagnostics != null) {
                        diagnostics.recordSlowOperation("mob-pathfinding",
                                Math.max(0L, nanoTime.getAsLong() - pathStartedNanos), 5_000_000L);
                    }
                }
                if (waypoint == null) {
                    mobStuckTracker.recordBlockedAttempt(mob.runtimeId(), mob.x(), mob.y(), mob.z(), nowMillis);
                    continue;
                }
                double dx = waypoint.x() - mob.x();
                double dz = waypoint.z() - mob.z();
                double length = Math.hypot(dx, dz);
                if (length < 0.0001) {
                    mobStuckTracker.recordBlockedAttempt(mob.runtimeId(), mob.x(), mob.y(), mob.z(), nowMillis);
                    continue;
                }
                double step = Math.min(
                        ru.nexora.combat.LivingEntityAiService.movementSpeedFor(mob.identifier()), length);
                nextX = mob.x() + dx / length * step;
                nextZ = mob.z() + dz / length * step;
                if (!WorldCollision.canMove(world, mob.x(), mob.y(), mob.z(), nextX, nextY, nextZ)) {
                    mobStuckTracker.recordBlockedAttempt(mob.runtimeId(), mob.x(), mob.y(), mob.z(), nowMillis);
                    continue;
                }
            }
            WorldLivingEntity moved = world.livingEntities().move(
                    mob.runtimeId(), nextX, nextY, nextZ).orElse(null);
            if (moved == null) {
                mobStuckTracker.clear(mob.runtimeId());
                continue;
            }
            mobStuckTracker.clear(mob.runtimeId());
            float yaw = (float) Math.toDegrees(Math.atan2(-decision.moveX(), decision.moveZ()));
            broadcastLivingActorPacket(BedrockMoveActorAbsoluteCodec.create(
                    moved.runtimeId(), moved.x(), moved.y(), moved.z(), 0.0f, yaw, yaw,
                    WorldCollision.isOnGround(world, moved.x(), moved.y(), moved.z())));
        }
        tickMobArrows(tick);
    }

    private static float mobMaxHealth(String identifier) {
        return switch (identifier) {
            case "minecraft:chicken", "minecraft:cod", "minecraft:tropical_fish",
                    "minecraft:salmon", "minecraft:pufferfish", "minecraft:tadpole",
                    "minecraft:bat", "minecraft:parrot", "minecraft:allay" -> 4.0f;
            case "minecraft:rabbit", "minecraft:cat", "minecraft:ocelot",
                    "minecraft:wolf", "minecraft:bee", "minecraft:fox" -> 8.0f;
            case "minecraft:pig", "minecraft:cow", "minecraft:sheep", "minecraft:goat",
                    "minecraft:mooshroom", "minecraft:armadillo", "minecraft:camel",
                    "minecraft:donkey", "minecraft:horse", "minecraft:mule",
                    "minecraft:llama", "minecraft:trader_llama", "minecraft:turtle",
                    "minecraft:axolotl", "minecraft:frog", "minecraft:strider",
                    "minecraft:glow_squid", "minecraft:squid" -> 10.0f;
            case "minecraft:cave_spider" -> 12.0f;
            case "minecraft:spider", "minecraft:polar_bear", "minecraft:panda",
                    "minecraft:blaze", "minecraft:bogged" -> 16.0f;
            case "minecraft:creeper", "minecraft:skeleton", "minecraft:stray",
                    "minecraft:husk", "minecraft:drowned", "minecraft:zombie",
                    "minecraft:zombie_villager", "minecraft:witch", "minecraft:piglin",
                    "minecraft:pillager", "minecraft:vindicator", "minecraft:evoker",
                    "minecraft:phantom", "minecraft:slime", "minecraft:magma_cube",
                    "minecraft:shulker", "minecraft:wither_skeleton" -> 20.0f;
            case "minecraft:enderman", "minecraft:ravager", "minecraft:hoglin",
                    "minecraft:zoglin", "minecraft:warden" -> 40.0f;
            case "minecraft:ghast" -> 10.0f;
            case "minecraft:iron_golem", "minecraft:elder_guardian" -> 100.0f;
            case "minecraft:wither" -> 300.0f;
            default -> 20.0f;
        };
    }

    private boolean tickRangedMobBehavior(
            WorldLivingEntity mob,
            ru.nexora.combat.LivingEntityAiService.PlayerTarget nearest,
            long nowMillis
    ) {
        if (!isRangedMob(mob.identifier())) return false;
        if (nearest == null) return false;
        double dx = nearest.x() - mob.x();
        double dy = nearest.y() + 1.0 - (mob.y() + 1.2);
        double dz = nearest.z() - mob.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 4.0 || distance > 16.0) return false;
        if (!WorldRaycast.hasLineOfSight(world, mob.x(), mob.y() + 1.2, mob.z(),
                (int) Math.floor(nearest.x()), (int) Math.floor(nearest.y() + 1.0),
                (int) Math.floor(nearest.z()))) {
            return false;
        }
        if (nowMillis < nextMobAttackMillis.getOrDefault(mob.runtimeId(), 0L)) return true;
        if (mobArrows.size() >= 2_000) return true;
        double speed = 1.8;
        double aimY = dy + distance * 0.025;
        double aimLength = Math.sqrt(dx * dx + aimY * aimY + dz * dz);
        if (aimLength < 0.0001) return true;
        long runtimeId = nextMobArrowRuntimeId.getAndIncrement();
        if (runtimeId <= 0 || runtimeId == Long.MAX_VALUE) {
            nextMobArrowRuntimeId.set(4_000_000L);
            runtimeId = nextMobArrowRuntimeId.getAndIncrement();
        }
        ru.nexora.combat.ArrowProjectile arrow = new ru.nexora.combat.ArrowProjectile(
                -runtimeId, runtimeId, "mob:" + mob.runtimeId(),
                mob.x(), mob.y() + 1.2, mob.z(),
                dx / aimLength * speed, aimY / aimLength * speed, dz / aimLength * speed, 0);
        mobArrows.put(runtimeId, arrow);
        broadcastLivingActorPacket(BedrockArrowActorCodec.createSpawn(arrow));
        nextMobAttackMillis.put(mob.runtimeId(), nowMillis + 2_000L);
        return true;
    }

    private static boolean isRangedMob(String identifier) {
        return switch (identifier) {
            case "minecraft:skeleton", "minecraft:stray", "minecraft:wither_skeleton",
                    "minecraft:bogged", "minecraft:pillager" -> true;
            default -> false;
        };
    }

    private void tickMobArrows(long tick) {
        for (var entry : mobArrows.entrySet()) {
            ru.nexora.combat.ArrowProjectile current = entry.getValue();
            ru.nexora.combat.ArrowProjectile next = current.advance();
            boolean removed = next.expired();
            if (!removed) {
                int blockX = (int) Math.floor(next.x());
                int blockY = (int) Math.floor(next.y());
                int blockZ = (int) Math.floor(next.z());
                removed = blockY < WorldChunk.MIN_Y || blockY > WorldChunk.MAX_Y
                        || !world.isChunkLoadedAt(blockX, blockZ)
                        || world.getBlock(blockX, blockY, blockZ) != BlockType.AIR;
            }
            if (!removed) {
                for (Map.Entry<String, SessionState> playerEntry : sessions.entrySet()) {
                    SessionState player = playerEntry.getValue();
                    if (next.ageTicks() <= 5 && current.ownerNetworkId().equals(playerEntry.getKey())) continue;
                    if (!player.loginAuthenticated || !player.clientInitialized
                            || !player.positionInitialized || player.dead || player.vitals.dead()) continue;
                    double distanceSquared = distanceToSegmentSquared(
                            player.positionX, player.positionY + 0.9, player.positionZ,
                            current.x(), current.y(), current.z(), next.x(), next.y(), next.z());
                    if (distanceSquared > 0.55 * 0.55) continue;
                    player.combatLock.lock();
                    try {
                        synchronized (player) {
                            if (player.dead || player.vitals.dead()) continue;
                            List<ItemStack> armor = player.equipment.armorSnapshot().stream()
                                    .flatMap(Optional::stream).toList();
                            float damage = ru.nexora.combat.EnchantmentCombatService
                                    .applyIncomingDamage(4.0f, armor,
                                            ru.nexora.combat.EnchantmentCombatService.DamageType.PROJECTILE);
                            if (damage > 0.0f) {
                                applyDamage(playerEntry.getKey(), player, damage, tick, "death.attack.arrow");
                            }
                        }
                    } finally {
                        player.combatLock.unlock();
                    }
                    removed = true;
                    break;
                }
            }
            if (removed) {
                if (mobArrows.remove(entry.getKey(), current)) {
                    broadcastLivingActorPacket(BedrockAddItemActorCodec.createRemoveActor(current.uniqueId()));
                }
            } else if (mobArrows.replace(entry.getKey(), current, next)) {
                broadcastLivingActorPacket(BedrockArrowActorCodec.createMove(next, Math.max(0, tick)));
            }
        }
    }

    private static double distanceToSegmentSquared(
            double px, double py, double pz,
            double ax, double ay, double az, double bx, double by, double bz
    ) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double lengthSquared = dx * dx + dy * dy + dz * dz;
        double t = lengthSquared < 1.0e-12 ? 0.0
                : Math.max(0.0, Math.min(1.0,
                        ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / lengthSquared));
        double ox = px - (ax + t * dx);
        double oy = py - (ay + t * dy);
        double oz = pz - (az + t * dz);
        return ox * ox + oy * oy + oz * oz;
    }

    /** Endermen blink away from a player who damages them, using collision-safe landing spots. */
    private void teleportEndermanAway(
            WorldLivingEntity enderman, double attackerX, double attackerY, double attackerZ,
            long nowMillis, long tick
    ) {
        if (nowMillis < endermanTeleportCooldownMillis.getOrDefault(enderman.runtimeId(), 0L)) return;
        double awayX = enderman.x() - attackerX;
        double awayZ = enderman.z() - attackerZ;
        double length = Math.hypot(awayX, awayZ);
        if (length < 0.001) { awayX = 1.0; awayZ = 0.0; length = 1.0; }
        awayX /= length;
        awayZ /= length;
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 24; attempt++) {
            double distance = random.nextDouble(8.0, 13.0);
            double candidateX = enderman.x() + awayX * distance + random.nextDouble(-3.0, 3.0);
            double candidateZ = enderman.z() + awayZ * distance + random.nextDouble(-3.0, 3.0);
            int baseY = (int) Math.floor(enderman.y());
            for (int offset = 2; offset >= -2; offset--) {
                double candidateY = baseY + offset;
                int blockX = (int) Math.floor(candidateX);
                int blockY = (int) Math.floor(candidateY);
                int blockZ = (int) Math.floor(candidateZ);
                if (blockY <= WorldChunk.MIN_Y || blockY + 2 > WorldChunk.MAX_Y
                        || !ru.nexora.world.WorldPathfinder.isMobFootprintLoaded(world, candidateX, candidateZ)) continue;
                if (world.getBlock(blockX, blockY - 1, blockZ) == BlockType.AIR
                        || world.getBlock(blockX, blockY, blockZ) != BlockType.AIR
                        || world.getBlock(blockX, blockY + 1, blockZ) != BlockType.AIR
                        || world.getBlock(blockX, blockY + 2, blockZ) != BlockType.AIR) continue;
                if (!WorldCollision.canOccupy(world, candidateX, candidateY, candidateZ)) continue;
                WorldLivingEntity moved = world.livingEntities().move(
                        enderman.runtimeId(), candidateX, candidateY, candidateZ).orElse(null);
                if (moved == null) return;
                endermanTeleportCooldownMillis.put(enderman.runtimeId(), nowMillis + 1_000L);
                float yaw = (float) Math.toDegrees(Math.atan2(-awayX, awayZ));
                broadcastLivingActorPacket(BedrockMoveActorAbsoluteCodec.create(
                        moved.runtimeId(), moved.x(), moved.y(), moved.z(), 0.0f, yaw, yaw,
                        true, true));
                return;
            }
        }
        endermanTeleportCooldownMillis.put(enderman.runtimeId(), nowMillis + 250L);
    }

    private boolean tickCreeperBehavior(
            WorldLivingEntity creeper,
            ru.nexora.combat.LivingEntityAiService.PlayerTarget nearest,
            long nowMillis,
            long tick
    ) {
        if (nearest == null) {
            creeperFuseStartedMillis.remove(creeper.runtimeId());
            return false;
        }
        double dx = nearest.x() - creeper.x();
        double dy = nearest.y() - creeper.y();
        double dz = nearest.z() - creeper.z();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared > 3.0 * 3.0) {
            creeperFuseStartedMillis.remove(creeper.runtimeId());
            return false;
        }

        long fuseStarted = creeperFuseStartedMillis.computeIfAbsent(
                creeper.runtimeId(), ignored -> nowMillis);
        if (nowMillis - fuseStarted < 1_500L) return true;

        creeperFuseStartedMillis.remove(creeper.runtimeId());
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState player = entry.getValue();
            player.combatLock.lock();
            try {
                synchronized (player) {
                    if (!player.loginAuthenticated || !player.clientInitialized
                            || player.dead || player.vitals.dead() || !player.positionInitialized) continue;
                    double px = player.positionX - creeper.x();
                    double py = player.positionY - creeper.y();
                    double pz = player.positionZ - creeper.z();
                    double distance = Math.sqrt(px * px + py * py + pz * pz);
                    if (distance >= 3.5) continue;
                    float rawDamage = (float) (12.0 * (1.0 - distance / 3.5));
                    List<ItemStack> armor = player.equipment.armorSnapshot().stream()
                            .flatMap(Optional::stream).toList();
                    float damage = ru.nexora.combat.EnchantmentCombatService
                            .applyIncomingDamage(rawDamage, armor,
                                    ru.nexora.combat.EnchantmentCombatService.DamageType.EXPLOSION);
                    if (damage > 0.0f) {
                        applyDamage(entry.getKey(), player, damage, tick, "death.attack.explosion");
                    }
                }
            } finally {
                player.combatLock.unlock();
            }
        }
        if (world.livingEntities().remove(creeper.runtimeId())) {
            cleanupLivingEntityRuntimeState(creeper.runtimeId());
            broadcastLivingActorPacket(
                    BedrockAddItemActorCodec.createRemoveActor(creeper.uniqueId()));
        }
        return true;
    }

    private boolean damagePlayerFromMob(String networkId, WorldLivingEntity mob, float amount, long tick) {
        SessionState target = sessions.get(networkId);
        if (target == null) return false;
        target.combatLock.lock();
        try {
            synchronized (target) {
                if (!target.loginAuthenticated || !target.clientInitialized
                        || target.dead || target.vitals.dead()) return false;
                double rangeX = target.positionX - mob.x();
                double rangeY = target.positionY - mob.y();
                double rangeZ = target.positionZ - mob.z();
                double attackRange =
                        ru.nexora.combat.LivingEntityAiService.attackRangeFor(mob.identifier());
                if (rangeX * rangeX + rangeY * rangeY + rangeZ * rangeZ
                        > attackRange * attackRange) return false;
                int targetBlockX = (int) Math.floor(target.positionX);
                int targetBlockY = (int) Math.floor(target.positionY + 1.0);
                int targetBlockZ = (int) Math.floor(target.positionZ);
                if (!WorldRaycast.hasLineOfSight(world, mob.x(), mob.y() + 1.2, mob.z(),
                        targetBlockX, targetBlockY, targetBlockZ)) return false;
                List<ItemStack> armor = target.equipment.armorSnapshot().stream()
                        .flatMap(Optional::stream).toList();
                float mitigatedDamage = ru.nexora.combat.EnchantmentCombatService
                        .applyIncomingDamage(amount, armor,
                                ru.nexora.combat.EnchantmentCombatService.DamageType.GENERIC);
                if (mitigatedDamage > 0.0f) {
                    applyDamage(networkId, target, mitigatedDamage, tick, "death.attack.mob");
                }
                if (!target.vitals.dead() && !armor.isEmpty()) {
                    double[] rolls = new double[armor.size()];
                    int[] damageRolls = new int[armor.size()];
                    java.util.concurrent.ThreadLocalRandom random =
                            java.util.concurrent.ThreadLocalRandom.current();
                    for (int i = 0; i < armor.size(); i++) {
                        rolls[i] = random.nextDouble();
                        damageRolls[i] = random.nextInt(1, 5);
                    }
                    int reflected = ru.nexora.combat.EnchantmentCombatService
                            .resolveThornsDamage(armor, rolls, damageRolls);
                    if (reflected > 0) {
                        if (reflected >= mob.health()) {
                            livingEntityKillCredit.put(mob.runtimeId(), new MobKillCredit(networkId, 0));
                        }
                        var thornsDamage = world.livingEntities().damage(mob.runtimeId(), reflected).orElse(null);
                        if (thornsDamage != null) {
                            if (thornsDamage.killed()) {
                                grantMobRewards(networkId, target, mob, 0);
                                cleanupLivingEntityRuntimeState(mob.runtimeId());
                                broadcastLivingActorPacket(
                                        BedrockAddItemActorCodec.createRemoveActor(mob.uniqueId()));
                            } else {
                                WorldLivingEntity thornsTarget = thornsDamage.current();
                                broadcastLivingActorPacket(BedrockVitalsCodec.createActorHealthUpdate(
                                        thornsTarget.runtimeId(), thornsTarget.health(),
                                        thornsTarget.maxHealth(), Math.max(0, tick)));
                            }
                        }
                    }
                }
                double dx = target.positionX - mob.x();
                double dz = target.positionZ - mob.z();
                double length = Math.hypot(dx, dz);
                if (length > 0.0001) {
                    double vx = dx / length * 0.35;
                    double vz = dz / length * 0.35;
                    if (target.physicsState != null) {
                        PlayerPhysics.State current = target.physicsState;
                        target.physicsState = new PlayerPhysics.State(
                                current.x(), current.y(), current.z(),
                                current.velocityX() + vx, Math.max(current.velocityY(), 0.12),
                                current.velocityZ() + vz, current.onGround(),
                                current.fallDistance(), current.landedDistance());
                        sendMovementCorrection(networkId, target, tick);
                    } else {
                        sendPacket(networkId, BedrockActorMotionCodec.createSetActorMotion(
                                target.entityId, vx, 0.12, vz, Math.max(0, tick)), true);
                    }
                }
                return !target.vitals.dead()
                        && world.livingEntities().find(mob.runtimeId()).isPresent();
            }
        } finally {
            target.combatLock.unlock();
        }
    }

    private void sendExistingLivingActors(String networkId) {
        for (ru.nexora.combat.ArrowProjectile arrow : mobArrows.values()) {
            try {
                sendPacket(networkId, BedrockArrowActorCodec.createSpawn(arrow), true);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to send existing arrow to " + networkId
                        + ": " + exception.getMessage());
            }
        }
        for (WorldLivingEntity entity : world.livingEntities().snapshot()) {
            try {
                sendPacket(networkId, BedrockAddActorCodec.createAddActor(entity), true);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to send existing living actor to " + networkId
                        + ": " + exception.getMessage());
            }
        }
    }

    private void broadcastLivingActorPacket(BedrockPacket packet) {
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            if (entry.getValue().clientInitialized) sendPacket(entry.getKey(), packet, true);
        }
    }

    /** Spawns a server-owned living actor and publishes it to every initialized client. */
    public WorldLivingEntity spawnLivingEntity(
            String identifier, WorldLivingEntity.Family family,
            float maxHealth, double x, double y, double z
    ) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || y < WorldChunk.MIN_Y || y > WorldChunk.MAX_Y) {
            throw new IllegalArgumentException("Living actor position is outside supported bounds");
        }
        world.getChunk(Math.floorDiv((int) Math.floor(x), 16),
                Math.floorDiv((int) Math.floor(z), 16));
        WorldLivingEntity entity = world.livingEntities().spawn(identifier, family, maxHealth, x, y, z);
        broadcastLivingActorPacket(BedrockAddActorCodec.createAddActor(entity));
        return entity;
    }

    /** Spawns a living actor and infers its Smite/Bane family from the vanilla identifier. */
    public WorldLivingEntity spawnLivingEntity(
            String identifier, float maxHealth, double x, double y, double z
    ) {
        return spawnLivingEntity(identifier, WorldLivingEntity.familyForIdentifier(identifier),
                maxHealth, x, y, z);
    }

    /** Removes a server-owned living actor and despawns it for all initialized clients. */
    public boolean despawnLivingEntity(long runtimeId) {
        WorldLivingEntity entity = world.livingEntities().find(runtimeId).orElse(null);
        if (entity == null || !world.livingEntities().remove(runtimeId)) return false;
        cleanupLivingEntityRuntimeState(runtimeId);
        broadcastLivingActorPacket(BedrockAddItemActorCodec.createRemoveActor(entity.uniqueId()));
        return true;
    }

    /** Clears every transient subsystem reference when a mob dies or is despawned. */
    private void cleanupLivingEntityRuntimeState(long runtimeId) {
        nextMobAttackMillis.remove(runtimeId);
        mobPathCache.remove(runtimeId);
        mobStuckTracker.clear(runtimeId);
        creeperFuseStartedMillis.remove(runtimeId);
        endermanTeleportCooldownMillis.remove(runtimeId);
        livingEntityAggroTargets.remove(runtimeId);
        livingEntityKillCredit.remove(runtimeId);
        for (var entry : mobArrows.entrySet()) {
            if (entry.getValue().ownerNetworkId().equals("mob:" + runtimeId)
                    && mobArrows.remove(entry.getKey(), entry.getValue())) {
                broadcastLivingActorPacket(
                        BedrockAddItemActorCodec.createRemoveActor(entry.getValue().uniqueId()));
            }
        }
    }

    /**
     * Lunge is triggered by a successful spear jab. Exhaustion converts to saturation
     * and then hunger using the same survival rules as normal movement.
     *
     * The current world model has no water blocks or item durability state, so those
     * two vanilla Lunge restrictions/costs cannot yet be represented here.
     */
    private void applySpearLunge(String networkId, SessionState attacker, ItemStack weapon) {
        if (weapon == null || !weapon.itemId().endsWith("_spear")
                || attacker.vitals.hunger() <= 5 || attacker.physicsState == null) return;
        int level = weapon.enchantments().getOrDefault("minecraft:lunge", 0);
        if (level <= 0) return;

        PlayerPhysics.State current = attacker.physicsState;
        double radians = Math.toRadians(attacker.yaw);
        double impulse = 0.458 * 20.0 * level;
        double velocityX = current.velocityX() - Math.sin(radians) * impulse;
        double velocityZ = current.velocityZ() + Math.cos(radians) * impulse;
        attacker.physicsState = new PlayerPhysics.State(
                current.x(), current.y(), current.z(),
                velocityX, current.velocityY(), velocityZ,
                current.onGround(), current.fallDistance(), current.landedDistance());
        attacker.vitals = attacker.vitals.addExhaustion(4.0f * level);
        sendMovementCorrection(networkId, attacker, attacker.lastInputTick);
        sendPacket(networkId, createPlayerAttributes(attacker, Math.max(0, attacker.lastInputTick)), true);
    }

    private void applyKnockback(
            String targetNetworkId, SessionState target, SessionState attacker, int level
    ) {
        if (level <= 0) return;
        double dx = target.positionX - attacker.positionX;
        double dz = target.positionZ - attacker.positionZ;
        double length = Math.hypot(dx, dz);
        if (length < 0.0001) return;
        double strength = 0.4 + 0.1 * level;
        double velocityX = dx / length * strength;
        double velocityZ = dz / length * strength;
        if (target.physicsState != null) {
            PlayerPhysics.State current = target.physicsState;
            target.physicsState = new PlayerPhysics.State(
                    current.x(), current.y(), current.z(),
                    current.velocityX() + velocityX,
                    Math.max(current.velocityY(), 0.1),
                    current.velocityZ() + velocityZ,
                    current.onGround(), current.fallDistance(), current.landedDistance());
            sendMovementCorrection(targetNetworkId, target, target.lastInputTick);
        } else {
            sendPacket(targetNetworkId, BedrockActorMotionCodec.createSetActorMotion(
                    target.entityId, velocityX, 0.1, velocityZ,
                    Math.max(0, target.lastInputTick)), true);
        }
    }

    private static boolean isSpear(ItemStack weapon) {
        return weapon != null && weapon.itemId().endsWith("_spear");
    }

    /** Bedrock spear jab cooldowns are tier-specific; other weapons keep the existing gate. */
    private static long weaponAttackCooldownNanos(ItemStack weapon) {
        return ru.nexora.combat.SpearCombatStats.forItem(weapon)
                .map(ru.nexora.combat.SpearCombatStats.Profile::jabCooldownNanos)
                .orElse(50_000_000L);
    }

    /** Spears require 2 blocks of minimum reach and have a 4.5 block maximum reach. */
    private static double maximumWeaponReach(ItemStack weapon) {
        return ru.nexora.combat.SpearCombatStats.forItem(weapon)
                .map(ru.nexora.combat.SpearCombatStats.Profile::maximumReach)
                .orElse(3.5);
    }

    private static boolean isWithinWeaponReach(ItemStack weapon, double distanceSquared) {
        var spearProfile = ru.nexora.combat.SpearCombatStats.forItem(weapon);
        return spearProfile.map(profile -> profile.isWithinReach(distanceSquared))
                .orElse(distanceSquared <= 3.5 * 3.5);
    }

    private static float baseAttackDamage(ItemStack weapon) {
        if (weapon == null) return 1.0f;
        return switch (weapon.itemId()) {
            case "minecraft:wooden_sword", "minecraft:golden_sword" -> 5.0f;
            case "minecraft:stone_sword" -> 6.0f;
            case "minecraft:iron_sword" -> 7.0f;
            case "minecraft:diamond_sword" -> 8.0f;
            case "minecraft:netherite_sword" -> 9.0f;
            case "minecraft:mace" -> 6.0f;
            default -> ru.nexora.combat.SpearCombatStats.forItem(weapon)
                    .map(ru.nexora.combat.SpearCombatStats.Profile::jabDamage)
                    .orElse(1.0f);
        };
    }

    private void handleCraftingUiTransaction(
            String networkId, SessionState state, List<InventoryTransactionService.Action> actions
    ) {
        List<Optional<ru.nexora.inventory.ItemStack>> previousPlayer = state.inventory.snapshot();
        int previousSelectedSlot = state.inventory.selectedHotbarSlot();
        List<Optional<ru.nexora.inventory.ItemStack>> previousUi = state.craftingUiInventory.snapshot();
        ContainerInventoryTransactionService.Result result = containerTransactionService.apply(
                state.inventory, state.craftingUiInventory,
                BedrockInventoryContentCodec.PLAYER_UI_CONTAINER_ID, actions, Set.of(28, 29, 30, 31));

        if (result == ContainerInventoryTransactionService.Result.APPLIED) {
            try {
                saveInventory(state);
            } catch (IOException | RuntimeException exception) {
                state.inventory.restore(previousPlayer, previousSelectedSlot);
                state.craftingUiInventory.restore(previousUi);
                try {
                    saveInventory(state);
                } catch (IOException | RuntimeException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                System.err.println("NEXORA: crafting-grid transaction rolled back because persistence failed: "
                        + exception.getMessage());
            }
        }
        clearSpearChargeIfItemChanged(state);
        sendInventoryAndEquipmentState(networkId, state);
    }

    private void handleOpenContainerTransaction(
            String networkId, SessionState state, List<InventoryTransactionService.Action> actions
    ) {
        ContainerInventory container = state.openContainerInventory;
        int windowId = state.openContainerWindowId;
        if (container == null || windowId == 0) return;

        if (state.openContainerIsFurnace && actions.stream().anyMatch(action ->
                action.containerId() == windowId && action.slot() == FurnaceService.OUTPUT_SLOT
                        && action.to().isPresent())) {
            sendContainerReconciliation(networkId, state);
            return;
        }

        List<Optional<ItemStack>> previousPlayer = state.inventory.snapshot();
        int previousSelectedSlot = state.inventory.selectedHotbarSlot();
        List<Optional<ItemStack>> previousContainer = container.snapshot();
        int claimedExperienceMilli = 0;
        int previousOutputCount = 0;
        boolean furnaceWindow = state.openContainerIsFurnace;
        WorldFurnaceManager furnaceManager = furnaceWindow ? world.furnaces().orElse(null) : null;
        if (furnaceWindow && furnaceManager != null) {
            ItemStack previousOutput = container.getSlot(2).orElse(null);
            previousOutputCount = previousOutput == null ? 0 : previousOutput.count();
        }

        ContainerInventoryTransactionService.Result result = containerTransactionService.apply(
                state.inventory, container, windowId, actions);

        if (result == ContainerInventoryTransactionService.Result.APPLIED) {
            try {
                if (furnaceWindow && furnaceManager != null && previousOutputCount > 0) {
                    ItemStack previousOutput = previousContainer.get(2).orElse(null);
                    ItemStack currentOutput = container.getSlot(2).orElse(null);
                    int currentCount = currentOutput != null && previousOutput != null
                            && currentOutput.itemId().equals(previousOutput.itemId())
                            ? currentOutput.count() : 0;
                    int collectedItems = Math.max(0, previousOutputCount - currentCount);
                    if (collectedItems > 0) {
                        claimedExperienceMilli = furnaceManager.collectExperience(
                                state.openContainerX, state.openContainerY, state.openContainerZ,
                                collectedItems, previousOutputCount);
                    }
                }
                saveInventory(state);
                if (furnaceWindow) {
                    if (furnaceManager == null) {
                        throw new IllegalStateException("World furnace storage is not configured");
                    }
                    furnaceManager.save(state.openContainerX, state.openContainerY, state.openContainerZ);
                } else if (!state.openContainerIsCraftingTable) {
                    WorldContainerManager manager = world.containers().orElseThrow(
                            () -> new IllegalStateException("World container storage is not configured"));
                    manager.save(state.openContainerX, state.openContainerY, state.openContainerZ);
                }
            } catch (IOException | RuntimeException exception) {
                if (claimedExperienceMilli > 0 && furnaceManager != null) {
                    furnaceManager.restorePendingExperience(
                            state.openContainerX, state.openContainerY, state.openContainerZ,
                            claimedExperienceMilli);
                }
                state.inventory.restore(previousPlayer, previousSelectedSlot);
                container.restore(previousContainer);
                try {
                    saveInventory(state);
                    if (furnaceWindow) {
                        world.furnaces().ifPresent(manager -> {
                            try { manager.save(state.openContainerX, state.openContainerY, state.openContainerZ); }
                            catch (IOException rollbackFailure) { throw new java.io.UncheckedIOException(rollbackFailure); }
                        });
                    } else if (!state.openContainerIsCraftingTable) {
                        world.containers().ifPresent(manager -> {
                            try {
                                manager.save(state.openContainerX, state.openContainerY, state.openContainerZ);
                            } catch (IOException rollbackFailure) {
                                throw new java.io.UncheckedIOException(rollbackFailure);
                            }
                        });
                    }
                } catch (IOException | RuntimeException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                claimedExperienceMilli = 0;
                System.err.println("NEXORA: container transaction rolled back because persistence failed: "
                        + exception.getMessage());
            }
            if (claimedExperienceMilli > 0) {
                long currentMilli = (long) state.experiencePoints * 1000L + state.experienceRemainderMilli;
                long updatedMilli = Math.min(2_000_000_000_000L, currentMilli + claimedExperienceMilli);
                state.experiencePoints = (int) (updatedMilli / 1000L);
                state.experienceRemainderMilli = (int) (updatedMilli % 1000L);
                sendPacket(networkId, createPlayerAttributes(state, Math.max(0, state.lastInputTick)), true);
            }
        }

        clearSpearChargeIfItemChanged(state);
        // Always reconcile both inventories so a rejected or stale client prediction cannot linger.
        sendContainerReconciliation(networkId, state);
    }

    private void handleNetworkSettingsRequest(
            NetherNetInboundPipeline.InboundMessage message,
            SessionState state
    ) {
        if (!message.reliable()) {
            throw new IllegalArgumentException("RequestNetworkSettings must arrive on ReliableDataChannel");
        }
        List<BedrockPacket> packets = BedrockBatchCodec.decode(message.payload(), false);
        if (packets.size() != 1) {
            throw new IllegalArgumentException("Initial Bedrock batch must contain only RequestNetworkSettings");
        }
        int clientProtocol = BedrockBatchCodec.readRequestedProtocol(packets.getFirst());
        if (clientProtocol != serverProtocol) {
            int status = clientProtocol < serverProtocol
                    ? LOGIN_FAILED_CLIENT_OLD
                    : LOGIN_FAILED_SERVER_OLD;
            state.rejected = true;
            sendPacket(message.networkId(), BedrockBatchCodec.createPlayStatus(status), false);
            System.out.printf("NEXORA: rejected Bedrock protocol %d; server requires %d%n",
                    clientProtocol, serverProtocol);
            return;
        }

        state.compressionNegotiated = true;
        state.clientProtocol = clientProtocol;
        sendPacket(message.networkId(), BedrockBatchCodec.createNetworkSettings(
                compressionThreshold, BedrockBatchCodec.COMPRESSION_ZLIB), false);
        System.out.printf("NEXORA: negotiated Bedrock protocol %d for %s%n",
                clientProtocol, message.networkId());
    }

    private void handleLogin(
            NetherNetInboundPipeline.InboundMessage message,
            SessionState state,
            BedrockPacket packet
    ) {
        if (!message.reliable()) throw new SecurityException("Login must arrive on ReliableDataChannel");
        if (state.loginAuthenticated) throw new SecurityException("Duplicate Login packet");
        if (state.identity == null) throw new SecurityException("Login has no verified NetherNet identity");

        BedrockLoginPacketParser.LoginRequest login = BedrockLoginPacketParser.parse(packet);
        if (login.authenticationType() != 0) {
            throw new SecurityException("Only authenticated Xbox Live login is supported");
        }
        if (!state.identity.gameServerToken().equals(login.multiplayerToken())) {
            throw new SecurityException("Login GameServerToken does not match the verified NetherNet identity");
        }
        verifyClientDataToken(login.rawClientDataToken(), state.identity);
        if (inventoryStorage != null) {
            try {
                var restored = inventoryStorage.loadState(state.identity.xuid());
                if (restored.isPresent()) {
                    state.inventory = restored.orElseThrow().inventory();
                    state.craftingUiInventory.restore(
                            restored.orElseThrow().craftingUiInventory().snapshot());
                }
            } catch (IOException | RuntimeException exception) {
                state.rejected = true;
                throw new SecurityException("Unable to safely restore authenticated player inventory", exception);
            }
        }
        if (equipmentStorage != null) {
            try {
                state.equipment = equipmentStorage.load(state.identity.xuid()).orElseGet(PlayerEquipment::new);
            } catch (IOException | RuntimeException exception) {
                state.rejected = true;
                throw new SecurityException("Unable to safely restore authenticated player equipment", exception);
            }
        }
        // Do not mark the session authenticated until its persisted inventory has been restored.
        // Otherwise a failed load could be replaced by an empty inventory during autosave.
        state.loginAuthenticated = true;
        state.entityId = nextEntityId.getAndIncrement();
        if (state.entityId < 1) {
            throw new IllegalStateException("NEXORA entity ID space exhausted");
        }

        String displayName = state.identity.displayName() == null
                ? "unknown" : state.identity.displayName();
        if (playerStorage != null) {
            try {
                Optional<PlayerData> saved = playerStorage.load(state.identity.xuid());
                if (saved.isPresent()) {
                    PlayerData player = saved.get();
                    state.vitals = player.vitals();
                    state.experiencePoints = player.experiencePoints();
                    state.experienceRemainderMilli = player.experienceRemainderMilli();
                    state.dead = state.vitals.dead();
                    if (player.worldName().equals(world.name())
                            && WorldCollision.canOccupy(world, player.x(), player.y(), player.z())) {
                        state.positionX = player.x();
                        state.positionY = player.y();
                        state.positionZ = player.z();
                        state.yaw = player.yaw();
                        state.pitch = player.pitch();
                        state.positionInitialized = true;
                    } else if (player.worldName().equals(world.name())) {
                        System.err.println("NEXORA: saved position for " + displayName
                                + " is obstructed; using the world spawn");
                    }
                }
            } catch (IOException | RuntimeException exception) {
                System.err.println("NEXORA: unable to load player profile for " + displayName
                        + ": " + exception.getMessage());
            }
        }
        System.out.printf("NEXORA: authenticated player %s%n", displayName);
    }

    private void handleClientHandshake(String networkId, SessionState state, BedrockPacket packet) {
        if (!state.loginAuthenticated) {
            throw new SecurityException("ClientToServerHandshake arrived before authenticated Login");
        }
        if (state.clientHandshakeReceived) {
            throw new SecurityException("Duplicate ClientToServerHandshake packet");
        }
        if (packet.payload().length != 0) {
            throw new IllegalArgumentException("ClientToServerHandshake must have an empty payload");
        }

        // NetherNet's DTLS layer provides transport encryption; no Bedrock application cipher is enabled.
        state.clientHandshakeReceived = true;
        state.resourcePacksInfoSent = true;
        sendPacket(networkId, BedrockBatchCodec.createPlayStatus(LOGIN_SUCCESS), true);
        sendPacket(networkId, BedrockBatchCodec.createResourcePacksInfo(), true);
    }

    private void handleResourcePackResponse(
            String networkId,
            SessionState state,
            BedrockPacket packet,
            boolean resourcePacksInfoWasSent,
            boolean resourcePackStackWasSent
    ) {
        if (!state.clientHandshakeReceived || !resourcePacksInfoWasSent) {
            throw new SecurityException("ResourcePackClientResponse arrived before ResourcePacksInfo");
        }
        BedrockBatchCodec.ResourcePackClientResponseData response =
                BedrockBatchCodec.parseResourcePackClientResponse(packet);
        switch (response.response()) {
            case 0 -> throw new SecurityException("Client refused resource-pack negotiation");
            case 1 -> throw new SecurityException("Client requested packs from an empty server pack list");
            case 2 -> {
                if (state.resourcePackStackSent) {
                    throw new SecurityException("Duplicate all-packs-downloaded response");
                }
                state.resourcePackStackSent = true;
                sendPacket(networkId, BedrockBatchCodec.createResourcePackStack(serverVersion), true);
            }
            case 3 -> {
                if (!resourcePackStackWasSent) {
                    throw new SecurityException("Resource-pack stack completed before the server sent its stack");
                }
                state.resourcePacksCompleted = true;
                if (!state.gameStarted) {
                    state.gameStarted = true;
                    if (!state.positionInitialized) {
                        state.positionX = world.spawnX() + 0.5f;
                        state.positionY = world.spawnY();
                        state.positionZ = world.spawnZ() + 0.5f;
                        if (!WorldCollision.canOccupy(world, state.positionX, state.positionY, state.positionZ)) {
                            throw new IllegalStateException("Configured NEXORA world spawn is obstructed");
                        }
                        state.positionInitialized = true;
                    }
                    if (serverAuthoritativeMovement) {
                        state.physicsState = PlayerPhysics.spawn(
                                world, state.positionX, state.positionY, state.positionZ);
                    }
                    state.lastMovementNanos = nanoTime.getAsLong();
                    state.movementBudget = 2.0;
                    for (BedrockPacket worldPacket : BedrockStartGameCodec.createInitialWorldPackets(
                            state.entityId,
                            world.name(),
                            serverVersion,
                            serverVersion,
                            world.seed(),
                            world.spawnX(),
                            world.spawnY() - 1,
                            world.spawnZ(),
                            state.positionX,
                            state.positionY,
                            state.positionZ,
                            state.pitch,
                            state.yaw
                    )) {
                        if (worldPacket.packetId() == 0x0b) {
                            // Bedrock requires ItemRegistry immediately after StartGame.
                            sendPackets(networkId, List.of(worldPacket,
                                    BedrockItemRegistryPacketCodec.createPacket(itemRegistry)), true);
                        } else {
                            sendPacket(networkId, worldPacket, true);
                        }
                    }
                    sendPacket(networkId, BedrockBiomeDefinitionCodec.createPacket(), true);
                    sendInitialChunkArea(networkId, state);
                }
                System.out.printf("NEXORA: resource-pack negotiation completed for %s%n", networkId);
            }
            default -> throw new IllegalArgumentException("Unknown resource-pack response");
        }
    }


    private void handlePlayerAuthInput(String networkId, SessionState state, BedrockPacket packet) {
        if (!state.clientInitialized) {
            throw new SecurityException("PlayerAuthInput arrived before client initialization");
        }
        if (state.dead) return;
        BedrockPlayerAuthInputCodec.PlayerInput input = BedrockPlayerAuthInputCodec.decode(packet);
        if (state.hasReceivedInput && input.tick() <= state.lastInputTick) return;
        long inputNowNanos = nanoTime.getAsLong();
        if (state.spearChargeStartedNanos != Long.MIN_VALUE
                && (inputNowNanos < state.spearChargeStartedNanos
                || inputNowNanos - state.spearChargeStartedNanos > 10_000_000_000L)) {
            clearSpearCharge(state);
        }
        if (input.hasInputAction(BedrockPlayerAuthInputCodec.InputAction.START_USING_ITEM)) {
            startSpearChargeIfHeld(state);
        }
        tickPlayerFire(networkId, state, input.tick());
        tickWorldLivingActors(input.tick());

        if (serverAuthoritativeMovement) {
            handleServerAuthoritativeMovement(networkId, state, input);
            return;
        }

        if (Math.abs(input.x()) > 30_000_000 || Math.abs(input.z()) > 30_000_000
                || input.y() < WorldChunk.MIN_Y - 16 || input.y() > WorldChunk.MAX_Y + 16) {
            return;
        }
        long nowNanos = nanoTime.getAsLong();
        double dx = input.x() - state.positionX;
        double dy = input.y() - state.positionY;
        double dz = input.z() - state.positionZ;
        long elapsedNanos = Math.max(0, nowNanos - state.lastMovementNanos);
        double movementBudget = Math.min(2.0,
                state.movementBudget + (Math.min(elapsedNanos, 1_000_000_000L) / 1_000_000_000.0) * 8.0);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > movementBudget) {
            return; // Token-bucket speed guard; full movement simulation follows later.
        }
        if (!WorldCollision.canMove(world,
                state.positionX, state.positionY, state.positionZ,
                input.x(), input.y(), input.z())) {
            return; // Reject movement that clips through solid world blocks.
        }
        state.movementBudget = movementBudget - distance;
        double velocitySeconds = Math.max(0.05, Math.min(0.25, elapsedNanos / 1_000_000_000.0));
        state.velocityX = dx / velocitySeconds;
        state.velocityY = dy / velocitySeconds;
        state.velocityZ = dz / velocitySeconds;
        state.lastMovementNanos = nowNanos;
        state.hasReceivedInput = true;
        state.positionX = input.x();
        state.positionY = input.y();
        state.positionZ = input.z();
        state.yaw = input.yaw();
        state.pitch = input.pitch();
        state.lastInputTick = input.tick();

        for (BedrockPlayerAuthInputCodec.BlockAction action : input.blockActions()) {
            handleBlockAction(networkId, state, action);
        }
        handleItemUseTransaction(networkId, state, input.itemUseTransaction());
        tickWorldItemActors(input.tick());

        int chunkX = Math.floorDiv((int) Math.floor(input.x()), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(input.z()), 16);
        if (chunkX != state.lastChunkX || chunkZ != state.lastChunkZ) {
            sendPacket(networkId, BedrockStartGameCodec.createChunkPublisherUpdate(
                    (int) Math.floor(input.x()), (int) Math.floor(input.y()), (int) Math.floor(input.z())), true);
            sendChunkArea(networkId, state, chunkX, chunkZ);
        }
    }

    private void handleServerAuthoritativeMovement(
            String networkId, SessionState state, BedrockPlayerAuthInputCodec.PlayerInput input
    ) {
        if (Math.abs(input.x()) > 30_000_000 || Math.abs(input.z()) > 30_000_000
                || input.y() < WorldChunk.MIN_Y - 16 || input.y() > WorldChunk.MAX_Y + 16
                || Math.abs(input.motionX()) > 1.05f || Math.abs(input.motionZ()) > 1.05f) {
            sendMovementCorrection(networkId, state, input.tick());
            return;
        }
        if (state.physicsState == null) {
            try {
                state.physicsState = PlayerPhysics.spawn(
                        world, state.positionX, state.positionY, state.positionZ);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Authenticated player has no valid physics state", exception);
            }
        }

        long tickDelta = state.hasReceivedInput ? input.tick() - state.lastInputTick : 1L;
        if (tickDelta < 1) return;
        double deltaSeconds = Math.min(PlayerPhysics.MAX_TICK_SECONDS, tickDelta / 20.0);
        boolean jumping = input.hasInputAction(BedrockPlayerAuthInputCodec.InputAction.START_JUMPING)
                || input.hasInputAction(BedrockPlayerAuthInputCodec.InputAction.JUMP_PRESSED_RAW)
                || input.hasInputAction(BedrockPlayerAuthInputCodec.InputAction.JUMPING);
        long inputNowNanos = nanoTime.getAsLong();
        if (state.usingItem && inputNowNanos - state.usingItemStartedNanos > 3_000_000_000L) {
            clearUsingItem(state);
        }
        if (state.spearChargeStartedNanos != Long.MIN_VALUE
                && inputNowNanos - state.spearChargeStartedNanos > 10_000_000_000L) {
            clearSpearCharge(state);
        }
        float movementX = state.usingItem ? input.motionX() * 0.2f : input.motionX();
        float movementZ = state.usingItem ? input.motionZ() * 0.2f : input.motionZ();
        PlayerPhysics.State next;
        try {
            next = PlayerPhysics.tick(world, state.physicsState,
                    new PlayerPhysics.Input(movementX, movementZ, input.yaw(),
                            input.hasInputAction(BedrockPlayerAuthInputCodec.InputAction.SPRINTING),
                            jumping),
                    deltaSeconds);
        } catch (IllegalArgumentException exception) {
            sendMovementCorrection(networkId, state, input.tick());
            return;
        }

        double errorX = input.x() - next.x();
        double errorY = input.y() - next.y();
        double errorZ = input.z() - next.z();
        boolean correctionNeeded = errorX * errorX + errorY * errorY + errorZ * errorZ > 0.0625;
        aiGuard.recordMovementDiscrepancy(
                state.identity == null ? networkId : state.identity.xuid(),
                Math.hypot(errorX, errorZ), Math.abs(errorY), nanoTime.getAsLong());
        double previousX = state.positionX;
        double previousZ = state.positionZ;
        boolean sprinting = input.hasInputAction(BedrockPlayerAuthInputCodec.InputAction.SPRINTING);
        boolean jumpStarted = input.hasInputAction(BedrockPlayerAuthInputCodec.InputAction.START_JUMPING)
                || input.hasInputAction(BedrockPlayerAuthInputCodec.InputAction.JUMP_PRESSED_RAW);
        state.physicsState = next;
        state.positionX = (float) next.x();
        state.positionY = (float) next.y();
        state.positionZ = (float) next.z();
        state.yaw = input.yaw();
        state.pitch = input.pitch();
        state.lastInputTick = input.tick();
        state.lastMovementNanos = nanoTime.getAsLong();
        state.hasReceivedInput = true;
        double horizontalDistance = Math.hypot(state.positionX - previousX, state.positionZ - previousZ);
        updateSurvivalVitals(networkId, state, input.tick(), horizontalDistance, sprinting, jumpStarted);
        if (next.landedDistance() > 3.0) {
            float fallDamage = (float) Math.ceil(next.landedDistance() - 3.0);
            List<ItemStack> armor = state.equipment.armorSnapshot().stream()
                    .flatMap(Optional::stream).toList();
            fallDamage = ru.nexora.combat.EnchantmentCombatService.applyIncomingDamage(
                    fallDamage, armor,
                    ru.nexora.combat.EnchantmentCombatService.DamageType.FALL);
            if (fallDamage > 0.0f) {
                applyDamage(networkId, state, fallDamage, input.tick(), "death.attack.fall");
            }
        }

        if (correctionNeeded) sendMovementCorrection(networkId, state, input.tick());

        for (BedrockPlayerAuthInputCodec.BlockAction action : input.blockActions()) {
            handleBlockAction(networkId, state, action);
        }
        handleItemUseTransaction(networkId, state, input.itemUseTransaction());
        tickWorldItemActors(input.tick());

        int chunkX = Math.floorDiv((int) Math.floor(state.positionX), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(state.positionZ), 16);
        if (chunkX != state.lastChunkX || chunkZ != state.lastChunkZ) {
            sendPacket(networkId, BedrockStartGameCodec.createChunkPublisherUpdate(
                    (int) Math.floor(state.positionX), (int) Math.floor(state.positionY),
                    (int) Math.floor(state.positionZ)), true);
            sendChunkArea(networkId, state, chunkX, chunkZ);
        }
    }

    private void sendInventoryReconciliation(
            String networkId, SessionState state, BedrockPacket authoritativeContent
    ) {
        List<BedrockPacket> reconciliation = new java.util.ArrayList<>();
        reconciliation.add(authoritativeContent);
        reconciliation.addAll(createEquipmentReconciliationPackets(state));
        sendPackets(networkId, reconciliation, true);
    }

    private void handleItemRelease(
            String networkId, SessionState state,
            BedrockInventoryTransactionCodec.DecodedItemRelease release
    ) {
        if (release.actionType() == 0
                && state.spearChargeStartedNanos != Long.MIN_VALUE) {
            ItemStack chargeItem = state.inventory.getSlot(state.spearChargeSlot).orElse(null);
            ItemStack releasedItem = release.heldItem().orElse(null);
            long releaseNow = nanoTime.getAsLong();
            double fromDx = release.fromX() - state.positionX;
            double fromDy = release.fromY() - state.positionY;
            double fromDz = release.fromZ() - state.positionZ;
            boolean validChargeRelease = release.hand() == 0
                    && state.spearChargeSlot == release.hotbarSlot()
                    && state.spearChargeSlot == state.inventory.selectedHotbarSlot()
                    && chargeItem != null && isSpear(chargeItem)
                    && chargeItem.itemId().equals(state.spearChargeItemId)
                    && chargeItem.equals(state.spearChargeItemSnapshot)
                    && chargeItem.equals(releasedItem)
                    && releaseNow >= state.spearChargeStartedNanos
                    && releaseNow - state.spearChargeStartedNanos <= 10_000_000_000L
                    && fromDx * fromDx + fromDy * fromDy + fromDz * fromDz <= 2.25;
            if (validChargeRelease) {
                state.spearChargeReleasedNanos = releaseNow;
            } else {
                clearSpearCharge(state);
            }
        } else {
            clearSpearCharge(state);
        }
        if (!state.usingItem) return;
        if (release.actionType() == 0) {
            clearUsingItem(state);
            return;
        }
        if (release.actionType() != 1 || release.hotbarSlot() != state.usingItemSlot) {
            clearUsingItem(state);
            return;
        }

        ItemStack held = state.inventory.getSlot(state.usingItemSlot).orElse(null);
        ItemStack released = release.heldItem().orElse(null);
        FoodStats food = held == null ? null : foodStats(held.itemId());
        if (held == null || released == null || food == null
                || !held.equals(released) || state.vitals.hunger() >= PlayerVitals.MAX_HUNGER) {
            clearUsingItem(state);
            return;
        }
        double fromDx = release.fromX() - state.positionX;
        double fromDy = release.fromY() - state.positionY;
        double fromDz = release.fromZ() - state.positionZ;
        if (fromDx * fromDx + fromDy * fromDy + fromDz * fromDz > 2.25) {
            clearUsingItem(state);
            return;
        }

        List<Optional<ItemStack>> before = state.inventory.snapshot();
        int selectedSlot = state.inventory.selectedHotbarSlot();
        state.inventory.setSlot(state.usingItemSlot,
                held.count() == 1 ? null : held.withCount(held.count() - 1));
        try {
            saveInventory(state);
        } catch (IOException | RuntimeException exception) {
            state.inventory.restore(before, selectedSlot);
            clearUsingItem(state);
            sendInventoryAndEquipmentState(networkId, state);
            return;
        }

        int runtimeItemId = state.usingItemRuntimeId;
        clearUsingItem(state);
        state.vitals = state.vitals.feed(food.foodPoints(), food.saturationModifier());
        sendPackets(networkId, List.of(
                BedrockItemUseCompletionCodec.create(runtimeItemId),
                createPlayerAttributes(state, Math.max(0, state.lastInputTick))
        ), true);
        sendInventoryAndEquipmentState(networkId, state);
    }

    private static void clearUsingItem(SessionState state) {
        clearSpearCharge(state);
        state.usingItem = false;
        state.usingItemSlot = -1;
        state.usingItemRuntimeId = 0;
        state.usingItemStartedNanos = 0;
    }

    private void startSpearChargeIfHeld(SessionState state) {
        ItemStack held = state.inventory.heldItem().orElse(null);
        if (!isSpear(held)) return;
        long now = nanoTime.getAsLong();
        if (state.spearChargeStartedNanos != Long.MIN_VALUE
                && state.spearChargeReleasedNanos == Long.MIN_VALUE
                && state.spearChargeSlot == state.inventory.selectedHotbarSlot()
                && held.equals(state.spearChargeItemSnapshot)
                && now >= state.spearChargeStartedNanos
                && now - state.spearChargeStartedNanos <= 10_000_000_000L) return;
        clearSpearCharge(state);
        state.spearChargeStartedNanos = now;
        state.spearChargeSlot = state.inventory.selectedHotbarSlot();
        state.spearChargeItemId = held.itemId();
        state.spearChargeItemSnapshot = held;
    }

    private static void clearSpearChargeIfItemChanged(SessionState state) {
        if (state.spearChargeSlot < 0 || state.spearChargeItemSnapshot == null) return;
        ItemStack current = state.inventory.getSlot(state.spearChargeSlot).orElse(null);
        if (!Objects.equals(current, state.spearChargeItemSnapshot)) clearSpearCharge(state);
    }

    private static void clearSpearCharge(SessionState state) {
        state.spearChargeStartedNanos = Long.MIN_VALUE;
        state.spearChargeReleasedNanos = Long.MIN_VALUE;
        state.spearChargeSlot = -1;
        state.spearChargeItemId = null;
        state.spearChargeItemSnapshot = null;
    }

    private static ru.nexora.combat.SpearChargeCombatService.ChargeEffect consumeSpearCharge(
            SessionState attacker, ItemStack weapon,
            double targetVelocityX, double targetVelocityY, double targetVelocityZ, long nowNanos
    ) {
        if (!isSpear(weapon) || attacker.spearChargeStartedNanos == Long.MIN_VALUE
                || attacker.spearChargeReleasedNanos == Long.MIN_VALUE
                || attacker.spearChargeSlot != attacker.inventory.selectedHotbarSlot()
                || !weapon.itemId().equals(attacker.spearChargeItemId)
                || !weapon.equals(attacker.spearChargeItemSnapshot)
                || nowNanos < attacker.spearChargeReleasedNanos
                || nowNanos - attacker.spearChargeReleasedNanos > 750_000_000L
                || nowNanos < attacker.spearChargeStartedNanos
                || nowNanos - attacker.spearChargeStartedNanos > 10_000_000_000L
                || nowNanos - attacker.spearChargeStartedNanos < 50_000_000L) {
            return null;
        }
        PlayerPhysics.State physics = attacker.physicsState;
        double attackerVelocityX = physics == null ? attacker.velocityX : physics.velocityX();
        double attackerVelocityY = physics == null ? attacker.velocityY : physics.velocityY();
        double attackerVelocityZ = physics == null ? attacker.velocityZ : physics.velocityZ();
        double yaw = Math.toRadians(attacker.yaw);
        double pitch = Math.toRadians(attacker.pitch);
        double horizontalLook = Math.cos(pitch);
        var effect = ru.nexora.combat.SpearChargeCombatService.resolve(
                weapon,
                new ru.nexora.combat.SpearChargeCombatService.Vector(
                        attackerVelocityX, attackerVelocityY, attackerVelocityZ),
                new ru.nexora.combat.SpearChargeCombatService.Vector(
                        targetVelocityX, targetVelocityY, targetVelocityZ),
                new ru.nexora.combat.SpearChargeCombatService.Vector(
                        -Math.sin(yaw) * horizontalLook, -Math.sin(pitch),
                        Math.cos(yaw) * horizontalLook));
        clearSpearCharge(attacker);
        return effect;
    }

    private void handleItemUseTransaction(
            String networkId, SessionState state,
            BedrockPlayerAuthInputCodec.ItemUseTransaction transaction
    ) {
        if (transaction == null || transaction.triggerType() == 0
                || transaction.hand() != 0
                || transaction.slot() != state.inventory.selectedHotbarSlot()) return;

        ItemStack held = state.inventory.getSlot(transaction.slot()).orElse(null);
        if (transaction.actionType() == 0 && hasNoInventoryMutation(transaction)) {
            boolean heldDescriptorMatches = held == null
                    ? isEmptyItemDescriptor(transaction.item())
                    : matchesRegisteredItem(transaction.item(), held);
            int targetX = transaction.x();
            int targetY = transaction.y();
            int targetZ = transaction.z();
            if (heldDescriptorMatches && world.getBlock(targetX, targetY, targetZ) == BlockType.CRAFTING_TABLE
                    && transaction.targetBlockId()
                    == Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(BlockType.CRAFTING_TABLE))
                    && isWithinBlockReach(state, targetX, targetY, targetZ)) {
                double dx = transaction.fromX() - state.positionX;
                double dy = transaction.fromY() - state.positionY;
                double dz = transaction.fromZ() - state.positionZ;
                if (dx * dx + dy * dy + dz * dz <= 2.25) {
                    openCraftingTableWindow(networkId, state, targetX, targetY, targetZ);
                    return;
                }
            }
            if (heldDescriptorMatches && world.getBlock(targetX, targetY, targetZ) == BlockType.CHEST
                    && transaction.targetBlockId()
                    == Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(BlockType.CHEST))
                    && isWithinBlockReach(state, targetX, targetY, targetZ)) {
                double dx = transaction.fromX() - state.positionX;
                double dy = transaction.fromY() - state.positionY;
                double dz = transaction.fromZ() - state.positionZ;
                if (dx * dx + dy * dy + dz * dz <= 2.25) {
                    openChestWindow(networkId, state, targetX, targetY, targetZ);
                    return;
                }
            }
            BlockType clickedBlock = world.getBlock(targetX, targetY, targetZ);
            if (heldDescriptorMatches && furnaceKind(clickedBlock) != null
                    && transaction.targetBlockId()
                    == Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(clickedBlock))
                    && isWithinBlockReach(state, targetX, targetY, targetZ)) {
                double dx = transaction.fromX() - state.positionX;
                double dy = transaction.fromY() - state.positionY;
                double dz = transaction.fromZ() - state.positionZ;
                if (dx * dx + dy * dy + dz * dz <= 2.25) {
                    openFurnaceWindow(networkId, state, targetX, targetY, targetZ, clickedBlock);
                    return;
                }
            }
        }
        if (held == null || !matchesRegisteredItem(transaction.item(), held)
                || !itemUseActionsMatch(transaction, held)) return;

        if (transaction.actionType() == 1) {
            FoodStats food = foodStats(held.itemId());
            if (food == null || state.vitals.hunger() >= PlayerVitals.MAX_HUNGER) return;
            double dx = transaction.fromX() - state.positionX;
            double dy = transaction.fromY() - state.positionY;
            double dz = transaction.fromZ() - state.positionZ;
            if (dx * dx + dy * dy + dz * dz > 2.25) return;
            state.usingItem = true;
            state.usingItemSlot = transaction.slot();
            state.usingItemRuntimeId = transaction.item().runtimeItemId();
            state.usingItemStartedNanos = nanoTime.getAsLong();
            return;
        }
        if (transaction.actionType() != 0) return;

        BlockType placedType = switch (held.itemId()) {
            case "minecraft:stone" -> BlockType.STONE;
            case "minecraft:dirt" -> BlockType.DIRT;
            case "minecraft:grass_block" -> BlockType.GRASS_BLOCK;
            case "minecraft:cobblestone" -> BlockType.COBBLESTONE;
            case "minecraft:gravel" -> BlockType.GRAVEL;
            case "minecraft:glass" -> BlockType.GLASS;
            case "minecraft:obsidian" -> BlockType.OBSIDIAN;
            case "minecraft:netherrack" -> BlockType.NETHERRACK;
            case "minecraft:end_stone" -> BlockType.END_STONE;
            case "minecraft:coal_block" -> BlockType.COAL_BLOCK;
            case "minecraft:iron_block" -> BlockType.IRON_BLOCK;
            case "minecraft:gold_block" -> BlockType.GOLD_BLOCK;
            case "minecraft:diamond_block" -> BlockType.DIAMOND_BLOCK;
            case "minecraft:emerald_block" -> BlockType.EMERALD_BLOCK;
            case "minecraft:chest" -> BlockType.CHEST;
            case "minecraft:oak_log" -> BlockType.OAK_LOG;
            case "minecraft:oak_planks" -> BlockType.OAK_PLANKS;
            case "minecraft:crafting_table" -> BlockType.CRAFTING_TABLE;
            case "minecraft:furnace" -> BlockType.FURNACE;
            case "minecraft:blast_furnace" -> BlockType.BLAST_FURNACE;
            case "minecraft:smoker" -> BlockType.SMOKER;
            default -> null;
        };
        if (placedType == null) return;

        int targetX = transaction.x();
        int targetY = transaction.y();
        int targetZ = transaction.z();
        BlockType targetType = world.getBlock(targetX, targetY, targetZ);
        if (targetType == BlockType.AIR
                || transaction.targetBlockId()
                != Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(targetType))
                || !isWithinBlockReach(state, targetX, targetY, targetZ)) return;

        double fromDx = transaction.fromX() - state.positionX;
        double fromDy = transaction.fromY() - state.positionY;
        double fromDz = transaction.fromZ() - state.positionZ;
        if (fromDx * fromDx + fromDy * fromDy + fromDz * fromDz > 2.25) return;

        int placeX = targetX;
        int placeY = targetY;
        int placeZ = targetZ;
        switch (transaction.face()) {
            case 0 -> placeY--;
            case 1 -> placeY++;
            case 2 -> placeZ--;
            case 3 -> placeZ++;
            case 4 -> placeX--;
            case 5 -> placeX++;
            default -> { return; }
        }
        if (placeY < WorldChunk.MIN_Y || placeY > WorldChunk.MAX_Y
                || world.getBlock(placeX, placeY, placeZ) != BlockType.AIR
                || intersectsPlayer(state, placeX, placeY, placeZ)) return;

        List<Optional<ItemStack>> before = state.inventory.snapshot();
        int selectedSlot = state.inventory.selectedHotbarSlot();
        state.inventory.setSlot(transaction.slot(),
                held.count() == 1 ? null : held.withCount(held.count() - 1));
        try {
            saveInventory(state);
        } catch (IOException | RuntimeException exception) {
            state.inventory.restore(before, selectedSlot);
            sendInventoryAndEquipmentState(networkId, state);
            System.err.println("NEXORA: unable to persist block placement inventory: " + exception.getMessage());
            return;
        }

        try {
            world.setBlock(placeX, placeY, placeZ, placedType);
        } catch (RuntimeException exception) {
            state.inventory.restore(before, selectedSlot);
            try {
                saveInventory(state);
            } catch (IOException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            sendInventoryAndEquipmentState(networkId, state);
            return;
        }

        broadcastBlockUpdate(placeX, placeY, placeZ, placedType);
        sendInventoryAndEquipmentState(networkId, state);
    }

    private static FoodStats foodStats(String itemId) {
        return switch (itemId) {
            case "minecraft:bread" -> new FoodStats(5, 0.6f);
            case "minecraft:carrot" -> new FoodStats(3, 0.6f);
            case "minecraft:potato" -> new FoodStats(1, 0.3f);
            case "minecraft:baked_potato" -> new FoodStats(5, 0.6f);
            case "minecraft:cookie" -> new FoodStats(2, 0.1f);
            case "minecraft:cooked_beef" -> new FoodStats(8, 0.8f);
            case "minecraft:beef" -> new FoodStats(3, 0.3f);
            case "minecraft:chicken" -> new FoodStats(2, 0.3f);
            case "minecraft:cooked_chicken" -> new FoodStats(6, 0.6f);
            case "minecraft:salmon" -> new FoodStats(2, 0.4f);
            case "minecraft:cooked_salmon" -> new FoodStats(6, 0.8f);
            case "minecraft:cod" -> new FoodStats(2, 0.1f);
            case "minecraft:cooked_cod" -> new FoodStats(5, 0.6f);
            case "minecraft:pumpkin_pie" -> new FoodStats(8, 0.3f);
            case "minecraft:melon_slice" -> new FoodStats(2, 0.3f);
            case "minecraft:sweet_berries", "minecraft:glow_berries" -> new FoodStats(2, 0.1f);
            case "minecraft:rabbit" -> new FoodStats(3, 0.3f);
            default -> null;
        };
    }

    private static boolean hasNoInventoryMutation(
            BedrockPlayerAuthInputCodec.ItemUseTransaction transaction
    ) {
        List<BedrockPlayerAuthInputCodec.RawInventoryAction> actions = transaction.actions();
        if (actions.isEmpty()) return true;
        if (actions.size() != 1) return false;
        BedrockPlayerAuthInputCodec.RawInventoryAction action = actions.getFirst();
        return action.sourceType() == 2 && action.sourceFlags() <= 1
                && isEmptyItemDescriptor(action.from()) && isEmptyItemDescriptor(action.to());
    }

    private static boolean isEmptyItemDescriptor(BedrockPlayerAuthInputCodec.RawItemStack descriptor) {
        return descriptor != null && descriptor.runtimeItemId() == 0 && descriptor.count() == 0
                && descriptor.auxValue() == 0 && !descriptor.hasStackNetId()
                && descriptor.blockRuntimeId() == 0 && descriptor.userData().length == 0;
    }

    private boolean matchesRegisteredItem(
            BedrockPlayerAuthInputCodec.RawItemStack descriptor, ItemStack expected
    ) {
        if (descriptor == null || descriptor.hasStackNetId()) return false;
        BedrockItemRegistry.Entry entry = itemRegistry.findByRuntimeItemId(descriptor.runtimeItemId());
        if (entry == null) return false;
        BedrockInventoryContentCodec.ItemDefinition definition = entry.networkDefinition();
        return descriptor.count() == expected.count()
                && descriptor.auxValue() == definition.auxValue()
                && descriptor.blockRuntimeId() == definition.blockRuntimeId()
                && java.util.Arrays.equals(descriptor.userData(), definition.userData())
                && expected.itemId().equals(entry.namespacedId())
                && expected.maxStackSize() == entry.maxStackSize();
    }

    private boolean itemUseActionsMatch(
            BedrockPlayerAuthInputCodec.ItemUseTransaction transaction, ItemStack held
    ) {
        List<BedrockPlayerAuthInputCodec.RawInventoryAction> actions = transaction.actions();
        if (actions.isEmpty()) return true;
        if (actions.size() != 1) return false;
        BedrockPlayerAuthInputCodec.RawInventoryAction action = actions.getFirst();
        if (action.sourceType() == 2) {
            // WorldInteraction action accompanies a use on the world; it does not own inventory slots.
            return action.sourceFlags() <= 1
                    && action.from().runtimeItemId() == 0 && action.to().runtimeItemId() == 0;
        }
        if (action.sourceType() != 0 || action.containerId() != 0
                || action.slot() != transaction.slot()
                || !matchesRegisteredItem(action.from(), held)) return false;
        ItemStack expectedAfter = held.count() == 1 ? null : held.withCount(held.count() - 1);
        if (expectedAfter == null) return action.to().runtimeItemId() == 0;
        return matchesRegisteredItem(action.to(), expectedAfter);
    }

    private static boolean intersectsPlayer(SessionState state, int blockX, int blockY, int blockZ) {
        double playerMinX = state.positionX - 0.3;
        double playerMaxX = state.positionX + 0.3;
        double playerMinY = state.positionY;
        double playerMaxY = state.positionY + 1.8;
        double playerMinZ = state.positionZ - 0.3;
        double playerMaxZ = state.positionZ + 0.3;
        return playerMaxX > blockX && playerMinX < blockX + 1
                && playerMaxY > blockY && playerMinY < blockY + 1
                && playerMaxZ > blockZ && playerMinZ < blockZ + 1;
    }

    private void broadcastBlockUpdate(int x, int y, int z, BlockType type) {
        BedrockPacket update = BedrockBlockUpdateCodec.createPacket(x, y, z, type);
        int changedChunkX = Math.floorDiv(x, 16);
        int changedChunkZ = Math.floorDiv(z, 16);
        long changedChunkKey = ((long) changedChunkX << 32) | (changedChunkZ & 0xffff_ffffL);
        for (var entry : sessions.entrySet()) {
            SessionState recipient = entry.getValue();
            if (recipient.clientInitialized && recipient.sentChunkKeys.contains(changedChunkKey)) {
                sendPacket(entry.getKey(), update, true);
            }
        }
    }

    private void tickPlayerFire(String networkId, SessionState state, long tick) {
        long now = nanoTime.getAsLong();
        if (state.fireUntilNanos == Long.MIN_VALUE) return;
        if (now >= state.fireUntilNanos) {
            state.fireUntilNanos = Long.MIN_VALUE;
            state.nextFireDamageNanos = Long.MIN_VALUE;
            return;
        }
        if (now >= state.nextFireDamageNanos && !state.vitals.dead()) {
            state.nextFireDamageNanos = now + 1_000_000_000L;
            List<ItemStack> armor = state.equipment.armorSnapshot().stream()
                    .flatMap(Optional::stream).toList();
            float fireDamage = ru.nexora.combat.EnchantmentCombatService.applyIncomingDamage(
                    1.0f, armor, ru.nexora.combat.EnchantmentCombatService.DamageType.FIRE);
            if (fireDamage > 0.0f) {
                applyDamage(networkId, state, fireDamage, tick, "death.attack.onFire");
            }
        }
    }

    private void updateSurvivalVitals(
            String networkId, SessionState state, long tick,
            double horizontalDistance, boolean sprinting, boolean jumpStarted
    ) {
        PlayerVitals before = state.vitals;
        float exhaustion = (float) (horizontalDistance * (sprinting ? 0.1 : 0.01));
        if (jumpStarted) exhaustion += 0.2f;
        PlayerVitals updated = before.addExhaustion(exhaustion);
        long nowNanos = nanoTime.getAsLong();
        if (nowNanos >= state.nextNaturalVitalsNanos) {
            if (updated.hunger() >= 18 && updated.health() < PlayerVitals.MAX_HEALTH) {
                updated = updated.heal(1.0f).addExhaustion(6.0f);
            } else if (updated.hunger() == 0 && updated.health() > 1.0f) {
                updated = updated.damage(1.0f);
            }
            state.nextNaturalVitalsNanos = nowNanos + 4_000_000_000L;
        }
        if (updated.equals(before)) return;
        state.vitals = updated;
        boolean healthChanged = updated.health() != before.health();
        boolean clientVisibleChanged = healthChanged
                || updated.hunger() != before.hunger()
                || updated.saturation() != before.saturation();
        if (!clientVisibleChanged) {
            if (state.vitals.dead()) handleDeath(networkId, state, "death.attack.onFire");
            return;
        }
        List<BedrockPacket> packets = new java.util.ArrayList<>();
        if (healthChanged) packets.add(BedrockVitalsCodec.createSetHealth(updated));
        packets.add(createPlayerAttributes(state, Math.max(0, tick)));
        sendPackets(networkId, packets, true);
        if (state.vitals.dead()) handleDeath(networkId, state, "death.attack.onFire");
    }

    /** Applies a server-originated damage event, for later fall, combat and hazard systems. */
    public boolean damagePlayer(String networkId, float amount) {
        SessionState state = sessions.get(networkId);
        if (state == null) return false;
        state.combatLock.lock();
        try {
            synchronized (state) {
                if (!state.loginAuthenticated || !state.clientInitialized || state.vitals.dead()) return false;
                applyDamage(networkId, state, amount, state.lastInputTick, "death.attack.generic");
                return true;
            }
        } finally {
            state.combatLock.unlock();
        }
    }

    private void applyDamage(
            String networkId, SessionState state, float amount, long tick, String deathCause
    ) {
        PlayerVitals before = state.vitals;
        state.vitals = before.damage(amount);
        List<BedrockPacket> packets = new java.util.ArrayList<>();
        if (state.vitals.health() != before.health()) {
            packets.add(BedrockVitalsCodec.createSetHealth(state.vitals));
        }
        packets.add(createPlayerAttributes(state, Math.max(0, tick)));
        sendPackets(networkId, packets, true);
        if (state.vitals.dead()) handleDeath(networkId, state, deathCause);
    }

    private BedrockPacket createPlayerAttributes(SessionState state, long tick) {
        PlayerExperience.Progress experience = PlayerExperience.fromTotalMilliPoints(
                (long) state.experiencePoints * 1000L + state.experienceRemainderMilli);
        return BedrockVitalsCodec.createUpdateAttributes(state.entityId, state.vitals,
                experience.level(), experience.progress(), tick);
    }

    private void sendMovementCorrection(String networkId, SessionState state, long tick) {
        if (state.physicsState == null) return;
        PlayerPhysics.State physics = state.physicsState;
        sendPacket(networkId, BedrockMovementCorrectionCodec.createPlayerCorrection(
                physics.x(), physics.y(), physics.z(),
                physics.velocityX(), physics.velocityY(), physics.velocityZ(),
                physics.onGround(), Math.max(0, tick)), true);
    }

    private void handleBlockAction(
            String networkId,
            SessionState state,
            BedrockPlayerAuthInputCodec.BlockAction action
    ) {
        if (Math.abs((long) action.x()) > 30_000_000
                || Math.abs((long) action.z()) > 30_000_000
                || action.y() < WorldChunk.MIN_Y || action.y() > WorldChunk.MAX_Y
                || (action.action() == 0 && (action.face() < 0 || action.face() > 5))) {
            return;
        }
        if (action.action() == 0) { // START_DESTROY_BLOCK
            if (!isWithinBlockReach(state, action.x(), action.y(), action.z())) return;
            BlockType type = world.getBlock(action.x(), action.y(), action.z());
            if (type == BlockType.AIR || type == BlockType.BEDROCK) return;
            if (!matchesBreakTarget(state.breakTarget, action)) {
                state.breakTarget = new BreakTarget(action.x(), action.y(), action.z(), nanoTime.getAsLong());
            }
            return;
        }

        if (action.action() == 1) { // ABORT_DESTROY_BLOCK
            if (matchesBreakTarget(state, action)) state.breakTarget = null;
            return;
        }

        if (action.action() != 2 && action.action() != 26 && action.action() != 27) return;
        // STOP / PREDICT / CONTINUE_DESTROY_BLOCK
        BreakTarget target = state.breakTarget;
        if (target == null || !matchesBreakTarget(target, action)) return;

        if (!isWithinBlockReach(state, target.x(), target.y(), target.z())) {
            state.breakTarget = null;
            return;
        }
        BlockType current = world.getBlock(target.x(), target.y(), target.z());
        if (current == BlockType.AIR) {
            state.breakTarget = null;
            return;
        }
        long requiredNanos = requiredBreakNanos(current);
        if (requiredNanos == Long.MAX_VALUE
                || nanoTime.getAsLong() - target.startedAtNanos() < requiredNanos) return;

        if (current == BlockType.CHEST) {
            ru.nexora.world.WorldContainerManager manager = world.containers().orElse(null);
            if (manager == null) {
                state.breakTarget = null;
                sendPacket(networkId, BedrockBlockUpdateCodec.createPacket(
                        target.x(), target.y(), target.z(), current), true);
                return;
            }
            try {
                ContainerInventory chest = manager.getOrCreate(target.x(), target.y(), target.z());
                if (chest.snapshot().stream().anyMatch(Optional::isPresent)) {
                    // Until atomic multi-stack spill is implemented, never destroy a
                    // non-empty chest or its persistent contents.
                    state.breakTarget = null;
                    sendPacket(networkId, BedrockBlockUpdateCodec.createPacket(
                            target.x(), target.y(), target.z(), current), true);
                    return;
                }
                for (var entry : sessions.entrySet()) {
                    SessionState viewer = entry.getValue();
                    synchronized (viewer) {
                        if (viewer.openContainerWindowId != 0
                                && viewer.openContainerX == target.x()
                                && viewer.openContainerY == target.y()
                                && viewer.openContainerZ == target.z()) {
                            closeContainerWindow(entry.getKey(), viewer, true);
                        }
                    }
                }
                manager.remove(target.x(), target.y(), target.z());
            } catch (IOException exception) {
                state.breakTarget = null;
                System.err.println("NEXORA: chest break cancelled because its contents could not be checked: "
                        + exception.getMessage());
                return;
            }
        }

        if (furnaceKind(current) != null) {
            WorldFurnaceManager manager = world.furnaces().orElse(null);
            if (manager == null) {
                state.breakTarget = null;
                sendPacket(networkId, BedrockBlockUpdateCodec.createPacket(
                        target.x(), target.y(), target.z(), current), true);
                return;
            }
            try {
                ContainerInventory furnace = manager.getOrCreate(
                        target.x(), target.y(), target.z(), furnaceKind(current));
                if (furnace.snapshot().stream().anyMatch(Optional::isPresent)) {
                    state.breakTarget = null;
                    sendPacket(networkId, BedrockBlockUpdateCodec.createPacket(
                            target.x(), target.y(), target.z(), current), true);
                    return;
                }
                for (var entry : sessions.entrySet()) {
                    SessionState viewer = entry.getValue();
                    synchronized (viewer) {
                        if (viewer.openContainerIsFurnace
                                && viewer.openContainerX == target.x()
                                && viewer.openContainerY == target.y()
                                && viewer.openContainerZ == target.z()) {
                            closeContainerWindow(entry.getKey(), viewer, true);
                        }
                    }
                }
                manager.remove(target.x(), target.y(), target.z());
            } catch (IOException exception) {
                state.breakTarget = null;
                System.err.println("NEXORA: furnace break cancelled because its contents could not be checked: "
                        + exception.getMessage());
                return;
            }
        }

        WorldItemEntity dropped = null;
        String dropItemId = blockDropItemId(current);
        if (dropItemId != null && itemRegistry != null
                && itemRegistry.findByIdentifier(dropItemId) != null) {
            try {
                dropped = world.itemEntities().spawn(
                        new ru.nexora.inventory.ItemStack(dropItemId, 1, 64),
                        target.x() + 0.5, target.y() + 0.5, target.z() + 0.5,
                        0, 0.12, 0,
                        System.currentTimeMillis(), WorldItemEntityManager.DEFAULT_PICKUP_DELAY_MILLIS,
                        state.identity == null ? null : state.identity.xuid());
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: block break cancelled because its item drop could not be saved: "
                        + exception.getMessage());
                return;
            }
        }
        world.setBlock(target.x(), target.y(), target.z(), BlockType.AIR);
        state.breakTarget = null;
        BedrockPacket update = BedrockBlockUpdateCodec.createPacket(
                target.x(), target.y(), target.z(), BlockType.AIR);
        int changedChunkX = Math.floorDiv(target.x(), 16);
        int changedChunkZ = Math.floorDiv(target.z(), 16);
        long changedChunkKey = ((long) changedChunkX << 32) | (changedChunkZ & 0xffff_ffffL);
        for (var entry : sessions.entrySet()) {
            SessionState recipient = entry.getValue();
            // Only update clients that currently have this chunk in their sent view.
            // The set is concurrent because different session event loops can publish chunks
            // while another player breaks a block.
            if (recipient.clientInitialized && recipient.sentChunkKeys.contains(changedChunkKey)) {
                sendPacket(entry.getKey(), update, true);
            }
        }
        if (dropped != null) {
            broadcastItemActor(dropped, BedrockAddItemActorCodec.createAddItemActor(dropped, itemRegistry));
        }
    }

    private static String blockDropItemId(BlockType block) {
        return switch (block) {
            case AIR, BEDROCK -> null;
            case STONE -> "minecraft:cobblestone";
            case GRASS_BLOCK -> "minecraft:dirt";
            case DIRT -> "minecraft:dirt";
            case COBBLESTONE -> "minecraft:cobblestone";
            case GRAVEL -> "minecraft:gravel";
            case GLASS -> "minecraft:glass";
            case OBSIDIAN -> "minecraft:obsidian";
            case NETHERRACK -> "minecraft:netherrack";
            case END_STONE -> "minecraft:end_stone";
            case COAL_BLOCK -> "minecraft:coal_block";
            case IRON_BLOCK -> "minecraft:iron_block";
            case GOLD_BLOCK -> "minecraft:gold_block";
            case DIAMOND_BLOCK -> "minecraft:diamond_block";
            case EMERALD_BLOCK -> "minecraft:emerald_block";
            case CHEST -> "minecraft:chest";
            case OAK_LOG -> "minecraft:oak_log";
            case OAK_PLANKS -> "minecraft:oak_planks";
            case CRAFTING_TABLE -> "minecraft:crafting_table";
            case FURNACE -> "minecraft:furnace";
            case BLAST_FURNACE -> "minecraft:blast_furnace";
            case SMOKER -> "minecraft:smoker";
        };
    }

    private static boolean matchesBreakTarget(SessionState state, BedrockPlayerAuthInputCodec.BlockAction action) {
        return matchesBreakTarget(state.breakTarget, action);
    }

    private static boolean matchesBreakTarget(BreakTarget target, BedrockPlayerAuthInputCodec.BlockAction action) {
        return target != null && target.x() == action.x() && target.y() == action.y() && target.z() == action.z();
    }

    private boolean isWithinBlockReach(SessionState state, int x, int y, int z) {
        // Bedrock player position is at the feet; block interaction originates near eye height.
        double eyeX = state.positionX;
        double eyeY = state.positionY + 1.62;
        double eyeZ = state.positionZ;
        double targetX = x + 0.5;
        double targetY = y + 0.5;
        double targetZ = z + 0.5;
        double dx = eyeX - targetX;
        double dy = eyeY - targetY;
        double dz = eyeZ - targetZ;
        return dx * dx + dy * dy + dz * dz <= 36.0
                && WorldRaycast.hasLineOfSight(world, eyeX, eyeY, eyeZ, x, y, z);
    }

    private static long requiredBreakNanos(BlockType type) {
        return switch (type) {
            case AIR, BEDROCK -> Long.MAX_VALUE;
            case DIRT, GRASS_BLOCK -> 600_000_000L; // 0.6 seconds
            case GRAVEL -> 900_000_000L;
            case GLASS -> 300_000_000L;
            case NETHERRACK -> 400_000_000L;
            case STONE, COBBLESTONE, COAL_BLOCK, IRON_BLOCK, GOLD_BLOCK,
                    DIAMOND_BLOCK, EMERALD_BLOCK -> 7_500_000_000L;
            case END_STONE -> 9_000_000_000L;
            case OBSIDIAN -> 250_000_000_000L;
            case CHEST -> 2_500_000_000L;
            case OAK_LOG -> 2_000_000_000L;
            case OAK_PLANKS -> 2_000_000_000L;
            case CRAFTING_TABLE, FURNACE, BLAST_FURNACE, SMOKER -> 2_500_000_000L;
        };
    }

    private void sendInitialChunkArea(String networkId, SessionState state) {
        int centerX = Math.floorDiv((int) Math.floor(state.positionX), 16);
        int centerZ = Math.floorDiv((int) Math.floor(state.positionZ), 16);
        sendChunkArea(networkId, state, centerX, centerZ);
    }

    private void sendChunkArea(String networkId, SessionState state, int centerX, int centerZ) {
        int radius = 1;
        Set<Long> desiredChunks = new HashSet<>();
        for (int ring = 0; ring <= radius; ring++) {
            for (int offsetZ = -ring; offsetZ <= ring; offsetZ++) {
                for (int offsetX = -ring; offsetX <= ring; offsetX++) {
                    if (Math.max(Math.abs(offsetX), Math.abs(offsetZ)) != ring) continue;
                    int chunkX = centerX + offsetX;
                    int chunkZ = centerZ + offsetZ;
                    long key = ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
                    desiredChunks.add(key);
                    if (state.sentChunkKeys.add(key)) {
                        WorldChunk chunk = world.getChunk(chunkX, chunkZ);
                        sendPacket(networkId, BedrockLevelChunkCodec.createPacket(chunk), true);
                        if (itemRegistry != null) {
                            for (WorldItemEntity entity : world.itemEntities().snapshot()) {
                                if (Math.floorDiv((int) Math.floor(entity.x()), 16) == chunkX
                                        && Math.floorDiv((int) Math.floor(entity.z()), 16) == chunkZ) {
                                    sendPacket(networkId,
                                            BedrockAddItemActorCodec.createAddItemActor(entity, itemRegistry), true);
                                }
                            }
                        }
                    }
                }
            }
        }
        state.sentChunkKeys.retainAll(desiredChunks);
        state.lastChunkX = centerX;
        state.lastChunkZ = centerZ;
    }

    private void handleClientInitialized(
            NetherNetInboundPipeline.InboundMessage message,
            SessionState state,
            BedrockPacket packet
    ) {
        if (!message.reliable()) {
            throw new SecurityException("SetLocalPlayerAsInitialized must arrive on ReliableDataChannel");
        }
        if (!state.gameStarted) {
            throw new SecurityException("SetLocalPlayerAsInitialized arrived before StartGame");
        }
        if (state.clientInitialized) {
            throw new SecurityException("Duplicate SetLocalPlayerAsInitialized packet");
        }
        long runtimeEntityId = readUnsignedVarLong(packet.payload());
        if (runtimeEntityId != state.entityId) {
            throw new SecurityException("SetLocalPlayerAsInitialized runtime ID does not match this player");
        }
        state.clientInitialized = true;
        sendPacket(message.networkId(), BedrockBatchCodec.createPlayStatus(3), true);
        sendInitialPlayerInventory(message.networkId(), state);
        sendExistingLivingActors(message.networkId());
        if (state.dead) handleDeath(message.networkId(), state, "death.attack.generic");
    }

    /**
     * Sends the first authoritative player inventory snapshot only after the client
     * has acknowledged StartGame. Unknown persisted items are never serialized with
     * guessed Bedrock IDs; in that case the inventory remains server-side until its
     * exact protocol definition is registered.
     */
    private void sendInitialPlayerInventory(String networkId, SessionState state) {
        state.nextNaturalVitalsNanos = nanoTime.getAsLong() + 4_000_000_000L;
        try {
            List<BedrockPacket> initialPackets = new java.util.ArrayList<>();
            initialPackets.add(BedrockInventoryContentCodec.createPlayerInventory(state.inventory, itemRegistry));
            initialPackets.add(BedrockMobEquipmentCodec.createPlayerEquipment(state.entityId, state.inventory, itemRegistry));
            initialPackets.addAll(BedrockPlayerEquipmentCodec.createInitialEquipment(state.equipment, itemRegistry));
            initialPackets.add(BedrockAvailableCommandsCodec.createEquipmentCommands());
            initialPackets.add(BedrockCraftingDataCodec.createPacket(craftingRecipes, itemRegistry));
            initialPackets.add(BedrockInventoryContentCodec.createPlayerUiInventory(
                    state.craftingUiInventory, itemRegistry));
            sendPackets(networkId, initialPackets, true);
        } catch (IllegalArgumentException exception) {
            System.err.println("NEXORA: initial inventory sync deferred because a stored item has no Bedrock definition");
        }
        sendPackets(networkId, List.of(
                BedrockVitalsCodec.createSetHealth(state.vitals),
                createPlayerAttributes(state, Math.max(0, state.lastInputTick))), true);
    }

    /** Drops a server-selected inventory stack and advertises the new world actor. */
    public Optional<WorldItemEntity> dropPlayerItem(
            String networkId, int inventorySlot, int quantity,
            double velocityX, double velocityY, double velocityZ
    ) throws IOException {
        SessionState state = sessions.get(networkId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            if (!state.loginAuthenticated || !state.clientInitialized || state.identity == null) {
                return Optional.empty();
            }
            if (inventorySlot < 0 || inventorySlot >= state.inventory.size()) return Optional.empty();
            Optional<ru.nexora.inventory.ItemStack> current = state.inventory.getSlot(inventorySlot);
            if (current.isEmpty() || quantity < 1 || quantity > current.orElseThrow().count()) {
                return Optional.empty();
            }
            ru.nexora.inventory.ItemStack stack = current.orElseThrow();
            if (itemRegistry.findByIdentifier(stack.itemId()) == null) return Optional.empty();

            List<Optional<ru.nexora.inventory.ItemStack>> previousInventory = state.inventory.snapshot();
            int previousSelectedSlot = state.inventory.selectedHotbarSlot();
            if (quantity == stack.count()) {
                state.inventory.setSlot(inventorySlot, null);
            } else {
                state.inventory.setSlot(inventorySlot,
                        stack.withCount(stack.count() - quantity));
            }

            WorldItemEntity entity = null;
            try {
                entity = world.itemEntities().spawn(
                        stack.withCount(quantity),
                        state.positionX, state.positionY + 0.5, state.positionZ,
                        velocityX, velocityY, velocityZ,
                        System.currentTimeMillis(), WorldItemEntityManager.DEFAULT_PICKUP_DELAY_MILLIS,
                        state.identity.xuid());
                saveInventory(state);
            } catch (IOException | RuntimeException exception) {
                if (entity != null) world.itemEntities().remove(entity.runtimeId());
                state.inventory.restore(previousInventory, previousSelectedSlot);
                if (exception instanceof IOException ioException) throw ioException;
                throw exception;
            }

            sendInventoryAndEquipmentState(networkId, state);
            broadcastItemActor(entity, BedrockAddItemActorCodec.createAddItemActor(entity, itemRegistry));
            return Optional.of(entity);
        }
    }

    private void handleTakeItemActor(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        String networkId = message.networkId();
        if (!message.reliable()) throw new SecurityException("TakeItemActor must arrive on ReliableDataChannel");
        if (!state.loginAuthenticated || !state.clientInitialized || state.identity == null) {
            throw new SecurityException("TakeItemActor arrived before player initialization");
        }

        BedrockTakeItemActorCodec.DecodedPickup request;
        try {
            request = BedrockTakeItemActorCodec.decode(packet);
        } catch (IllegalArgumentException exception) {
            throw new SecurityException("Malformed Bedrock TakeItemActor", exception);
        }
        if (request.playerRuntimeId() != state.entityId) {
            throw new SecurityException("TakeItemActor player runtime ID does not match this session");
        }

        WorldItemEntity entity = world.itemEntities().find(request.itemRuntimeId()).orElse(null);
        if (entity == null) return;
        if (itemRegistry.findByIdentifier(entity.stack().itemId()) == null) {
            throw new SecurityException("Dropped item has no registered Bedrock definition");
        }
        List<Optional<ru.nexora.inventory.ItemStack>> previousInventory = state.inventory.snapshot();
        int previousSelectedSlot = state.inventory.selectedHotbarSlot();
        boolean pickedUp = world.itemEntities().pickup(
                entity.runtimeId(), state.inventory, state.identity.xuid(),
                state.positionX, state.positionY, state.positionZ, System.currentTimeMillis());
        if (!pickedUp) {
            sendPacket(networkId, BedrockAddItemActorCodec.createAddItemActor(entity, itemRegistry), true);
            return;
        }

        try {
            saveInventory(state);
        } catch (IOException exception) {
            state.inventory.restore(previousInventory, previousSelectedSlot);
            world.itemEntities().restore(entity);
            sendInventoryAndEquipmentState(networkId, state);
            sendPacket(networkId, BedrockAddItemActorCodec.createAddItemActor(entity, itemRegistry), true);
            throw new IllegalStateException("Unable to persist picked-up item inventory", exception);
        }
        sendInventoryAndEquipmentState(networkId, state);
        broadcastItemActor(entity,
                BedrockTakeItemActorCodec.createAcknowledgement(entity.runtimeId(), state.entityId));
        broadcastItemActor(entity, BedrockAddItemActorCodec.createRemoveActor(entity.uniqueId()));
    }

    private void tickWorldItemActors(long tick) {
        long nowNanos = nanoTime.getAsLong();
        while (true) {
            long previous = lastItemActorTickNanos.get();
            if (previous == Long.MIN_VALUE) {
                if (lastItemActorTickNanos.compareAndSet(previous, nowNanos)) return;
                continue;
            }
            long elapsed = nowNanos - previous;
            if (elapsed < 50_000_000L) return;
            if (!lastItemActorTickNanos.compareAndSet(previous, nowNanos)) continue;
            double deltaSeconds = Math.min(0.1, elapsed / 1_000_000_000.0);
            for (WorldItemEntity entity : world.itemEntities().tick(
                    world, deltaSeconds, System.currentTimeMillis())) {
                broadcastItemActor(entity, BedrockItemActorCodec.createMove(
                        entity, Math.abs(entity.velocityY()) < 1.0e-6, Math.max(0, tick)));
            }
            for (WorldItemEntity removed : world.itemEntities().drainRemovedByTick()) {
                broadcastItemActor(removed, BedrockItemActorCodec.createRemove(removed.uniqueId()));
            }
            return;
        }
    }

    private void broadcastItemActor(WorldItemEntity entity, BedrockPacket packet) {
        int chunkX = Math.floorDiv((int) Math.floor(entity.x()), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(entity.z()), 16);
        long chunkKey = ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
        for (var entry : sessions.entrySet()) {
            SessionState recipient = entry.getValue();
            if (!recipient.clientInitialized || recipient.rejected
                    || !recipient.sentChunkKeys.contains(chunkKey)) continue;
            try {
                sendPacket(entry.getKey(), packet, true);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to sync item actor to " + entry.getKey()
                        + ": " + exception.getMessage());
            }
        }
    }

    private void broadcastPacket(BedrockPacket packet) {
        for (var entry : sessions.entrySet()) {
            SessionState recipient = entry.getValue();
            if (!recipient.clientInitialized || recipient.rejected) continue;
            try {
                sendPacket(entry.getKey(), packet, true);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to broadcast actor packet to " + entry.getKey()
                        + ": " + exception.getMessage());
            }
        }
    }

    private void broadcastPacketExcept(String excludedNetworkId, BedrockPacket packet) {
        for (var entry : sessions.entrySet()) {
            if (entry.getKey().equals(excludedNetworkId)) continue;
            SessionState recipient = entry.getValue();
            if (!recipient.clientInitialized || recipient.rejected) continue;
            try {
                sendPacket(entry.getKey(), packet, true);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to broadcast combat animation to " + entry.getKey()
                        + ": " + exception.getMessage());
            }
        }
    }

    private void handleMobEquipment(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        String networkId = message.networkId();
        if (!message.reliable()) {
            throw new SecurityException("MobEquipment must arrive on ReliableDataChannel");
        }
        if (!state.loginAuthenticated || !state.clientInitialized) {
            throw new SecurityException("MobEquipment arrived before player initialization");
        }

        BedrockMobEquipmentCodec.DecodedEquipment equipment;
        try {
            equipment = BedrockMobEquipmentCodec.decode(packet, itemRegistry);
        } catch (UnsupportedOperationException exception) {
            sendPackets(networkId, createEquipmentReconciliationPackets(state), true);
            return;
        } catch (IllegalArgumentException exception) {
            state.rejected = true;
            throw new SecurityException("Malformed Bedrock MobEquipment", exception);
        }

        if (equipment.runtimeEntityId() != state.entityId) {
            throw new SecurityException("MobEquipment runtime entity ID does not match this player");
        }
        if (equipment.containerId() != BedrockInventoryContentCodec.PLAYER_INVENTORY_CONTAINER_ID
                || equipment.inventorySlot() >= PlayerInventory.HOTBAR_SIZE
                || equipment.hotbarSlot() >= PlayerInventory.HOTBAR_SIZE
                || equipment.inventorySlot() != equipment.hotbarSlot()) {
            sendPackets(networkId, createEquipmentReconciliationPackets(state), true);
            return;
        }

        Optional<ru.nexora.inventory.ItemStack> authoritative =
                state.inventory.getSlot(equipment.inventorySlot());
        if (!authoritative.equals(equipment.item())) {
            List<BedrockPacket> reconciliation = new java.util.ArrayList<>();
            reconciliation.add(BedrockInventoryContentCodec.createPlayerInventory(state.inventory, itemRegistry));
            reconciliation.addAll(createEquipmentReconciliationPackets(state));
            sendPackets(networkId, reconciliation, true);
            return;
        }
        if (state.spearChargeSlot != -1 && state.spearChargeSlot != equipment.hotbarSlot()) {
            clearSpearCharge(state);
        }
        state.inventory.selectHotbarSlot(equipment.hotbarSlot());
    }

    private static long readUnsignedVarLong(byte[] payload) {
        long value = 0;
        int shift = 0;
        for (int i = 0; i < 10; i++) {
            if (i >= payload.length) throw new IllegalArgumentException("Truncated unsigned VarLong");
            int current = Byte.toUnsignedInt(payload[i]);
            if (i == 9 && (current & 0xfe) != 0) {
                throw new IllegalArgumentException("Unsigned VarLong exceeds 64 bits");
            }
            value |= (long) (current & 0x7f) << shift;
            if ((current & 0x80) == 0) {
                if (i + 1 != payload.length) throw new IllegalArgumentException("Trailing bytes in unsigned VarLong");
                if (value < 0) throw new IllegalArgumentException("Runtime entity ID exceeds signed long range");
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Unsigned VarLong is too long");
    }

    private void sendPacket(String networkId, BedrockPacket packet, boolean compressionNegotiated) {
        sendPackets(networkId, List.of(packet), compressionNegotiated);
    }

    private void sendPackets(String networkId, List<BedrockPacket> packets, boolean compressionNegotiated) {
        byte[] batch = BedrockBatchCodec.encode(
                packets, compressionNegotiated, compressionThreshold);
        List<byte[]> frames = NetherNetMessageFramer.fragment(
                batch, SCTP_MAX_MESSAGE_BYTES, true);
        try {
            for (byte[] frame : frames) {
                outbound.sendFrame(networkId, "ReliableDataChannel", frame);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to send Bedrock packet batch", exception);
        }
    }

    private static void verifyClientDataToken(String token, NetherNetClientIdentity identity) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[2].isEmpty()) {
                throw new SecurityException("Malformed client-data JWT");
            }
            JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"ES384".equals(header.path("alg").asText())) {
                throw new SecurityException("Client-data JWT must use ES384");
            }

            byte[] publicKeyDer = identity.publicKeyDer();
            String x5u = header.path("x5u").asText("");
            if (!x5u.isBlank()) {
                byte[] headerKey = Base64.getDecoder().decode(x5u);
                if (!MessageDigest.isEqual(publicKeyDer, headerKey)) {
                    throw new SecurityException("Client-data JWT x5u does not match the verified client key");
                }
            }

            PublicKey publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(publicKeyDer));
            Signature verifier = Signature.getInstance("SHA384withECDSA");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if (!verifier.verify(joseToDer(Base64.getUrlDecoder().decode(parts[2])))) {
                throw new SecurityException("Invalid client-data JWT signature");
            }

            JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long now = Instant.now().getEpochSecond();
            JsonNode expires = claims.get("exp");
            if (expires != null && (!expires.canConvertToLong() || now > expires.asLong() + 30)) {
                throw new SecurityException("Client-data JWT has expired or has an invalid expiry");
            }
            JsonNode notBefore = claims.get("nbf");
            if (notBefore != null && (!notBefore.canConvertToLong() || now + 30 < notBefore.asLong())) {
                throw new SecurityException("Client-data JWT is not active yet");
            }
        } catch (SecurityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SecurityException("Unable to validate client-data JWT", exception);
        }
    }

    private static byte[] joseToDer(byte[] jose) {
        if (jose.length != 96) throw new SecurityException("ES384 signature must be 96 bytes");
        byte[] r = derInteger(jose, 0);
        byte[] s = derInteger(jose, 48);
        int bodyLength = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + bodyLength];
        int offset = 0;
        der[offset++] = 0x30;
        der[offset++] = (byte) bodyLength;
        der[offset++] = 0x02;
        der[offset++] = (byte) r.length;
        System.arraycopy(r, 0, der, offset, r.length);
        offset += r.length;
        der[offset++] = 0x02;
        der[offset++] = (byte) s.length;
        System.arraycopy(s, 0, der, offset, s.length);
        return der;
    }

    private static byte[] derInteger(byte[] jose, int offset) {
        int first = offset;
        int end = offset + 48;
        while (first < end - 1 && jose[first] == 0) first++;
        int length = end - first;
        boolean needsZero = (jose[first] & 0x80) != 0;
        byte[] result = new byte[length + (needsZero ? 1 : 0)];
        System.arraycopy(jose, first, result, result.length - length, length);
        return result;
    }

    @Override
    public void close() {
        if (furnaceUpdateListener != null) {
            try { furnaceUpdateListener.close(); }
            catch (Exception exception) {
                System.err.println("NEXORA: unable to unregister furnace updates: " + exception.getMessage());
            }
        }
        for (SessionState state : sessions.values()) {
            synchronized (state) {
                try {
                    returnCraftingGridItems(state);
                } catch (IOException | RuntimeException exception) {
                    System.err.println("NEXORA: unable to return crafting-grid items on shutdown: "
                            + exception.getMessage());
                }
            }
        }
        try {
            savePlayers();
        } catch (IOException exception) {
            System.err.println("NEXORA: unable to save player profiles on shutdown: " + exception.getMessage());
        }
        sessions.clear();
    }

    @FunctionalInterface
    public interface OutboundFrameSender {
        void sendFrame(String networkId, String channelLabel, byte[] frame) throws Exception;
    }

    public record PacketEvent(String networkId, String channelLabel, BedrockPacket packet) {
        public PacketEvent {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(channelLabel, "channelLabel");
            Objects.requireNonNull(packet, "packet");
        }
    }

    public record PlayerPosition(float x, float y, float z, float yaw, float pitch, long tick) {
    }

    private record BreakTarget(int x, int y, int z, long startedAtNanos) {
    }

    private record FoodStats(int foodPoints, float saturationModifier) {
    }

    private enum EquipmentFormKind { MAIN, SLOT }

    private record PendingEquipmentForm(
            int formId, EquipmentFormKind kind, EquipmentSlot slot,
            List<Integer> inventorySlots, boolean hasUnequip
    ) {
    }

    private record MobKillCredit(String networkId, int lootingLevel) {
        private MobKillCredit {
            Objects.requireNonNull(networkId, "networkId");
            if (networkId.isBlank() || lootingLevel < 0 || lootingLevel > 3) {
                throw new IllegalArgumentException("Invalid mob kill credit");
            }
        }
    }

    private static final class SessionState {
        private final ReentrantLock combatLock = new ReentrantLock();
        private long lastAttackNanos = Long.MIN_VALUE;
        private long lastCombatDamageNanos = Long.MIN_VALUE;
        private long spearChargeStartedNanos = Long.MIN_VALUE;
        private long spearChargeReleasedNanos = Long.MIN_VALUE;
        private int spearChargeSlot = -1;
        private String spearChargeItemId;
        private ItemStack spearChargeItemSnapshot;
        private long fireUntilNanos = Long.MIN_VALUE;
        private long nextFireDamageNanos = Long.MIN_VALUE;
        private PlayerInventory inventory = new PlayerInventory();
        private PlayerEquipment equipment = new PlayerEquipment();
        private PlayerVitals vitals = PlayerVitals.DEFAULT;
        private int experiencePoints;
        private int experienceRemainderMilli;
        private boolean dead;
        private boolean deathPacketsSent;
        private boolean usingItem;
        private int usingItemSlot = -1;
        private int usingItemRuntimeId;
        private long usingItemStartedNanos;
        private long nextNaturalVitalsNanos;
        private boolean compressionNegotiated;
        private volatile boolean loginAuthenticated;
        private boolean clientHandshakeReceived;
        private boolean resourcePacksInfoSent;
        private boolean resourcePackStackSent;
        private volatile boolean resourcePacksCompleted;
        private boolean gameStarted;
        private volatile boolean clientInitialized;
        private boolean positionInitialized;
        private float positionX;
        private float positionY;
        private float positionZ;
        private double velocityX;
        private double velocityY;
        private double velocityZ;
        private float yaw;
        private float pitch;
        private PlayerPhysics.State physicsState;
        private long lastInputTick;
        private boolean hasReceivedInput;
        private long lastMovementNanos;
        private double movementBudget = 2.0;
        private int lastChunkX = Integer.MIN_VALUE;
        private int lastChunkZ = Integer.MIN_VALUE;
        private final Set<Long> sentChunkKeys = ConcurrentHashMap.newKeySet();
        private BreakTarget breakTarget;
        private long entityId;
        private boolean rejected;
        private PendingEquipmentForm pendingEquipmentForm;
        private int pendingAiFormId;
        private int nextContainerWindowId = 1;
        private int openContainerWindowId;
        private int openContainerType = BedrockContainerPacketCodec.CONTAINER_TYPE;
        private boolean openContainerIsCraftingTable;
        private boolean openContainerIsFurnace;
        private int openContainerX;
        private int openContainerY;
        private int openContainerZ;
        private ContainerInventory openContainerInventory;
        private final ContainerInventory craftingUiInventory =
                new ContainerInventory(BedrockInventoryContentCodec.PLAYER_UI_SIZE);
        private int clientProtocol;
        private NetherNetClientIdentity identity;
    }
}
