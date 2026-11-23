package ru.nexora.network.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.network.nethernet.NetherNetDataChannelListener;
import ru.nexora.network.nethernet.NetherNetInboundPipeline;
import ru.nexora.network.nethernet.NetherNetMessageFramer;
import ru.nexora.network.nethernet.NetherNetOutboundQueue;
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
import ru.nexora.world.WorldChunkResidencyManager;
import ru.nexora.world.WorldCollision;
import ru.nexora.world.WorldRaycast;
import ru.nexora.world.WorldPathfinder;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerDataStorage;
import ru.nexora.world.PlayerExperience;
import ru.nexora.world.PlayerPhysics;
import ru.nexora.world.PlayerVitals;
import ru.nexora.persistence.PlayerStateBundle;
import ru.nexora.persistence.PlayerStateBundleCodec;
import ru.nexora.persistence.PlayerStateCheckpointService;
import ru.nexora.network.PlayerTransferFence;
import ru.nexora.network.PlayerTransferRecoveryService;
import ru.nexora.network.PendingPlayerTransferRestoreRegistry;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.io.IOException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
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
    public record AuthenticatedPlayer(String xuid, String displayName, long entityId) {
    }

    public record TransferReconciliationReport(int scanned, int resumed, int redirected, int unresolved) {
        public TransferReconciliationReport {
            if (scanned < 0 || resumed < 0 || redirected < 0 || unresolved < 0) {
                throw new IllegalArgumentException("Transfer reconciliation counts must not be negative");
            }
        }
    }

    public static final int LOGIN_FAILED_CLIENT_OLD = 1;
    public static final int LOGIN_FAILED_SERVER_OLD = 2;
    public static final int LOGIN_SUCCESS = 0;
    public static final int DEFAULT_COMPRESSION_THRESHOLD = 256;
    public static final int SCTP_MAX_MESSAGE_BYTES = 262_144;
    private static final long TRANSFER_OWNERSHIP_RECHECK_NANOS = 5_000_000_000L;
    private static final int MAX_OUTBOUND_FRAMES_PER_DRAIN = 64;
    private static final long MAX_OUTBOUND_BYTES_PER_DRAIN = 1L * 1024L * 1024L;
    private static final long MAX_TOTAL_OUTBOUND_QUEUED_BYTES = 64L * 1024L * 1024L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int serverProtocol;
    private final String serverVersion;
    private final int compressionThreshold;
    private final World world;
    private final PlayerDataStorage playerStorage;
    private final PlayerInventoryStorage inventoryStorage;
    private final PlayerEquipmentStorage equipmentStorage;
    private final PlayerStateCheckpointService checkpointService;
    private final PendingPlayerTransferRestoreRegistry pendingTransferRestores =
            new PendingPlayerTransferRestoreRegistry();
    private volatile ru.nexora.network.DurableHandoffCoordinator transferOwnershipLedger;
    private volatile String transferOwnershipNodeId;
    private volatile ru.nexora.network.PlayerOnlineLeaseRegistry onlineLeaseRegistry;
    private volatile long onlineLeaseMillis = 15_000L;
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
    private final ActorReplicationIndex actorReplicationIndex = new ActorReplicationIndex();
    private final ActorReplicationIndex itemActorReplicationIndex = new ActorReplicationIndex();
    private final ActorReplicationIndex playerActorReplicationIndex = new ActorReplicationIndex();
    private final Object[] xuidLoginLocks = createXuidLoginLocks();
    private final Object sessionChangeMonitor = new Object();
    private final AtomicLong nextEntityId = new AtomicLong(1);
    private final AtomicLong outboundFramesSent = new AtomicLong();
    private final AtomicLong outboundBytesSent = new AtomicLong();
    private final AtomicLong outboundSendFailures = new AtomicLong();
    private final AtomicLong outboundBackpressureRejections = new AtomicLong();
    private final AtomicLong outboundQueuedBytesGlobal = new AtomicLong();
    private final OutboundDrainBudget outboundDrainBudget = new OutboundDrainBudget();
    private final AtomicLong chunkStreamingBatches = new AtomicLong();
    private final AtomicLong chunkStreamingCompleted = new AtomicLong();
    private final AtomicLong chunkPacketsEncoded = new AtomicLong();
    private final AtomicLong chunkPacketsReused = new AtomicLong();
    private final AtomicLong chunkStreamingPendingPeak = new AtomicLong();
    private final ChunkStreamingBudget chunkStreamingBudget = new ChunkStreamingBudget();
    private final AtomicLong nextFormId = new AtomicLong(1);
    private final AtomicLong lastItemActorTickNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastLivingAiTickMillis = new AtomicLong(Long.MIN_VALUE);
    private final Map<Long, Long> nextMobAttackMillis = new ConcurrentHashMap<>();
    private final Map<Long, MobKillCredit> livingEntityKillCredit = new ConcurrentHashMap<>();
    private final Map<Long, String> livingEntityAggroTargets = new ConcurrentHashMap<>();
    private final Map<Long, Long> livingEntityAggroExpiresAtMillis = new ConcurrentHashMap<>();
    private static final long MOB_AGGRO_RETENTION_MILLIS = 10_000L;
    private static final long CHAT_RATE_WINDOW_NANOS = 5_000_000_000L;
    private static final int MAX_CHAT_MESSAGES_PER_WINDOW = 5;
    private final Map<Long, ru.nexora.combat.ArrowProjectile> mobArrows = new ConcurrentHashMap<>();
    private final Map<Long, ru.nexora.combat.FireballProjectile> mobFireballs = new ConcurrentHashMap<>();
    // Projectile actor visibility is indexed by chunk just like mobs and dropped items.
    private final Map<Long, Set<Long>> mobArrowIdsByChunk = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> mobFireballIdsByChunk = new ConcurrentHashMap<>();
    private final Map<Long, Long> mobArrowChunkById = new ConcurrentHashMap<>();
    private final Map<Long, Long> mobFireballChunkById = new ConcurrentHashMap<>();
    // Owner indexes make mob-death projectile cleanup proportional to that mob's projectiles.
    private final Map<String, Set<Long>> mobArrowIdsByOwner = new ConcurrentHashMap<>();
    private final Map<Long, String> mobArrowOwnerById = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> mobFireballIdsByOwner = new ConcurrentHashMap<>();
    private final Map<Long, String> mobFireballOwnerById = new ConcurrentHashMap<>();
    private final Set<Long> poisonMobArrows = ConcurrentHashMap.newKeySet();
    private final ru.nexora.combat.GuardianBeamTracker guardianBeamTracker =
            new ru.nexora.combat.GuardianBeamTracker();
    private final AtomicLong nextMobArrowRuntimeId = new AtomicLong(4_000_000L);
    private final ru.nexora.combat.MobFuseTracker mobFuseTracker =
            new ru.nexora.combat.MobFuseTracker();
    private final Map<Long, Long> endermanTeleportCooldownMillis = new ConcurrentHashMap<>();
    private final ru.nexora.combat.LivingEntityAiService livingEntityAi =
            new ru.nexora.combat.LivingEntityAiService();
    private final ru.nexora.combat.MobPhysicsService mobPhysics =
            new ru.nexora.combat.MobPhysicsService();
    private final ru.nexora.world.MobPathCache mobPathCache =
            new ru.nexora.world.MobPathCache();
    private final ru.nexora.combat.MobStuckTracker mobStuckTracker =
            new ru.nexora.combat.MobStuckTracker();
    private final ru.nexora.combat.MobFallTracker mobFallTracker =
            new ru.nexora.combat.MobFallTracker();
    private final ru.nexora.combat.MobJumpController mobJumpController =
            new ru.nexora.combat.MobJumpController();
    private final ru.nexora.combat.MobClimbController mobClimbController =
            new ru.nexora.combat.MobClimbController();
    private final ru.nexora.combat.MobDespawnTracker mobDespawnTracker =
            new ru.nexora.combat.MobDespawnTracker();
    private final ru.nexora.combat.MobAiLoadController mobAiLoadController =
            new ru.nexora.combat.MobAiLoadController();
    private final ru.nexora.combat.MobAiTaskScheduler mobAiTaskScheduler =
            new ru.nexora.combat.MobAiTaskScheduler();
    private final ru.nexora.ai.HotPathProfiler hotPathProfiler =
            new ru.nexora.ai.HotPathProfiler();
    private final ru.nexora.world.WorldChunkResidencyManager chunkResidency;
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
    private final Object chunkStreamingMonitor = new Object();

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
                equipmentStorage, null, outbound, packetSink, System::nanoTime);
    }

    /** Enables canonical shadow checkpoints while legacy files remain the login authority. */
    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            PlayerInventoryStorage inventoryStorage,
            PlayerEquipmentStorage equipmentStorage,
            PlayerStateCheckpointService checkpointService,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, inventoryStorage,
                equipmentStorage, checkpointService, outbound, packetSink, Integer.MAX_VALUE);
    }

    /** Creates the production session manager with an explicit world residency ceiling. */
    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            PlayerInventoryStorage inventoryStorage,
            PlayerEquipmentStorage equipmentStorage,
            PlayerStateCheckpointService checkpointService,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink,
            int maximumResidentChunks
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, inventoryStorage,
                equipmentStorage, checkpointService, outbound, packetSink, maximumResidentChunks, System::nanoTime);
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
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, inventoryStorage,
                equipmentStorage, null, outbound, packetSink, nanoTime);
    }

    BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            PlayerInventoryStorage inventoryStorage,
            PlayerEquipmentStorage equipmentStorage,
            PlayerStateCheckpointService checkpointService,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink,
            LongSupplier nanoTime
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, inventoryStorage,
                equipmentStorage, checkpointService, outbound, packetSink, Integer.MAX_VALUE, nanoTime);
    }

    BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            PlayerInventoryStorage inventoryStorage,
            PlayerEquipmentStorage equipmentStorage,
            PlayerStateCheckpointService checkpointService,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink,
            int maximumResidentChunks,
            LongSupplier nanoTime
    ) {
        if (maximumResidentChunks < 1) throw new IllegalArgumentException("maximumResidentChunks must be positive");
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
        this.chunkResidency = new ru.nexora.world.WorldChunkResidencyManager(
                BedrockChunkRadiusCodec.DEFAULT_RADIUS, 100,
                ru.nexora.world.WorldChunkResidencyManager.Shape.SQUARE, maximumResidentChunks);
        this.playerStorage = playerStorage;
        this.inventoryStorage = inventoryStorage;
        this.equipmentStorage = equipmentStorage;
        this.checkpointService = checkpointService;
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.packetSink = Objects.requireNonNull(packetSink, "packetSink");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.inbound = new NetherNetInboundPipeline(this::handleCompleteMessage, aiGuard,
                ru.nexora.network.nethernet.InboundTransportConfig.fromEnvironment(System.getenv()));
        this.furnaceUpdateListener = this.world.furnaces()
                .map(manager -> manager.addUpdateListener(this::handleFurnaceUpdate))
                .orElse(null);
    }

    /**
     * Rehydrates durable destination ownership before the network starts accepting logins.
     * The registry is local to this session manager; the transfer ledger is the source of truth.
     */
    public PlayerTransferRecoveryService.RecoveryReport recoverPendingTransferRestores(
            ru.nexora.network.DurableHandoffCoordinator transferLedger,
            String nodeId,
            int limit
    ) throws IOException {
        if (checkpointService == null) {
            throw new IllegalStateException("Canonical player checkpoints are not configured");
        }
        PlayerTransferRecoveryService recovery = new PlayerTransferRecoveryService(
                Objects.requireNonNull(transferLedger, "transferLedger"),
                pendingTransferRestores,
                nodeId,
                System::currentTimeMillis);
        return recovery.recover(limit);
    }

    /**
     * Enables shared-ledger admission fencing before any network session is accepted.
     * This also makes the shared canonical checkpoint authoritative after a transfer.
     */
    public void configureTransferOwnershipGuard(
            ru.nexora.network.DurableHandoffCoordinator transferLedger, String nodeId
    ) {
        Objects.requireNonNull(transferLedger, "transferLedger");
        if (nodeId == null || !nodeId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid transfer ownership node ID");
        }
        if (!sessions.isEmpty()) {
            throw new IllegalStateException("Transfer ownership guard must be configured before sessions are created");
        }
        if (onlineLeaseRegistry == null) {
            throw new IllegalStateException("Global online player leases must be configured before transfer ownership");
        }
        this.transferOwnershipLedger = transferLedger;
        this.transferOwnershipNodeId = nodeId;
    }

    /** Configures the cluster-wide online XUID lease. Must be called before accepting sessions. */
    public void configureOnlinePlayerLeases(
            ru.nexora.network.PlayerOnlineLeaseRegistry registry, long leaseMillis
    ) {
        Objects.requireNonNull(registry, "registry");
        if (leaseMillis < 3_000L || leaseMillis > 120_000L) {
            throw new IllegalArgumentException("Online player lease must be between 3000 and 120000 ms");
        }
        if (!sessions.isEmpty()) {
            throw new IllegalStateException("Online leases must be configured before sessions are created");
        }
        this.onlineLeaseRegistry = registry;
        this.onlineLeaseMillis = leaseMillis;
    }

    private void acquireOnlinePlayerLease(SessionState state) {
        ru.nexora.network.PlayerOnlineLeaseRegistry registry = onlineLeaseRegistry;
        String nodeId = transferOwnershipNodeId;
        if (registry == null || nodeId == null || state.identity == null) return;
        String sessionId = UUID.randomUUID().toString();
        try {
            if (!registry.acquire(state.identity.xuid(), nodeId, sessionId,
                    System.currentTimeMillis(), onlineLeaseMillis)) {
                throw new SecurityException("This player already has an active session on another cluster node");
            }
            state.onlineLeaseSessionId = sessionId;
            state.lastOnlineLeaseRenewalNanos = nanoTime.getAsLong();
            state.onlineLeaseLost = false;
        } catch (IOException failure) {
            throw new SecurityException("Unable to acquire global online player ownership", failure);
        }
    }

    private void ensureOnlinePlayerLeaseForResume(SessionState state) throws IOException {
        ru.nexora.network.PlayerOnlineLeaseRegistry registry = onlineLeaseRegistry;
        String nodeId = transferOwnershipNodeId;
        if (registry == null || nodeId == null) return;
        String sessionId = state.onlineLeaseSessionId;
        long now = System.currentTimeMillis();
        if (sessionId != null && registry.renew(
                state.identity.xuid(), nodeId, sessionId, now, onlineLeaseMillis)) {
            state.lastOnlineLeaseRenewalNanos = nanoTime.getAsLong();
            state.onlineLeaseLost = false;
            return;
        }
        state.onlineLeaseSessionId = null;
        state.lastOnlineLeaseRenewalNanos = Long.MIN_VALUE;
        try {
            acquireOnlinePlayerLease(state);
        } catch (SecurityException leaseFailure) {
            throw new IOException("Unable to reacquire global online player lease", leaseFailure);
        }
    }

    private boolean releaseOnlinePlayerLease(SessionState state) {
        ru.nexora.network.PlayerOnlineLeaseRegistry registry = onlineLeaseRegistry;
        String nodeId = transferOwnershipNodeId;
        String sessionId = state.onlineLeaseSessionId;
        if (registry == null || nodeId == null || sessionId == null || state.identity == null) return true;
        try {
            boolean released = registry.release(state.identity.xuid(), nodeId, sessionId);
            if (released) {
                state.onlineLeaseSessionId = null;
                state.lastOnlineLeaseRenewalNanos = Long.MIN_VALUE;
            }
            return released;
        } catch (IOException failure) {
            System.err.println("NEXORA: unable to release online XUID lease: " + failure.getMessage());
            return false;
        }
    }

    /** Registers a durable claimed transfer for the next authenticated login of this XUID. */
    public void registerPendingTransferRestore(
            PendingPlayerTransferRestoreRegistry.PendingRestore restore
    ) {
        if (checkpointService == null) {
            throw new IllegalStateException("Canonical player checkpoints are not configured");
        }
        pendingTransferRestores.register(restore);
    }

    /** Cancels only the matching local pending registration after a confirmed transfer abort. */
    public boolean cancelPendingTransferRestore(String playerXuid, UUID transferId, long fencingToken) {
        return pendingTransferRestores.cancel(playerXuid, transferId, fencingToken);
    }

    /**
     * Stops a destination session if it has already consumed the matching pending
     * restore. This is used only during rollback while the same CLAIMED fence is owned.
     */
    public boolean deactivateTransferRestore(String playerXuid, UUID transferId, long fencingToken) {
        boolean cancelled = pendingTransferRestores.cancel(playerXuid, transferId, fencingToken);
        boolean stopped = false;
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState state = entry.getValue();
            state.combatLock.lock();
            try {
                synchronized (state) {
                    PlayerTransferFence fence = state.transferFence;
                    if (fence == null || !playerXuid.equals(fence.playerXuid())
                            || !transferId.equals(fence.transferId())
                            || fencingToken != fence.fencingToken()) continue;
                    state.rejected = true;
                    state.loginAuthenticated = false;
                    state.clientInitialized = false;
                    releaseOnlinePlayerLease(state);
                    removePlayerListEntry(entry.getKey(), state);
                    stopped = true;
                }
            } finally {
                state.combatLock.unlock();
            }
        }
        return cancelled || stopped;
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
        long startedNanos = nanoTime.getAsLong();
        try {
            inbound.onFrame(networkId, channelLabel, frame);
        } finally {
            drainOutboundForSession(networkId);
            hotPathProfiler.record(ru.nexora.ai.HotPathProfiler.Path.BEDROCK_INBOUND,
                    Math.max(0L, nanoTime.getAsLong() - startedNanos));
        }
    }

    @Override
    public void onDisconnected(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state != null) {
            boolean alreadyDisconnecting;
            synchronized (state) {
                alreadyDisconnecting = state.disconnecting;
                if (!alreadyDisconnecting) {
                    state.disconnecting = true;
                    state.rejected = true;
                    // Drop session-owned references without cancelling shared, deduplicated
                    // world I/O futures that another connected player may also be awaiting.
                    state.pendingChunkLoads.clear();
                    state.chunkStreamingQueue = null;
                    state.sentChunkKeys.clear();
                }
            }
            if (alreadyDisconnecting) {
                inbound.onDisconnected(networkId);
                return;
            }
            // Release partial inbound fragments and per-peer admission state before
            // persistence can block this disconnect callback.
            inbound.onDisconnected(networkId);
            synchronized (state.outboundLock) {
                state.outboundClosed = true;
                clearOutboundQueue(state);
            }
            removePlayerListEntry(networkId, state);
            if (state.identity != null) aiGuard.onPlayerDisconnected(state.identity.xuid());
            boolean persistenceSucceeded = true;
            state.combatLock.lock();
            try {
                synchronized (state) {
                    if (!state.transferFrozen) {
                        saveAndClearContainerWindow(state);
                        try {
                            // A disconnect must not let an expired former owner
                            // overwrite the canonical checkpoint now owned elsewhere.
                            if (onlineLeaseRegistry != null && state.onlineLeaseSessionId == null) {
                                throw new IOException("Disconnect persistence has no global online ownership lease");
                            }
                            assertTransferNodeOwnership(state);
                            returnCraftingGridItems(state);
                            saveAllPlayerState(state);
                        } catch (IOException | RuntimeException exception) {
                            persistenceSucceeded = false;
                            System.err.println("NEXORA: unable to save disconnected player: " + exception.getMessage());
                        }
                    }
                }
            } finally {
                state.combatLock.unlock();
            }
            // Keep ownership fenced until TTL expiry when the final disconnect
            // snapshot failed; releasing it would admit a second node with stale data.
            if (persistenceSucceeded) releaseOnlinePlayerLease(state);
            synchronized (state.outboundLock) {
                state.outboundClosed = true;
                clearOutboundQueue(state);
            }
            chunkStreamingBudget.unregister(networkId);
            outboundDrainBudget.unregister(networkId);
            sessions.remove(networkId, state);
            actorReplicationIndex.removeSession(networkId);
            itemActorReplicationIndex.removeSession(networkId);
            playerActorReplicationIndex.removeSession(networkId);
            synchronized (sessionChangeMonitor) {
                sessionChangeMonitor.notifyAll();
            }
        } else {
            inbound.onDisconnected(networkId);
        }
    }

    /**
     * Waits for active network sessions to disconnect during graceful shutdown.
     * Returns false when the bounded timeout expires; callers must then force-close
     * transport and let the gameplay module persist remaining sessions.
     */
    public boolean awaitNoActiveSessions(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        long started = System.nanoTime();
        long budget = timeout.toNanos();
        synchronized (sessionChangeMonitor) {
            while (!sessions.isEmpty()) {
                long remaining = budget - (System.nanoTime() - started);
                if (remaining <= 0) return false;
                long millis = remaining / 1_000_000L;
                int nanos = (int) (remaining % 1_000_000L);
                sessionChangeMonitor.wait(millis, nanos);
            }
        }
        return true;
    }

    /**
     * Periodic lease heartbeat independent of client movement/input packets.
     * Idle authenticated players must not lose ownership simply because they
     * have no gameplay packet to trigger the inbound-path renewal.
     */
    public void renewOnlinePlayerLeases() {
        if (onlineLeaseRegistry == null || transferOwnershipLedger == null) return;
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            String networkId = entry.getKey();
            SessionState state = entry.getValue();
            NetherNetClientIdentity identity = state.identity;
            if (identity == null || state.rejected) continue;

            // Renew outside the gameplay lock. A long checkpoint/save/transfer
            // must never prevent the lease heartbeat from extending ownership.
            String leaseSessionId = state.onlineLeaseSessionId;
            if (leaseSessionId != null) {
                long nowNanos = nanoTime.getAsLong();
                long previous = state.lastOnlineLeaseRenewalNanos;
                long interval = Math.max(500_000_000L, onlineLeaseMillis * 1_000_000L / 3L);
                if (previous == Long.MIN_VALUE || nowNanos - previous >= interval) {
                    boolean renewed;
                    try {
                        renewed = onlineLeaseRegistry.renew(identity.xuid(), transferOwnershipNodeId,
                                leaseSessionId, System.currentTimeMillis(), onlineLeaseMillis);
                    } catch (IOException | RuntimeException failure) {
                        renewed = false;
                    }
                    if (!renewed) {
                        // A concurrent transfer release may have cleared the token
                        // after this heartbeat captured it. Fence only if it is
                        // still the session's current lease.
                        if (leaseSessionId.equals(state.onlineLeaseSessionId)) {
                            state.onlineLeaseLost = true;
                            state.rejected = true;
                            state.loginAuthenticated = false;
                            state.clientInitialized = false;
                            releaseOnlinePlayerLease(state);
                            removePlayerListEntry(networkId, state);
                            System.err.println("NEXORA: stopped session after online lease loss for "
                                    + identity.xuid());
                        }
                        continue;
                    }
                    if (leaseSessionId.equals(state.onlineLeaseSessionId)) {
                        state.lastOnlineLeaseRenewalNanos = nowNanos;
                    }
                }
            } else if (state.loginAuthenticated && !state.transferFrozen) {
                state.onlineLeaseLost = true;
                state.rejected = true;
                state.loginAuthenticated = false;
                state.clientInitialized = false;
                removePlayerListEntry(networkId, state);
                System.err.println("NEXORA: stopped session without a global online lease for " + identity.xuid());
                continue;
            }

            // A frozen source still owns its lease until PREPARED is durable,
            // but it must not be checked against the PREPARED gameplay fence.
            if (state.transferFrozen || state.rejected || state.onlineLeaseLost || !state.loginAuthenticated) continue;
            state.combatLock.lock();
            try {
                synchronized (state) {
                    if (state.transferFrozen || state.rejected || !state.loginAuthenticated) continue;
                    try {
                        assertTransferNodeOwnership(state);
                    } catch (IOException | RuntimeException ownershipFailure) {
                        state.onlineLeaseLost = true;
                        state.rejected = true;
                        state.loginAuthenticated = false;
                        state.clientInitialized = false;
                        releaseOnlinePlayerLease(state);
                        removePlayerListEntry(networkId, state);
                        System.err.println("NEXORA: stopped idle/session processing after ownership loss for "
                                + identity.xuid() + ": " + ownershipFailure.getMessage());
                    }
                }
            } finally {
                state.combatLock.unlock();
            }
        }
    }

    public void savePlayers() throws IOException {
        IOException failure = null;
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            String networkId = entry.getKey();
            SessionState state = entry.getValue();
            synchronized (state) {
                if (state.transferFrozen) continue;
                if (state.onlineLeaseLost) {
                    IOException ownershipFailure = new IOException(
                            "Player persistence was skipped after global online ownership lease loss");
                    if (failure == null) failure = ownershipFailure;
                    else failure.addSuppressed(ownershipFailure);
                    continue;
                }
                if (state.rejected || !state.loginAuthenticated) continue;
                try {
                    // Autosave must retain the lease and verify it before any
                    // shared checkpoint write; releasing here would open a
                    // duplicate-login window on every save interval.
                    assertTransferNodeOwnership(state);
                } catch (IOException | SecurityException ownershipFailure) {
                    state.rejected = true;
                    state.loginAuthenticated = false;
                    state.clientInitialized = false;
                    releaseOnlinePlayerLease(state);
                    removePlayerListEntry(networkId, state);
                    IOException wrapped = new IOException(
                            "Stopped player autosave after global ownership validation failed",
                            ownershipFailure);
                    if (failure == null) failure = wrapped;
                    else failure.addSuppressed(wrapped);
                    continue;
                }
                try {
                    saveAllPlayerState(state);
                } catch (IOException saveFailure) {
                    if (failure == null) failure = saveFailure;
                    else failure.addSuppressed(saveFailure);
                }
            }
        }
        if (failure != null) throw failure;
    }

    /**
     * Freezes one authenticated player on this node and writes a canonical checkpoint.
     * The returned revision is the durable revision the destination must restore.
     */
    public long freezePlayerForTransfer(String playerXuid) throws IOException {
        if (checkpointService == null) {
            throw new IllegalStateException("Canonical player checkpoints are not configured");
        }
        if (playerXuid == null || !playerXuid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid player XUID");
        }
        Map.Entry<String, SessionState> match = null;
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState candidate = entry.getValue();
            synchronized (candidate) {
                if (candidate.identity != null && playerXuid.equals(candidate.identity.xuid())) {
                    if (match != null) throw new IllegalStateException("Duplicate active XUID on source node");
                    match = entry;
                }
            }
        }
        if (match == null) throw new IllegalArgumentException("Player is not connected to this source node");
        String networkId = match.getKey();
        SessionState state = match.getValue();
        long revision;
        state.combatLock.lock();
        try {
            synchronized (state) {
                if (sessions.get(networkId) != state || state.rejected || state.transferFrozen
                        || !state.loginAuthenticated || !state.clientInitialized
                        || state.identity == null || !playerXuid.equals(state.identity.xuid())
                        || !state.positionInitialized) {
                    throw new IllegalStateException("Player session is not eligible for transfer");
                }
                // Do not freeze or checkpoint from a session that has already
                // lost its cluster-wide ownership lease.
                assertTransferNodeOwnership(state);
                state.transferFrozen = true;
                try {
                    if (state.openContainerWindowId != 0) closeContainerWindow(networkId, state, true);
                    returnCraftingGridItems(state);
                    revision = saveAllPlayerStateForTransfer(state);
                    if (revision < 0) throw new IOException("Canonical checkpoint was not written");
                } catch (IOException | RuntimeException failure) {
                    state.transferFrozen = false;
                    throw failure;
                }
                // Stop inbound gameplay and every tick path gated by authenticated/initialized state.
                state.loginAuthenticated = false;
                state.clientInitialized = false;
            }
        } finally {
            state.combatLock.unlock();
        }
        // Keep the source lease until PREPARED is durable. The source coordinator
        // releases it through releaseOnlinePlayerLeaseAfterPreparedTransfer().
        removePlayerListEntry(networkId, state);
        return revision;
    }

    /**
     * Releases the frozen source session's online XUID lease after its exact
     * transfer is durably PREPARED or has advanced to CLAIMED/COMMITTED.
     * Until this point the source retains the lease, preventing an early
     * destination login in the checkpoint/publication gap.
     */
    public void releaseOnlinePlayerLeaseAfterPreparedTransfer(String playerXuid, UUID transferId)
            throws IOException {
        Objects.requireNonNull(transferId, "transferId");
        ru.nexora.network.DurableHandoffCoordinator ledger = transferOwnershipLedger;
        String nodeId = transferOwnershipNodeId;
        if (onlineLeaseRegistry == null) return;
        if (ledger == null || nodeId == null) {
            throw new IOException("Shared transfer ownership is not configured");
        }
        ru.nexora.network.PlayerTransferRecord record = ledger.get(transferId)
                .orElseThrow(() -> new IOException("Prepared transfer is not present in the durable ledger"));
        if ((record.state() != ru.nexora.network.PlayerTransferRecord.State.PREPARED
                && record.state() != ru.nexora.network.PlayerTransferRecord.State.CLAIMED
                && record.state() != ru.nexora.network.PlayerTransferRecord.State.COMMITTED)
                || !record.playerXuid().equals(playerXuid)
                || !record.sourceNode().equals(nodeId)) {
            throw new SecurityException("Durable transfer record does not match frozen source player");
        }
        Map.Entry<String, SessionState> match = null;
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState candidate = entry.getValue();
            synchronized (candidate) {
                if (candidate.identity != null && playerXuid.equals(candidate.identity.xuid())
                        && candidate.transferFrozen) {
                    if (match != null) throw new IOException("Duplicate frozen XUID on source node");
                    match = entry;
                }
            }
        }
        if (match == null) throw new IOException("Frozen source session is no longer available");
        SessionState state = match.getValue();
        state.combatLock.lock();
        try {
            synchronized (state) {
                if (sessions.get(match.getKey()) != state || !state.transferFrozen
                        || state.identity == null || !playerXuid.equals(state.identity.xuid())) {
                    throw new IOException("Source session changed before PREPARED lease release");
                }
                if (state.onlineLeaseLost) {
                    throw new IOException("Source lost its online ownership lease during transfer freeze");
                }
                if (state.onlineLeaseSessionId == null) {
                    throw new IOException("Source online ownership lease is missing after PREPARED publication");
                }
                if (!releaseOnlinePlayerLease(state)) {
                    throw new IOException("Unable to release source online lease after PREPARED publication");
                }
            }
        } finally {
            state.combatLock.unlock();
        }
    }

    /**
     * Resumes a source session only after the transfer ledger has durably confirmed ABORTED.
     * A disconnected source session is deliberately not recreated here.
     */
    public void unfreezePlayerAfterTransferAbort(String playerXuid) throws IOException {
        if (playerXuid == null || !playerXuid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid player XUID");
        }
        Map.Entry<String, SessionState> match = null;
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState candidate = entry.getValue();
            synchronized (candidate) {
                if (candidate.identity != null && playerXuid.equals(candidate.identity.xuid())
                        && candidate.transferFrozen) {
                    if (match != null) throw new IllegalStateException("Duplicate frozen XUID on source node");
                    match = entry;
                }
            }
        }
        if (match == null) throw new IOException("Frozen source session is no longer available");
        String networkId = match.getKey();
        SessionState state = match.getValue();
        state.combatLock.lock();
        try {
            synchronized (state) {
                if (sessions.get(networkId) != state || !state.transferFrozen || state.rejected
                        || state.identity == null || !playerXuid.equals(state.identity.xuid())) {
                    throw new IOException("Source session cannot be safely resumed");
                }
                ensureOnlinePlayerLeaseForResume(state);
                state.loginAuthenticated = true;
                state.clientInitialized = true;
                state.transferFrozen = false;
            }
        } finally {
            state.combatLock.unlock();
        }
        try {
            sendContainerReconciliation(networkId, state);
        } catch (RuntimeException exception) {
            System.err.println("NEXORA: unable to reconcile inventory after transfer abort: "
                    + exception.getMessage());
        }
        advertisePlayerList(networkId, state);
    }

    /**
     * Recovers a source session frozen before PREPARED could be published.
     * Resume is allowed only when the shared ledger confirms no transfer record
     * and this process can still acquire/renew the exclusive online lease.
     */
    private boolean resumeFrozenPlayerWithoutTransfer(String playerXuid) throws IOException {
        if (onlineLeaseRegistry == null || transferOwnershipNodeId == null) return false;
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState state = entry.getValue();
            state.combatLock.lock();
            try {
                synchronized (state) {
                    if (state.identity == null || !playerXuid.equals(state.identity.xuid())
                            || !state.transferFrozen || state.rejected || state.transferFence != null) continue;
                    try {
                        ensureOnlinePlayerLeaseForResume(state);
                    } catch (SecurityException leaseUnavailable) {
                        return false;
                    }
                    state.loginAuthenticated = true;
                    state.clientInitialized = true;
                    state.transferFrozen = false;
                }
            } finally {
                state.combatLock.unlock();
            }
            try {
                sendContainerReconciliation(entry.getKey(), state);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to reconcile inventory after orphaned freeze: "
                        + exception.getMessage());
            }
            advertisePlayerList(entry.getKey(), state);
            return true;
        }
        return false;
    }

    /**
     * Redirects a source session only after the transfer coordinator has durably
     * committed ownership to the destination. The session must already be frozen.
     */
    public void redirectFrozenPlayerToNode(
            String playerXuid,
            UUID transferId,
            long fencingToken,
            ru.nexora.network.DurableHandoffCoordinator transferLedger,
            String localNodeId,
            String address,
            int port
    ) throws IOException {
        if (playerXuid == null || !playerXuid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid player XUID");
        }
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(transferLedger, "transferLedger");
        if (fencingToken <= 0 || localNodeId == null || !localNodeId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid transfer fence or local node ID");
        }
        ru.nexora.network.PlayerTransferRecord committed = transferLedger.get(transferId)
                .orElseThrow(() -> new IOException("Committed transfer record is unavailable"));
        if (committed.state() != ru.nexora.network.PlayerTransferRecord.State.COMMITTED
                || committed.fencingToken() != fencingToken
                || !playerXuid.equals(committed.playerXuid())
                || !localNodeId.equals(committed.sourceNode())
                || localNodeId.equals(committed.targetNode())) {
            throw new SecurityException("Bedrock redirect requires the matching durable COMMITTED transfer");
        }
        Map.Entry<String, SessionState> match = null;
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState candidate = entry.getValue();
            synchronized (candidate) {
                if (candidate.identity != null && playerXuid.equals(candidate.identity.xuid())
                        && candidate.transferFrozen && !candidate.transferRedirected) {
                    if (match != null) throw new IllegalStateException("Duplicate frozen XUID on source node");
                    match = entry;
                }
            }
        }
        if (match == null) throw new IllegalArgumentException("Player has no redirectable frozen source session");
        String networkId = match.getKey();
        SessionState state = match.getValue();
        state.combatLock.lock();
        try {
            synchronized (state) {
                if (sessions.get(networkId) != state || !state.transferFrozen || state.transferRedirected
                        || state.rejected || state.identity == null
                        || !playerXuid.equals(state.identity.xuid()) || !state.compressionNegotiated) {
                    throw new IllegalStateException("Frozen source session is not eligible for redirect");
                }
                sendPacket(networkId, BedrockTransferPacketCodec.create(address, port, true), true);
                state.transferRedirected = true;
                state.rejected = true;
            }
        } finally {
            state.combatLock.unlock();
        }
    }

    /**
     * Reconciles frozen source sessions after a peer/database restart. Only a
     * durable ABORTED record resumes gameplay; only COMMITTED ownership redirects.
     */
    public TransferReconciliationReport reconcileFrozenPlayerTransfers(
            ru.nexora.network.DurableHandoffCoordinator ledger,
            String localNodeId,
            Map<String, java.net.InetSocketAddress> bedrockRoutes
    ) throws IOException {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(bedrockRoutes, "bedrockRoutes");
        if (localNodeId == null || !localNodeId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid local transfer node ID");
        }
        List<String> frozenXuids = new ArrayList<>();
        for (SessionState state : sessions.values()) {
            synchronized (state) {
                if (state.identity != null && state.transferFrozen && !state.rejected
                        && !state.transferRedirected) {
                    frozenXuids.add(state.identity.xuid());
                }
            }
        }
        int resumed = 0;
        int redirected = 0;
        int unresolved = 0;
        long now = System.currentTimeMillis();
        for (String xuid : frozenXuids) {
            java.util.Optional<ru.nexora.network.PlayerTransferRecord> latest =
                    ledger.findLatestForPlayer(xuid);
            if (latest.isEmpty()) {
                if (resumeFrozenPlayerWithoutTransfer(xuid)) resumed++;
                else unresolved++;
                continue;
            }
            ru.nexora.network.PlayerTransferRecord record = latest.orElseThrow();
            if (!localNodeId.equals(record.sourceNode())) {
                unresolved++;
                continue;
            }
            if (record.state() == ru.nexora.network.PlayerTransferRecord.State.PREPARED
                    && now >= record.updatedAtEpochMillis()
                    && now - record.updatedAtEpochMillis() >= 30_000L) {
                // A stale PREPARED record has not been claimed. The ledger CAS
                // arbitrates against a destination that may claim concurrently.
                ledger.abortPrepared(record.transferId(), now);
                java.util.Optional<ru.nexora.network.PlayerTransferRecord> afterAbort =
                        ledger.findLatestForPlayer(xuid);
                if (afterAbort.isPresent()
                        && afterAbort.orElseThrow().transferId().equals(record.transferId())
                        && afterAbort.orElseThrow().state()
                        == ru.nexora.network.PlayerTransferRecord.State.ABORTED) {
                    unfreezePlayerAfterTransferAbort(xuid);
                    resumed++;
                } else {
                    unresolved++;
                }
            } else if (record.state() == ru.nexora.network.PlayerTransferRecord.State.ABORTED) {
                unfreezePlayerAfterTransferAbort(xuid);
                resumed++;
            } else if (record.state() == ru.nexora.network.PlayerTransferRecord.State.COMMITTED) {
                java.net.InetSocketAddress route = bedrockRoutes.get(record.targetNode());
                if (route == null) {
                    unresolved++;
                    continue;
                }
                redirectFrozenPlayerToNode(xuid, record.transferId(), record.fencingToken(),
                        ledger, localNodeId, route.getHostString(), route.getPort());
                redirected++;
            } else {
                unresolved++;
            }
        }
        return new TransferReconciliationReport(
                frozenXuids.size(), resumed, redirected, unresolved);
    }

    private void saveAllPlayerState(SessionState state) throws IOException {
        // The shared canonical snapshot is the cluster authority; write it
        // before updating host-local compatibility files.
        checkpointCanonicalPlayerState(state);
        savePlayer(state);
        saveInventory(state);
        saveEquipment(state);
    }

    private long saveAllPlayerStateForTransfer(SessionState state) throws IOException {
        long revision = checkpointCanonicalPlayerState(state, true);
        savePlayer(state);
        saveInventory(state);
        saveEquipment(state);
        return revision;
    }

    /** Revalidates destination ownership before any player persistence write. */
    private static void assertTransferFence(SessionState state) throws IOException {
        if (state.transferFence != null) state.transferFence.assertCommitted();
    }

    /**
     * Writes a canonical aggregate after all legacy components have been saved.
     * Legacy stores remain the login authority until an explicit migration protocol
     * can prove that the aggregate is newer and complete.
     */
    private void checkpointCanonicalPlayerState(SessionState state) throws IOException {
        checkpointCanonicalPlayerState(state, false);
    }

    private long checkpointCanonicalPlayerState(SessionState state, boolean forceTransferRevision) throws IOException {
        assertTransferFence(state);
        if (checkpointService == null || !state.loginAuthenticated || !state.positionInitialized
                || state.identity == null) return -1L;
        String displayName = state.identity.displayName() == null ? "unknown" : state.identity.displayName();
        PlayerData profile = new PlayerData(
                state.identity.xuid(), displayName, world.name(),
                state.positionX, state.positionY, state.positionZ,
                state.yaw, state.pitch, System.currentTimeMillis(), state.vitals,
                state.experiencePoints, state.experienceRemainderMilli);
        PlayerStateBundle bundle = PlayerStateBundle.capture(
                profile, state.inventory, state.craftingUiInventory, state.equipment);
        if (forceTransferRevision) {
            return checkpointService.checkpointForTransfer(bundle, profile.lastSeenEpochMillis());
        }
        ru.nexora.persistence.PlayerStateStore.SaveResult saveResult =
                checkpointService.checkpoint(bundle, profile.lastSeenEpochMillis());
        if (saveResult == ru.nexora.persistence.PlayerStateStore.SaveResult.STALE) {
            throw new IOException("Canonical player checkpoint was superseded by a newer owner revision");
        }
        return -1L;
    }

    private void savePlayer(SessionState state) throws IOException {
        assertTransferFence(state);
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
        assertTransferFence(state);
        if (inventoryStorage == null || !state.loginAuthenticated || state.identity == null) return;
        inventoryStorage.save(state.identity.xuid(), state.inventory, state.craftingUiInventory);
    }

    private void saveEquipment(SessionState state) throws IOException {
        assertTransferFence(state);
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

    /** Clears only the transient path cache; world and entity state are not modified. */
    public void clearMobPathCache() {
        mobPathCache.clear();
    }

    public int mobPathCacheSize() {
        return mobPathCache.size();
    }

    public int expiredMobPathCacheEntries() {
        return mobPathCache.expiredEntries(Math.max(0L, nanoTime.getAsLong() / 1_000_000L));
    }

    public int pruneExpiredMobPathCache() {
        return mobPathCache.pruneExpired(Math.max(0L, nanoTime.getAsLong() / 1_000_000L));
    }

    private String hotPathProfilerSummary() {
        StringBuilder summary = new StringBuilder();
        for (ru.nexora.ai.HotPathProfiler.Path path : ru.nexora.ai.HotPathProfiler.Path.values()) {
            ru.nexora.ai.HotPathProfiler.Snapshot snapshot = hotPathProfiler.snapshot(path);
            if (snapshot.sampleCount() == 0) continue;
            if (summary.length() > 0) summary.append("; ");
            summary.append(path.name()).append("[").append(snapshot.summary()).append("]");
        }
        return summary.length() == 0 ? "no samples" : summary.toString();
    }

    /** Read-only mob navigation/cache diagnostics for trusted operator console output. */
    public String mobAiDiagnosticsSummary() {
        ru.nexora.world.MobPathCache.Stats cache = mobPathCache.stats();
        ru.nexora.combat.MobStuckTracker.Stats stuck = mobStuckTracker.stats(Math.max(0L, nanoTime.getAsLong() / 1_000_000L));
        ru.nexora.combat.LivingEntityAiService.Stats aiStats = livingEntityAi.stats();
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
                .append("\nMob AI load controller: ").append(mobAiLoadController.stats().summary())
                .append("\nMob AI budget: ").append(mobAiLoadController.budgetStats().summary())
                .append("\nMob AI tasks: ").append(mobAiTaskScheduler.stats().summary())
                .append("\nHot paths: ").append(hotPathProfilerSummary())
                .append("\nMob AI decision algorithm: decisions=").append(aiStats.decisionCount())
                .append(", total=").append(aiStats.totalDecisionNanos()).append(" ns")
                .append(", avg=").append(aiStats.averageDecisionNanos()).append(" ns")
                .append(", last=").append(aiStats.lastDecisionNanos()).append(" ns")
                .append(", max=").append(aiStats.maxDecisionNanos()).append(" ns")
                .append(", failures=").append(aiStats.failures())
                .append("\nMob fall tracker: ").append(mobFallTracker.stats().summary())
                .append("\nMob jump controller: ").append(mobJumpController.stats().summary())
                .append("\nMob climb controller: ").append(mobClimbController.stats().summary())
                .append("\nMob spawners: ").append(world.mobSpawners().stats().summary())
                .append("\nMob despawn tracker: tracked=").append(mobDespawnTracker.stats().tracked())
                .append(", evictions=").append(mobDespawnTracker.stats().evictions());
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

    /** Number of active per-peer inbound framing channels, exposed for bounded-load diagnostics. */
    public int activeInboundChannelCount() {
        return inbound.activeChannelCount();
    }

    /** Aggregate frame/dispatch measurements for operator diagnostics and load tests. */
    public NetherNetInboundPipeline.InboundStats inboundStats() {
        return inbound.stats();
    }

    public ru.nexora.network.nethernet.InboundPeerBudget.Stats inboundPeerBudgetStats() {
        return inbound.peerBudgetStats();
    }

    public long authenticatedPlayerCount() {
        return sessions.values().stream().filter(state -> state.loginAuthenticated).count();
    }

    /** Immutable authenticated player identities for operator routing and diagnostics. */
    public List<AuthenticatedPlayer> authenticatedPlayers() {
        List<AuthenticatedPlayer> players = new ArrayList<>();
        for (SessionState state : sessions.values()) {
            synchronized (state) {
                if (state.identity == null || !state.loginAuthenticated || !state.clientInitialized
                        || state.rejected || state.transferFrozen) continue;
                players.add(new AuthenticatedPlayer(
                        state.identity.xuid(), state.identity.displayName(), state.entityId));
            }
        }
        players.sort(java.util.Comparator.comparing(AuthenticatedPlayer::displayName,
                String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(players);
    }

    public long resourcePackReadyCount() {
        return sessions.values().stream().filter(state -> state.resourcePacksCompleted).count();
    }

    /** Read-only aggregate world/session counters for the authenticated operator assistant. */
    public String adminRuntimeSummary() {
        ru.nexora.combat.LivingEntityAiService.Stats ai = livingEntityAi.stats();
        ru.nexora.network.nethernet.InboundPeerBudget.Stats ingress = inbound.peerBudgetStats();
        ru.nexora.network.nethernet.ReassemblyMemoryBudget.Stats reassembly = inbound.reassemblyMemoryStats();
        StringBuilder summary = new StringBuilder("players=").append(authenticatedPlayerCount())
                .append(", sessions=").append(activeSessionCount())
                .append(", mobs=").append(world.livingEntities().snapshot().size())
                .append(", droppedItems=").append(world.itemEntities().snapshot().size())
                .append(", mobAiDecisions=").append(ai.decisionCount())
                .append(", mobAiAvgNs=").append(ai.averageDecisionNanos())
                .append(", mobAiMaxNs=").append(ai.maxDecisionNanos())
                .append(", mobAiFailures=").append(ai.failures())
                .append(", outboundFramesSent=").append(outboundFramesSent.get())
                .append(", outboundBytesSent=").append(outboundBytesSent.get())
                .append(", outboundSendFailures=").append(outboundSendFailures.get())
                .append(", outboundBackpressureRejections=").append(outboundBackpressureRejections.get())
                .append(", inboundAdmittedFrames=").append(ingress.admittedFrames())
                .append(", inboundAdmittedBytes=").append(ingress.admittedBytes())
                .append(", inboundRejectedFrames=").append(ingress.rejectedFrames())
                .append(", inboundRejectedBytes=").append(ingress.rejectedBytes())
                .append(", inboundTrackedPeers=").append(ingress.trackedPeers())
                .append(", inboundReassemblyReservedBytes=").append(reassembly.reservedBytes())
                .append(", inboundReassemblyMaxBytes=").append(reassembly.maxBytes())
                .append(", inboundReassemblyRejectedReservations=").append(reassembly.rejectedReservations())
                .append(", outboundQueuedFrames=").append(sessions.values().stream()
                        .mapToInt(state -> state.outboundQueue.frameCount()).sum())
                .append(", outboundQueuedBytes=").append(sessions.values().stream()
                        .mapToLong(state -> state.outboundQueue.queuedBytes()).sum())
                .append(", outboundQueuedBytesGlobal=").append(outboundQueuedBytesGlobal.get())
                .append(", outboundDrainBudgetFrames=").append(outboundDrainBudget.globalFrames(nanoTime.getAsLong()))
                .append(", outboundDrainBudgetBytes=").append(outboundDrainBudget.globalBytes(nanoTime.getAsLong()))
                .append(", outboundDrainBudgetSessions=").append(outboundDrainBudget.queuedSessions())
                .append(", chunkStreamingBatches=").append(chunkStreamingBatches.get())
                .append(", chunkStreamingCompleted=").append(chunkStreamingCompleted.get())
                .append(", chunkPacketsEncoded=").append(chunkPacketsEncoded.get())
                .append(", chunkPacketsReused=").append(chunkPacketsReused.get())
                .append(", chunkStreamingPending=").append(sessions.values().stream()
                        .mapToInt(state -> state.chunkStreamingQueue == null
                                ? 0 : state.chunkStreamingQueue.remaining()).sum())
                .append(", chunkStreamingPendingPeak=").append(chunkStreamingPendingPeak.get())
                .append(", worldChunkLoadsDeferred=").append(world.pendingChunkLoadCount())
                .append(", worldChunkLoadBudgetPerTick=").append(MAX_WORLD_CHUNK_LOADS_PER_TICK)
                .append(", chunkIoWorkers=").append(world.activeChunkIoWorkers())
                .append(", chunkIoQueued=").append(world.queuedChunkIoLoads())
                .append(", chunkIoInFlight=").append(world.inFlightChunkIoLoads())
                .append(", chunkEncodingBudgetUsed=").append(chunkStreamingBudget.globalUsage(nanoTime.getAsLong()))
                .append(", chunkEncodingBudgetSessions=").append(chunkStreamingBudget.queuedSessions())
                .append(", chunkStreamingP95Ns=").append(hotPathProfiler.snapshot(
                        ru.nexora.ai.HotPathProfiler.Path.BEDROCK_CHUNK_STREAMING).p95Nanos())
                .append(", outboundDrainP95Ns=").append(hotPathProfiler.snapshot(
                        ru.nexora.ai.HotPathProfiler.Path.BEDROCK_OUTBOUND_DRAIN).p95Nanos());
        ru.nexora.ai.NexoraAiDiagnostics diagnostics = aiDiagnostics;
        if (diagnostics != null) {
            ru.nexora.ai.JvmResourceSnapshot resources = diagnostics.resourceSnapshot();
            summary.append(", heapUsed=").append(resources.heapUsedBytes())
                    .append(", heapCommitted=").append(resources.heapCommittedBytes())
                    .append(", heapMax=").append(resources.heapMaxBytes())
                    .append(", nonHeapUsed=").append(resources.nonHeapUsedBytes())
                    .append(", threads=").append(resources.liveThreadCount())
                    .append(", peakThreads=").append(resources.peakThreadCount());
        }
        return summary.toString();
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
        sendPackets(networkId, packets, true);
        broadcastPlayerEquipment(networkId, state);
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
            if (state.rejected || state.transferFrozen) return;
            if (state.identity != null) {
                try {
                    assertTransferNodeOwnership(state);
                } catch (IOException | SecurityException ownershipLost) {
                    state.rejected = true;
                    state.loginAuthenticated = false;
                    state.clientInitialized = false;
                    releaseOnlinePlayerLease(state);
                    removePlayerListEntry(message.networkId(), state);
                    System.err.println("NEXORA: stopped Bedrock session after node ownership changed: "
                            + ownershipLost.getMessage());
                    return;
                }
            }
            if (state.transferFence != null) {
                try {
                    state.transferFence.assertCurrent();
                } catch (IOException staleFence) {
                    state.rejected = true;
                    state.loginAuthenticated = false;
                    state.clientInitialized = false;
                    removePlayerListEntry(message.networkId(), state);
                    System.err.println("NEXORA: stopped Bedrock session after transfer ownership was lost: "
                            + staleFence.getMessage());
                    return;
                }
            }

            if (!state.compressionNegotiated) {
                handleNetworkSettingsRequest(message, state);
                return;
            }

            List<BedrockPacket> packets = BedrockBatchCodec.decode(message.payload(), true);
            boolean resourcePacksInfoWasSent = state.resourcePacksInfoSent;
            boolean resourcePackStackWasSent = state.resourcePackStackSent;
            for (BedrockPacket packet : packets) {
                if (state.transferFence != null) {
                    try {
                        state.transferFence.assertCurrent();
                    } catch (IOException staleFence) {
                        state.rejected = true;
                        state.loginAuthenticated = false;
                        state.clientInitialized = false;
                        releaseOnlinePlayerLease(state);
                        removePlayerListEntry(message.networkId(), state);
                        System.err.println("NEXORA: stopped Bedrock batch after transfer ownership was lost: "
                                + staleFence.getMessage());
                        return;
                    }
                }
                switch (packet.packetId()) {
                    case BedrockLoginPacketParser.LOGIN_PACKET_ID -> handleLogin(message, state, packet);
                    case BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID ->
                            handleClientHandshake(message.networkId(), state, packet);
                    case BedrockBatchCodec.RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID ->
                            handleResourcePackResponse(message.networkId(), state, packet,
                                    resourcePacksInfoWasSent, resourcePackStackWasSent);
                    case BedrockSubChunkCodec.SUB_CHUNK_REQUEST_PACKET_ID ->
                            handleSubChunkRequest(message, state, packet);
                    case 0x71 -> handleClientInitialized(message, state, packet);
                    case BedrockPacketViolationWarningCodec.PACKET_ID ->
                            handlePacketViolationWarning(message.networkId(), packet);
                    case BedrockChunkRadiusCodec.REQUEST_PACKET_ID ->
                            handleChunkRadiusRequest(message, state, packet);
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
                    case BedrockTextCodec.TEXT_PACKET_ID -> handleChatMessage(message, state, packet);
                    case BedrockCommandRequestCodec.COMMAND_REQUEST_PACKET_ID ->
                            handleCommandRequest(message, state, packet);
                    case BedrockModalFormCodec.MODAL_FORM_RESPONSE_PACKET_ID ->
                            handleModalFormResponse(message, state, packet);
                    case BedrockTakeItemActorCodec.TAKE_ITEM_ACTOR_PACKET_ID ->
                            handleTakeItemActor(message, state, packet);
                    case BedrockLoadingScreenCodec.PACKET_ID ->
                            handleLoadingScreen(message, state, packet);
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
        dropPlayerInventoryOnDeath(networkId, state);
        state.deathPacketsSent = true;
        sendPackets(networkId, List.of(
                BedrockRespawnCodec.createDeathInfo(deathCause),
                BedrockRespawnCodec.createSearchingForSpawn(
                        world.spawnX() + 0.5, world.spawnY(), world.spawnZ() + 0.5, state.entityId)
        ), true);
    }

    /** Durably spills registered inventory stacks on death; unknown items are retained. */
    private void dropPlayerInventoryOnDeath(String networkId, SessionState state) {
        if (!state.loginAuthenticated || state.identity == null) return;
        List<Optional<ru.nexora.inventory.ItemStack>> previousInventory = state.inventory.snapshot();
        int previousSelectedSlot = state.inventory.selectedHotbarSlot();
        List<WorldItemEntity> spawned = new ArrayList<>();
        try {
            for (int slot = 0; slot < state.inventory.size(); slot++) {
                ru.nexora.inventory.ItemStack stack = state.inventory.getSlot(slot).orElse(null);
                if (stack == null || itemRegistry.findByIdentifier(stack.itemId()) == null) continue;
                double angle = (slot * 2.399963229728653) % (Math.PI * 2.0);
                WorldItemEntity entity = world.itemEntities().spawn(
                        stack, state.positionX, state.positionY + 0.5, state.positionZ,
                        Math.cos(angle) * 0.12, 0.18, Math.sin(angle) * 0.12,
                        System.currentTimeMillis(), WorldItemEntityManager.DEFAULT_PICKUP_DELAY_MILLIS,
                        state.identity.xuid());
                spawned.add(entity);
                state.inventory.setSlot(slot, null);
            }
            if (spawned.isEmpty()) return;
            saveInventory(state);
        } catch (IOException | RuntimeException exception) {
            state.inventory.restore(previousInventory, previousSelectedSlot);
            for (WorldItemEntity entity : spawned) {
                try {
                    world.itemEntities().remove(entity.runtimeId());
                } catch (RuntimeException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            System.err.println("NEXORA: death inventory drop rolled back for " + networkId
                    + ": " + exception.getMessage());
            return;
        }
        sendInventoryAndEquipmentState(networkId, state);
        for (WorldItemEntity entity : spawned) {
            broadcastItemActor(entity, BedrockAddItemActorCodec.createAddItemActor(entity, itemRegistry));
        }
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
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), state.chunkViewRadius), true);
        sendChunkArea(networkId, state, chunkX, chunkZ);
    }

    private void handleChatMessage(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        if (!message.reliable()) {
            throw new SecurityException("TextPacket chat must arrive on ReliableDataChannel");
        }
        if (!state.loginAuthenticated || !state.clientInitialized || state.dead || state.vitals.dead()
                || state.identity == null) {
            throw new SecurityException("Chat arrived before the authenticated player was ready");
        }

        BedrockTextCodec.DecodedChat chat;
        try {
            chat = BedrockTextCodec.decodeChat(packet);
        } catch (IllegalArgumentException exception) {
            state.rejected = true;
            throw new SecurityException("Malformed Bedrock chat TextPacket", exception);
        }
        if (!chat.xuid().isBlank() && !chat.xuid().equals(state.identity.xuid())) {
            state.rejected = true;
            throw new SecurityException("Chat XUID does not match the authenticated player");
        }

        String cleanMessage;
        try {
            cleanMessage = sanitizeChatMessage(chat.message());
        } catch (SecurityException exception) {
            state.rejected = true;
            throw exception;
        }
        if (cleanMessage.isEmpty()) return;
        long now = nanoTime.getAsLong();
        if (!allowChatMessage(state, now)) return;

        String displayName = state.identity.displayName();
        if (displayName == null || displayName.isBlank()) displayName = "Player";
        // XUID is intentionally empty in the outgoing packet until player-list replication
        // is implemented; Bedrock clients otherwise may hide chat from unknown XUIDs.
        BedrockPacket outgoing = BedrockTextCodec.createChatMessage(displayName, cleanMessage, "", "");
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState recipient = entry.getValue();
            if (!recipient.loginAuthenticated || !recipient.clientInitialized || recipient.rejected) continue;
            try {
                sendPacket(entry.getKey(), outgoing, true);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to deliver chat message to " + entry.getKey()
                        + ": " + exception.getMessage());
            }
        }
    }

    private static String sanitizeChatMessage(String message) {
        StringBuilder clean = new StringBuilder(message.length());
        message.codePoints().filter(codePoint -> !Character.isISOControl(codePoint))
                .forEach(clean::appendCodePoint);
        String result = clean.toString().strip();
        if (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512) {
            throw new SecurityException("Chat message exceeds 512 UTF-8 bytes");
        }
        return result;
    }

    private static boolean allowChatMessage(SessionState state, long nowNanos) {
        if (state.chatWindowStartedNanos == Long.MIN_VALUE
                || nowNanos < state.chatWindowStartedNanos
                || nowNanos - state.chatWindowStartedNanos >= CHAT_RATE_WINDOW_NANOS) {
            state.chatWindowStartedNanos = nowNanos;
            state.chatMessagesInWindow = 0;
        }
        if (state.chatMessagesInWindow >= MAX_CHAT_MESSAGES_PER_WINDOW) return false;
        state.chatMessagesInWindow++;
        return true;
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
        if (normalized.startsWith("/ai spawner") || normalized.startsWith("/ai mob")
                || normalized.startsWith("/ai guard") || normalized.equals("/ai admin")
                || normalized.startsWith("/ai admin ")) {
            sendAiMessage(message.networkId(),
                    "§c[NEXORA AI] Технические сведения и диагностика доступны только через консоль сервера.");
            return;
        }
        if (normalized.equals("/ai")) {
            if (aiAssistant == null || !aiAssistant.isEnabled()) {
                sendAiMessage(message.networkId(), "§b[NEXORA AI] §fПомощник сейчас недоступен. Попробуй позже или обратись к администрации.");
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
            sendAiMessage(networkId, "§b[NEXORA AI] §fКоманды: /ai, /ai <вопрос>, /ai clear, /ai status. "
                    + "Помогу с игровыми командами, правилами, механиками и публичными возможностями сервера.");
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
                    + (configured != null && configured.isEnabled() ? "готов к работе" : "временно недоступен"));
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
            sendAiMessage(networkId, "§b[NEXORA AI] §fПомощник сейчас недоступен. Обратись к администрации.");
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
                sendAiMessage(networkId, "§b[NEXORA AI] §fТехнические планы доступны только через консоль сервера.");
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
        long aggroNowMillis = Math.max(0L, nowNanos / 1_000_000L);
        livingEntityAggroExpiresAtMillis.put(target.runtimeId(),
                aggroNowMillis > Long.MAX_VALUE - MOB_AGGRO_RETENTION_MILLIS
                        ? Long.MAX_VALUE : aggroNowMillis + MOB_AGGRO_RETENTION_MILLIS);
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
        tickPlayerPoisonEffects(tick);
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
            tickLivingEntityAi(nowMillis, tick, aiStartedNanos);
        } catch (RuntimeException failure) {
            ru.nexora.ai.NexoraAiDiagnostics diagnostics = aiDiagnostics;
            if (diagnostics != null) diagnostics.recordFailure(failure);
            throw failure;
        } finally {
            long elapsedNanos = Math.max(0L, nanoTime.getAsLong() - aiStartedNanos);
            hotPathProfiler.record(ru.nexora.ai.HotPathProfiler.Path.WORLD_ENTITY_TICK, elapsedNanos);
            mobAiLoadController.updateAdaptiveBudget(elapsedNanos);
            ru.nexora.ai.NexoraAiDiagnostics diagnostics = aiDiagnostics;
            if (diagnostics != null) {
                diagnostics.recordSlowOperation("mob-ai", elapsedNanos, 50_000_000L);
            }
        }
    }

    private void tickLivingEntityAi(long nowMillis, long tick, long aiStartedNanos) {
        List<ru.nexora.combat.LivingEntityAiService.PlayerTarget> targets = new ArrayList<>();
        List<ru.nexora.world.WorldChunkResidencyManager.PlayerChunk> playerChunks = new ArrayList<>();
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState player = entry.getValue();
            if (!player.loginAuthenticated || !player.clientInitialized || !player.positionInitialized
                    || player.dead || player.vitals.dead()) continue;
            targets.add(new ru.nexora.combat.LivingEntityAiService.PlayerTarget(
                    entry.getKey(), player.positionX, player.positionY, player.positionZ, true));
            playerChunks.add(new ru.nexora.world.WorldChunkResidencyManager.PlayerChunk(
                    Math.floorDiv((int) Math.floor(player.positionX), 16),
                    Math.floorDiv((int) Math.floor(player.positionZ), 16),
                    player.chunkViewRadius));
        }
        // One spatial index per world tick replaces O(mobs * players) full-list
        // target scans with a bounded 3x3 cell lookup and O(1) provoker lookup.
        ru.nexora.combat.PlayerTargetIndex playerTargetIndex =
                new ru.nexora.combat.PlayerTargetIndex(targets);
        Set<WorldChunkResidencyManager.ChunkCoordinate> entityChunks = new HashSet<>();
        for (WorldLivingEntity entity : world.livingEntities().snapshot()) {
            entityChunks.add(new WorldChunkResidencyManager.ChunkCoordinate(
                    Math.floorDiv((int) Math.floor(entity.x()), 16),
                    Math.floorDiv((int) Math.floor(entity.z()), 16)));
        }
        // Dropped items are world-owned actors too: keep their backing chunks
        // resident until pickup/despawn so physics and persistence never target
        // an evicted chunk.
        for (ru.nexora.world.WorldItemEntity entity : world.itemEntities().snapshot()) {
            entityChunks.add(new WorldChunkResidencyManager.ChunkCoordinate(
                    Math.floorDiv((int) Math.floor(entity.x()), 16),
                    Math.floorDiv((int) Math.floor(entity.z()), 16)));
        }
        long residencyStartedNanos = nanoTime.getAsLong();
        WorldChunkResidencyManager.ResidencyPlan residencyPlan =
                chunkResidency.update(playerChunks, entityChunks, Math.max(0L, nowMillis / 50L));
        world.applyChunkResidencyPlan(residencyPlan, MAX_WORLD_CHUNK_LOADS_PER_TICK);
        hotPathProfiler.record(ru.nexora.ai.HotPathProfiler.Path.CHUNK_RESIDENCY,
                Math.max(0L, nanoTime.getAsLong() - residencyStartedNanos));
        ru.nexora.ai.NexoraAiDiagnostics worldDiagnostics = aiDiagnostics;
        if (worldDiagnostics != null) {
            ru.nexora.combat.MobAiLoadController.Stats schedulerStats = mobAiLoadController.stats();
            ru.nexora.combat.MobAiLoadController.BudgetStats budgetStats =
                    mobAiLoadController.budgetStats();
            java.util.Set<ru.nexora.world.WorldChunkResidencyManager.ChunkCoordinate> plannedChunks =
                    chunkResidency.residentChunkCoordinates();
            int cacheOutsidePlanner = 0;
            for (ru.nexora.world.WorldChunkResidencyManager.ChunkCoordinate coordinate
                    : world.loadedChunkCoordinates()) {
                if (!plannedChunks.contains(coordinate)) cacheOutsidePlanner++;
            }
            worldDiagnostics.recordWorldRuntimeSnapshot(
                    residencyPlan.residentCount(), world.loadedChunkCount(),
                    chunkResidency.maximumResidentChunks(), residencyPlan.desiredCount(),
                    world.pendingChunkLoadCount(), world.loadedChunkBlockStorageBytes(),
                    world.loadedChunkSectionCount(), cacheOutsidePlanner, world.livingEntities().size(),
                    schedulerStats.fullRateUpdates(), schedulerStats.deferredUpdates(),
                    budgetStats.deferredTasks(), budgetStats.consumedNanos());
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
            if (!chunkResidency.isResidentAtBlockPosition(mob.x(), mob.z())
                    || !ru.nexora.world.WorldPathfinder.isMobFootprintLoaded(world, mob.x(), mob.z())) {
                // A fuse must not keep advancing while its mob is outside loaded world space.
                if ("minecraft:creeper".equals(mob.identifier())) {
                    mobFuseTracker.clear(mob.runtimeId());
                }
                continue;
            }
            WorldLivingEntity beforePhysics = mob;
            ru.nexora.combat.MobPhysicsService.Result physics = mobPhysics.tick(world, mob);
            mob = physics.entity();
            boolean velocityChanged = beforePhysics.velocityX() != mob.velocityX()
                    || beforePhysics.velocityY() != mob.velocityY()
                    || beforePhysics.velocityZ() != mob.velocityZ();
            if (physics.moved()) mobStuckTracker.clear(mob.runtimeId());
            if (physics.moved() || velocityChanged) {
                float physicsYaw = (float) Math.toDegrees(
                        Math.atan2(-mob.velocityX(), mob.velocityZ()));
                broadcastLivingActorPacket(BedrockMoveActorAbsoluteCodec.create(
                        mob.runtimeId(), mob.x(), mob.y(), mob.z(), 0.0f,
                        physicsYaw, physicsYaw, physics.grounded()));
            }
            ru.nexora.combat.MobFallTracker.Result fall =
                    mobFallTracker.update(mob.runtimeId(), mob.y(), physics.grounded(),
                            ru.nexora.combat.LivingEntityAiService.isFlyingMob(mob.identifier())
                                    || "minecraft:slime".equals(mob.identifier())
                                    || "minecraft:magma_cube".equals(mob.identifier()));
            if (fall.damage() > 0) {
                WorldLivingEntityManager.DamageResult fallDamage =
                        world.livingEntities().damage(mob.runtimeId(), fall.damage()).orElse(null);
                if (fallDamage != null && fallDamage.killed()) {
                    cleanupLivingEntityRuntimeState(mob.runtimeId());
                    broadcastLivingActorPacket(
                            BedrockAddItemActorCodec.createRemoveActor(mob.uniqueId()));
                    continue;
                }
                if (fallDamage != null) {
                    mob = fallDamage.current();
                    broadcastLivingActorPacket(BedrockVitalsCodec.createActorHealthUpdate(
                            mob.runtimeId(), mob.health(), mob.maxHealth(), Math.max(0, tick)));
                }
            }
            long targetingStartedNanos = nanoTime.getAsLong();
            ru.nexora.combat.LivingEntityAiService.PlayerTarget nearestPlayer =
                    playerTargetIndex.nearestWithinRange(mob);
            hotPathProfiler.record(ru.nexora.ai.HotPathProfiler.Path.MOB_TARGETING,
                    Math.max(0L, nanoTime.getAsLong() - targetingStartedNanos));
            if ("minecraft:creeper".equals(mob.identifier())
                    && tickCreeperBehavior(mob, nearestPlayer, nowMillis, tick)) {
                mobAiLoadController.recordScheduled(mob.runtimeId());
                mobAiTaskScheduler.recordRun(ru.nexora.combat.MobAiTaskScheduler.Task.COMBAT);
                mobStuckTracker.clear(mob.runtimeId());
                continue;
            }
            if (tickGuardianBeamBehavior(mob, nearestPlayer, nowMillis, tick)
                    || tickFireballMobBehavior(mob, nearestPlayer, nowMillis)
                    || tickRangedMobBehavior(mob, nearestPlayer, nowMillis)) {
                mobAiLoadController.recordScheduled(mob.runtimeId());
                mobAiTaskScheduler.recordRun(ru.nexora.combat.MobAiTaskScheduler.Task.COMBAT);
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
            if (mobDespawnTracker.shouldDespawn(mob.runtimeId(),
                    ru.nexora.combat.LivingEntityAiService.isHostile(mob.identifier()),
                    nearestPlayerDistanceSquared, nowMillis)) {
                despawnLivingEntity(mob.runtimeId());
                continue;
            }
            String provokedBy = activeMobProvoker(mob.runtimeId(), nowMillis);
            boolean hasActiveTarget = provokedBy != null;
            // Use the server's monotonic 50 ms AI cadence, not a client-provided input tick.
            // Different players have independent input counters and must not perturb mob scheduling.
            long serverAiTick = Math.max(0L, nowMillis / 50L);
            ru.nexora.combat.MobAiLoadController.Priority basePriority =
                    mobAiLoadController.priority(nearestPlayerDistanceSquared, hasActiveTarget, false);
            ru.nexora.combat.MobAiLoadController.Priority aiPriority =
                    mobAiLoadController.effectivePriority(mob.runtimeId(), basePriority);
            boolean starvationPromoted = aiPriority == ru.nexora.combat.MobAiLoadController.Priority.HIGH
                    && basePriority != ru.nexora.combat.MobAiLoadController.Priority.HIGH;
            if (!mobAiLoadController.shouldUpdate(mob.runtimeId(), serverAiTick,
                    nearestPlayerDistanceSquared, hasActiveTarget || starvationPromoted)) {
                mobAiLoadController.recordDeferred(mob.runtimeId());
                mobAiTaskScheduler.recordDeferral(ru.nexora.combat.MobAiTaskScheduler.Task.NAVIGATION);
                continue;
            }
            long budgetElapsed = Math.max(0L, nanoTime.getAsLong() - aiStartedNanos);
            if (mobAiTaskScheduler.admit(ru.nexora.combat.MobAiTaskScheduler.Task.NAVIGATION,
                    aiPriority, hasActiveTarget, budgetElapsed, mobAiLoadController.aiBudgetNanos())
                    == ru.nexora.combat.MobAiTaskScheduler.Admission.DEFER) {
                mobAiLoadController.recordDeferred(mob.runtimeId());
                continue;
            }
            if (!mobAiLoadController.admit(mob.runtimeId(), serverAiTick,
                    nearestPlayerDistanceSquared, hasActiveTarget, aiPriority, budgetElapsed)) {
                mobAiLoadController.recordDeferred(mob.runtimeId());
                continue;
            }
            long nextAttackAt = nextMobAttackMillis.getOrDefault(mob.runtimeId(), 0L);
            long decisionStartedNanos = nanoTime.getAsLong();
            ru.nexora.combat.LivingEntityAiService.Decision decision =
                    livingEntityAi.decide(mob, targets, nowMillis, nextAttackAt,
                            provokedBy, nearestPlayer, playerTargetIndex.find(provokedBy));
            double projectileRange = ru.nexora.combat.LivingEntityAiService
                    .projectileRangeFor(mob.identifier());
            if (nearestPlayer != null && projectileRange > 0
                    && nearestPlayerDistanceSquared <= projectileRange * projectileRange) {
                double eyeHeight = "minecraft:ghast".equals(mob.identifier()) ? 1.8 : 1.2;
                if (!WorldRaycast.hasLineOfSightInLoadedChunks(world,
                        mob.x(), mob.y() + eyeHeight, mob.z(),
                        (int) Math.floor(nearestPlayer.x()),
                        (int) Math.floor(nearestPlayer.y() + 1.0),
                        (int) Math.floor(nearestPlayer.z()))) {
                    decision = livingEntityAi.approach(mob, nearestPlayer);
                }
            }
            long decisionElapsedNanos = Math.max(0L, nanoTime.getAsLong() - decisionStartedNanos);
            mobAiLoadController.recordBudgetNanos(decisionElapsedNanos);
            hotPathProfiler.record(ru.nexora.ai.HotPathProfiler.Path.MOB_AI_DECISION, decisionElapsedNanos);
            if (decision.attack()) {
                mobAiLoadController.recordScheduled(mob.runtimeId());
                mobAiTaskScheduler.admit(ru.nexora.combat.MobAiTaskScheduler.Task.COMBAT,
                        aiPriority, true, Math.max(0L, nanoTime.getAsLong() - aiStartedNanos),
                        mobAiLoadController.aiBudgetNanos());
                mobStuckTracker.clear(mob.runtimeId());
                // Projectile attacks are executed only by the line-of-sight-aware
                // ranged handlers above; never convert a blocked shot into melee damage.
                if (decision.attackStyle() == ru.nexora.combat.LivingEntityAiService.AttackStyle.MELEE
                        && damagePlayerFromMob(decision.targetNetworkId(), mob,
                        decision.attackDamage(), tick)) {
                    nextMobAttackMillis.put(mob.runtimeId(), mobAttackDeadline(nowMillis,
                            ru.nexora.combat.LivingEntityAiService.attackCooldownMillisFor(
                                    mob.identifier())));
                }
                continue;
            }
            if (!decision.moving()) {
                mobAiLoadController.recordScheduled(mob.runtimeId());
                mobAiTaskScheduler.recordRun(ru.nexora.combat.MobAiTaskScheduler.Task.IDLE);
                mobStuckTracker.clear(mob.runtimeId());
                continue;
            }
            if (mobAiTaskScheduler.admit(ru.nexora.combat.MobAiTaskScheduler.Task.MOVEMENT,
                    aiPriority, aiPriority == ru.nexora.combat.MobAiLoadController.Priority.HIGH,
                    Math.max(0L, nanoTime.getAsLong() - aiStartedNanos),
                    mobAiLoadController.aiBudgetNanos())
                    == ru.nexora.combat.MobAiTaskScheduler.Admission.DEFER) {
                // admit() already records the task-level deferral; only starvation is tracked here.
                mobAiLoadController.recordDeferred(mob.runtimeId());
                continue;
            }
            // Clear starvation only after the movement task itself is admitted.
            // If the movement budget rejects it, its deferred count must survive this tick.
            mobAiLoadController.recordScheduled(mob.runtimeId());
            double nextX = mob.x() + decision.moveX();
            double nextY = mob.y() + decision.moveY();
            double nextZ = mob.z() + decision.moveZ();
            if (!ru.nexora.world.WorldPathfinder.isMobFootprintLoaded(world, nextX, nextZ)
                    || !WorldCollision.canMove(world, mob.x(), mob.y(), mob.z(), nextX, nextY, nextZ)) {
                // Try a collision-checked one-block jump before spending work on pathfinding.
                // The controller refuses flying mobs, unloaded chunks, and jumps without headroom.
                if (mobJumpController.tryJump(world, mob, nextX, nextZ, nowMillis)) {
                    mobStuckTracker.clear(mob.runtimeId());
                    continue;
                }
                if (mobClimbController.tryClimb(world, mob, nextX, nextZ)) {
                    mobStuckTracker.clear(mob.runtimeId());
                    continue;
                }
                double desiredX = mob.x() + decision.moveX() * 8.0;
                double desiredZ = mob.z() + decision.moveZ() * 8.0;
                long pathStartedNanos = nanoTime.getAsLong();
                WorldPathfinder.Step waypoint;
                try {
                    waypoint = mobPathCache.findNextStep(mob.runtimeId(), world,
                            mob.x(), mob.y(), mob.z(), desiredX, desiredZ, nowMillis).orElse(null);
                } finally {
                    ru.nexora.ai.NexoraAiDiagnostics diagnostics = aiDiagnostics;
                    long pathElapsedNanos = Math.max(0L, nanoTime.getAsLong() - pathStartedNanos);
                    hotPathProfiler.record(ru.nexora.ai.HotPathProfiler.Path.MOB_PATHFINDING, pathElapsedNanos);
                    if (diagnostics != null) {
                        diagnostics.recordSlowOperation("mob-pathfinding", pathElapsedNanos, 5_000_000L);
                    }
                }
                if (waypoint == null) {
                    if (ru.nexora.world.WorldPathfinder.isSearchAreaLoaded(world, mob.x(), mob.z())) {
                        mobStuckTracker.recordBlockedAttempt(
                                mob.runtimeId(), mob.x(), mob.y(), mob.z(), nowMillis);
                    } else {
                        mobStuckTracker.clear(mob.runtimeId());
                    }
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
        tickMobArrows(tick, playerTargetIndex);
        tickMobFireballs(tick, playerTargetIndex);
    }

    private static float mobMaxHealth(String identifier) {
        return switch (identifier) {
            case "minecraft:chicken", "minecraft:snow_golem" -> 4.0f;
            case "minecraft:cod", "minecraft:tropical_fish", "minecraft:salmon",
                    "minecraft:pufferfish", "minecraft:rabbit" -> 3.0f;
            case "minecraft:silverfish", "minecraft:endermite" -> 8.0f;
            case "minecraft:sheep" -> 8.0f;
            case "minecraft:tadpole" -> 6.0f;
            case "minecraft:bat", "minecraft:parrot" -> 6.0f;
            case "minecraft:wolf" -> 8.0f;
            case "minecraft:bee", "minecraft:cat", "minecraft:ocelot",
                    "minecraft:fox", "minecraft:pig", "minecraft:cow",
                    "minecraft:mooshroom", "minecraft:goat", "minecraft:frog",
                    "minecraft:glow_squid", "minecraft:squid" -> 10.0f;
            case "minecraft:armadillo" -> 12.0f;
            case "minecraft:axolotl" -> 14.0f;
            case "minecraft:vex" -> 14.0f;
            case "minecraft:donkey", "minecraft:mule", "minecraft:llama",
                    "minecraft:trader_llama" -> 15.0f;
            case "minecraft:piglin", "minecraft:bogged" -> 16.0f;
            case "minecraft:panda", "minecraft:blaze" -> 20.0f;
            case "minecraft:polar_bear" -> 30.0f;
            case "minecraft:horse", "minecraft:allay", "minecraft:strider" -> 20.0f;
            case "minecraft:camel" -> 32.0f;
            case "minecraft:turtle" -> 30.0f;
            case "minecraft:cave_spider" -> 12.0f;
            case "minecraft:guardian", "minecraft:shulker", "minecraft:breeze" -> 30.0f;
            case "minecraft:witch" -> 26.0f;
            case "minecraft:pillager", "minecraft:vindicator", "minecraft:evoker" -> 24.0f;
            case "minecraft:slime", "minecraft:magma_cube" -> 1.0f;
            case "minecraft:creeper", "minecraft:skeleton", "minecraft:stray",
                    "minecraft:husk", "minecraft:drowned", "minecraft:zombie",
                    "minecraft:zombie_villager", "minecraft:phantom",
                    "minecraft:wither_skeleton" -> 20.0f;
            case "minecraft:enderman", "minecraft:hoglin", "minecraft:zoglin" -> 40.0f;
            case "minecraft:ravager", "minecraft:iron_golem" -> 100.0f;
            case "minecraft:elder_guardian" -> 80.0f;
            case "minecraft:warden" -> 500.0f;
            case "minecraft:ender_dragon" -> 200.0f;
            case "minecraft:ghast" -> 10.0f;
            case "minecraft:wither" -> 300.0f;
            default -> 20.0f;
        };
    }

    private boolean tickGuardianBeamBehavior(
            WorldLivingEntity guardian,
            ru.nexora.combat.LivingEntityAiService.PlayerTarget target,
            long nowMillis,
            long tick
    ) {
        String identifier = guardian.identifier();
        if (!"minecraft:guardian".equals(identifier)
                && !"minecraft:elder_guardian".equals(identifier)) return false;
        if (target == null) {
            guardianBeamTracker.clear(guardian.runtimeId());
            return true;
        }
        double dx = target.x() - guardian.x();
        double dy = target.y() + 1.0 - (guardian.y() + 1.2);
        double dz = target.z() - guardian.z();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        boolean inRange = distanceSquared >= 3.0 * 3.0 && distanceSquared <= 14.0 * 14.0;
        boolean visible = inRange && WorldRaycast.hasLineOfSightInLoadedChunks(
                world, guardian.x(), guardian.y() + 1.2,
                guardian.z(), (int) Math.floor(target.x()),
                (int) Math.floor(target.y() + 1.0), (int) Math.floor(target.z()));
        ru.nexora.combat.GuardianBeamTracker.Result result = guardianBeamTracker.tick(
                guardian.runtimeId(), target.networkId(), visible, nowMillis);
        if (result.fired()) {
            float damage = "minecraft:elder_guardian".equals(identifier) ? 6.0f : 5.0f;
            damagePlayerFromMob(target.networkId(), guardian, damage, tick, 14.0);
        }
        return true;
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
        double horizontalDistance = Math.hypot(dx, dz);
        double maxRange = ru.nexora.combat.LivingEntityAiService.projectileRangeFor(mob.identifier());
        if (horizontalDistance < ru.nexora.combat.LivingEntityAiService.RANGED_RETREAT_DISTANCE
                || distance > maxRange) return false;
        if (!WorldRaycast.hasLineOfSightInLoadedChunks(world, mob.x(), mob.y() + 1.2, mob.z(),
                (int) Math.floor(nearest.x()), (int) Math.floor(nearest.y() + 1.0),
                (int) Math.floor(nearest.z()))) {
            return false;
        }
        if (nowMillis < nextMobAttackMillis.getOrDefault(mob.runtimeId(), 0L)) return true;
        if (mobArrows.size() >= 2_000) return false;
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
        indexProjectile(mobArrowIdsByChunk, mobArrowChunkById, runtimeId, arrow.x(), arrow.z());
        indexProjectileOwner(mobArrowIdsByOwner, mobArrowOwnerById, runtimeId, arrow.ownerNetworkId());
        if ("minecraft:bogged".equals(mob.identifier())) poisonMobArrows.add(runtimeId);
        broadcastLivingActorPacket(BedrockArrowActorCodec.createSpawn(arrow));
        long cooldownMillis = ru.nexora.combat.LivingEntityAiService
                .rangedAttackCooldownMillisFor(mob.identifier());
        long nextAttackAt = nowMillis > Long.MAX_VALUE - cooldownMillis
                ? Long.MAX_VALUE : nowMillis + cooldownMillis;
        nextMobAttackMillis.put(mob.runtimeId(), nextAttackAt);
        return true;
    }


    /** Ghasts launch heavy fireballs; blazes launch faster, smaller fireballs. */
    private boolean tickFireballMobBehavior(
            WorldLivingEntity mob,
            ru.nexora.combat.LivingEntityAiService.PlayerTarget nearest,
            long nowMillis
    ) {
        String identifier = mob.identifier();
        boolean ghast = "minecraft:ghast".equals(identifier);
        boolean blaze = "minecraft:blaze".equals(identifier);
        if (!ghast && !blaze) return false;
        if (nearest == null) return false;
        double dx = nearest.x() - mob.x();
        double dy = nearest.y() + 0.8 - (mob.y() + (ghast ? 1.8 : 1.2));
        double dz = nearest.z() - mob.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double horizontalDistance = Math.hypot(dx, dz);
        double maxRange = ru.nexora.combat.LivingEntityAiService.projectileRangeFor(identifier);
        if (horizontalDistance < ru.nexora.combat.LivingEntityAiService.RANGED_RETREAT_DISTANCE
                || distance > maxRange) return false;
        if (!WorldRaycast.hasLineOfSightInLoadedChunks(world, mob.x(), mob.y() + (ghast ? 1.8 : 1.2),
                mob.z(), (int) Math.floor(nearest.x()), (int) Math.floor(nearest.y() + 1.0),
                (int) Math.floor(nearest.z()))) return false;
        if (nowMillis < nextMobAttackMillis.getOrDefault(mob.runtimeId(), 0L)) return true;
        if (mobFireballs.size() >= 1_000) return true;
        double aimY = dy + distance * (ghast ? 0.018 : 0.025);
        double aimLength = Math.sqrt(dx * dx + aimY * aimY + dz * dz);
        if (aimLength < 0.0001) return true;
        long runtimeId = nextMobArrowRuntimeId.getAndIncrement();
        if (runtimeId <= 0 || runtimeId == Long.MAX_VALUE) {
            nextMobArrowRuntimeId.set(4_000_000L);
            runtimeId = nextMobArrowRuntimeId.getAndIncrement();
        }
        double speed = ghast ? 1.35 : 1.65;
        ru.nexora.combat.FireballProjectile fireball = new ru.nexora.combat.FireballProjectile(
                -runtimeId, runtimeId, "mob:" + mob.runtimeId(),
                ghast ? "minecraft:fireball" : "minecraft:small_fireball",
                mob.x(), mob.y() + (ghast ? 1.8 : 1.2), mob.z(),
                dx / aimLength * speed, aimY / aimLength * speed, dz / aimLength * speed,
                0, ghast ? 6.0f : 4.0f, ghast ? 5 : 3);
        mobFireballs.put(runtimeId, fireball);
        indexProjectile(mobFireballIdsByChunk, mobFireballChunkById,
                runtimeId, fireball.x(), fireball.z());
        indexProjectileOwner(mobFireballIdsByOwner, mobFireballOwnerById,
                runtimeId, fireball.ownerNetworkId());
        broadcastLivingActorPacket(BedrockFireballActorCodec.createSpawn(fireball));
        long cooldownMillis = ru.nexora.combat.LivingEntityAiService
                .rangedAttackCooldownMillisFor(identifier);
        nextMobAttackMillis.put(mob.runtimeId(),
                nowMillis > Long.MAX_VALUE - cooldownMillis ? Long.MAX_VALUE : nowMillis + cooldownMillis);
        return true;
    }

    private void tickMobFireballs(long tick) {
        tickMobFireballs(tick, snapshotPlayerTargetIndex());
    }

    private void tickMobFireballs(long tick, ru.nexora.combat.PlayerTargetIndex playerIndex) {
        long nowNanos = Math.max(0L, nanoTime.getAsLong());
        for (var entry : mobFireballs.entrySet()) {
            ru.nexora.combat.FireballProjectile current = entry.getValue();
            ru.nexora.combat.FireballProjectile next = current.advance();
            boolean expired = next.expired();
            boolean blocked = !expired && isMobFireballPathBlocked(current, next);
            boolean removed = expired || blocked;
            boolean ghastFireball = "minecraft:fireball".equals(current.identifier());
            if (blocked && ghastFireball) {
                explodeGhastFireball(current, tick, nowNanos);
            }
            if (!removed) {
                for (ru.nexora.combat.LivingEntityAiService.PlayerTarget candidate
                        : playerIndex.withinSegment(current.x(), current.y() - 0.9, current.z(),
                                next.x(), next.y() - 0.9, next.z(), 0.7)) {
                    String playerNetworkId = candidate.networkId();
                    SessionState player = sessions.get(playerNetworkId);
                    if (player == null) continue;
                    if (!player.loginAuthenticated || !player.clientInitialized
                            || !player.positionInitialized || player.dead || player.vitals.dead()) continue;
                    double distanceSquared = distanceToSegmentSquared(
                            player.positionX, player.positionY + 0.9, player.positionZ,
                            current.x(), current.y(), current.z(), next.x(), next.y(), next.z());
                    if (distanceSquared > 0.7 * 0.7) continue;
                    if (ghastFireball) {
                        explodeGhastFireball(current, tick, nowNanos);
                    } else {
                        player.combatLock.lock();
                        try {
                            synchronized (player) {
                                if (player.dead || player.vitals.dead()) continue;
                                List<ItemStack> armor = player.equipment.armorSnapshot().stream()
                                        .flatMap(Optional::stream).toList();
                                float damage = ru.nexora.combat.EnchantmentCombatService
                                        .applyIncomingDamage(current.damage(), armor,
                                                ru.nexora.combat.EnchantmentCombatService.DamageType.PROJECTILE);
                                if (damage > 0.0f) {
                                    applyDamage(playerNetworkId, player, damage, tick, "death.attack.fireball");
                                }
                                if (!player.dead && !player.vitals.dead() && current.fireDurationSeconds() > 0) {
                                    long durationNanos = current.fireDurationSeconds() * 1_000_000_000L;
                                    player.fireUntilNanos = Math.max(player.fireUntilNanos,
                                            nowNanos > Long.MAX_VALUE - durationNanos
                                                    ? Long.MAX_VALUE : nowNanos + durationNanos);
                                }
                            }
                        } finally {
                            player.combatLock.unlock();
                        }
                    }
                    removed = true;
                    break;
                }
            }
            if (removed) {
                if (mobFireballs.remove(entry.getKey(), current)) {
                    unindexProjectile(mobFireballIdsByChunk, mobFireballChunkById, entry.getKey());
                    unindexProjectileOwner(mobFireballIdsByOwner, mobFireballOwnerById, entry.getKey());
                    broadcastLivingActorPacket(
                            BedrockAddItemActorCodec.createRemoveActor(current.uniqueId()));
                }
            } else if (mobFireballs.replace(entry.getKey(), current, next)) {
                reindexProjectile(mobFireballIdsByChunk, mobFireballChunkById,
                        entry.getKey(), current.x(), current.z(), next.x(), next.z());
                broadcastLivingActorPacket(BedrockFireballActorCodec.createMove(next));
            }
        }
    }


    /** Applies the ghast blast only to resident chunks; protected blocks and containers survive. */
    private void explodeGhastFireball(
            ru.nexora.combat.FireballProjectile fireball, long tick, long nowNanos
    ) {
        broadcastPacket(BedrockLevelEventCodec.createExplosionParticle(
                fireball.x(), fireball.y(), fireball.z()));
        int minX = (int) Math.floor(fireball.x() - ru.nexora.combat.GhastExplosion.BLOCK_RADIUS);
        int maxX = (int) Math.floor(fireball.x() + ru.nexora.combat.GhastExplosion.BLOCK_RADIUS);
        int minY = Math.max(WorldChunk.MIN_Y,
                (int) Math.floor(fireball.y() - ru.nexora.combat.GhastExplosion.BLOCK_RADIUS));
        int maxY = Math.min(WorldChunk.MAX_Y,
                (int) Math.floor(fireball.y() + ru.nexora.combat.GhastExplosion.BLOCK_RADIUS));
        int minZ = (int) Math.floor(fireball.z() - ru.nexora.combat.GhastExplosion.BLOCK_RADIUS);
        int maxZ = (int) Math.floor(fireball.z() + ru.nexora.combat.GhastExplosion.BLOCK_RADIUS);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!ru.nexora.combat.GhastExplosion.isInsideBlockBlast(
                            x + 0.5 - fireball.x(), y + 0.5 - fireball.y(), z + 0.5 - fireball.z())
                            || !world.isChunkLoadedAt(x, z)) continue;
                    BlockType block = world.getBlock(x, y, z);
                    if (!ru.nexora.combat.GhastExplosion.canDestroy(block)) continue;

                    ItemStack drop = ru.nexora.combat.GhastExplosion.dropFor(block);
                    if (drop != null && itemRegistry.findByIdentifier(drop.itemId()) == null) {
                        // Never erase a block when this server build cannot represent its drop.
                        continue;
                    }
                    WorldItemEntity droppedEntity = null;
                    if (drop != null) {
                        try {
                            double dx = x + 0.5 - fireball.x();
                            double dz = z + 0.5 - fireball.z();
                            double horizontal = Math.sqrt(dx * dx + dz * dz);
                            double velocityX = horizontal < 1.0e-6 ? 0.0 : dx / horizontal * 0.18;
                            double velocityZ = horizontal < 1.0e-6 ? 0.0 : dz / horizontal * 0.18;
                            droppedEntity = world.itemEntities().spawn(drop,
                                    x + 0.5, y + 0.55, z + 0.5,
                                    velocityX, 0.22, velocityZ, System.currentTimeMillis(),
                                    WorldItemEntityManager.DEFAULT_PICKUP_DELAY_MILLIS, null);
                        } catch (RuntimeException exception) {
                            System.err.println("NEXORA: unable to create ghast block drop at "
                                    + x + " " + y + " " + z + ": " + exception.getMessage());
                            continue;
                        }
                    }
                    try {
                        world.setBlock(x, y, z, BlockType.AIR);
                        broadcastBlockUpdate(x, y, z, BlockType.AIR);
                        if (droppedEntity != null) {
                            broadcastItemActor(droppedEntity,
                                    BedrockAddItemActorCodec.createAddItemActor(droppedEntity, itemRegistry));
                        }
                    } catch (RuntimeException exception) {
                        if (droppedEntity != null) {
                            try {
                                world.itemEntities().remove(droppedEntity.runtimeId());
                            } catch (RuntimeException rollbackFailure) {
                                exception.addSuppressed(rollbackFailure);
                            }
                        }
                        throw exception;
                    }
                }
            }
        }

        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState player = entry.getValue();
            if (!player.loginAuthenticated || !player.clientInitialized
                    || !player.positionInitialized || player.dead || player.vitals.dead()) continue;
            double dx = player.positionX - fireball.x();
            double dy = player.positionY + 0.9 - fireball.y();
            double dz = player.positionZ - fireball.z();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            float rawDamage = ru.nexora.combat.GhastExplosion.damageAtDistance(distance);
            if (rawDamage <= 0.0f) continue;
            int targetX = (int) Math.floor(player.positionX);
            int targetY = (int) Math.floor(player.positionY + 1.0);
            int targetZ = (int) Math.floor(player.positionZ);
            if (!WorldRaycast.hasLineOfSightInLoadedChunks(world, fireball.x(), fireball.y(),
                    fireball.z(), targetX, targetY, targetZ)) continue;
            player.combatLock.lock();
            try {
                synchronized (player) {
                    if (player.dead || player.vitals.dead()) continue;
                    List<ItemStack> armor = player.equipment.armorSnapshot().stream()
                            .flatMap(Optional::stream).toList();
                    float damage = ru.nexora.combat.EnchantmentCombatService.applyIncomingDamage(
                            rawDamage, armor,
                            ru.nexora.combat.EnchantmentCombatService.DamageType.EXPLOSION);
                    if (damage > 0.0f) {
                        applyDamage(entry.getKey(), player, damage, tick, "death.attack.explosion");
                    }
                    if (!player.dead && !player.vitals.dead()
                            && distance <= ru.nexora.combat.GhastExplosion.BLOCK_RADIUS) {
                        long duration = ru.nexora.combat.GhastExplosion.FIRE_DURATION_SECONDS
                                * 1_000_000_000L;
                        player.fireUntilNanos = Math.max(player.fireUntilNanos,
                                nowNanos > Long.MAX_VALUE - duration ? Long.MAX_VALUE : nowNanos + duration);
                    }
                    if (!player.dead && !player.vitals.dead()) {
                        ru.nexora.combat.GhastExplosion.Knockback impulse =
                                ru.nexora.combat.GhastExplosion.knockbackAt(dx, dy, dz);
                        if (impulse != null) {
                            if (player.physicsState != null) {
                                PlayerPhysics.State current = player.physicsState;
                                player.physicsState = new PlayerPhysics.State(
                                        current.x(), current.y(), current.z(),
                                        current.velocityX() + impulse.velocityX(),
                                        Math.max(current.velocityY(), impulse.velocityY()),
                                        current.velocityZ() + impulse.velocityZ(),
                                        current.onGround(), current.fallDistance(), current.landedDistance());
                                sendMovementCorrection(entry.getKey(), player, Math.max(0, tick));
                            } else {
                                sendPacket(entry.getKey(), BedrockActorMotionCodec.createSetActorMotion(
                                        player.entityId, impulse.velocityX(), impulse.velocityY(),
                                        impulse.velocityZ(), Math.max(0, tick)), true);
                            }
                        }
                    }
                }
            } finally {
                player.combatLock.unlock();
            }
        }
    }

    /** Sweeps the full fireball segment and treats unloaded chunks as solid boundaries. */
    private boolean isMobFireballPathBlocked(
            ru.nexora.combat.FireballProjectile from,
            ru.nexora.combat.FireballProjectile to
    ) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int samples = Math.max(1, (int) Math.ceil(length / 0.1));
        if (samples > 256) return true;
        for (int sample = 1; sample <= samples; sample++) {
            double fraction = (double) sample / samples;
            int blockX = (int) Math.floor(from.x() + dx * fraction);
            int blockY = (int) Math.floor(from.y() + dy * fraction);
            int blockZ = (int) Math.floor(from.z() + dz * fraction);
            if (blockY < WorldChunk.MIN_Y || blockY > WorldChunk.MAX_Y
                    || !world.isChunkLoadedAt(blockX, blockZ)
                    || world.getBlock(blockX, blockY, blockZ) != BlockType.AIR) return true;
        }
        return false;
    }

    private static boolean isRangedMob(String identifier) {
        return switch (identifier) {
            case "minecraft:skeleton", "minecraft:stray",
                    "minecraft:bogged", "minecraft:pillager" -> true;
            default -> false;
        };
    }

    private ru.nexora.combat.PlayerTargetIndex snapshotPlayerTargetIndex() {
        List<ru.nexora.combat.LivingEntityAiService.PlayerTarget> targets = new ArrayList<>();
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState player = entry.getValue();
            if (!player.loginAuthenticated || !player.clientInitialized || !player.positionInitialized
                    || player.dead || player.vitals.dead()) continue;
            targets.add(new ru.nexora.combat.LivingEntityAiService.PlayerTarget(
                    entry.getKey(), player.positionX, player.positionY, player.positionZ, true));
        }
        return new ru.nexora.combat.PlayerTargetIndex(targets);
    }

    private void tickMobArrows(long tick) {
        tickMobArrows(tick, snapshotPlayerTargetIndex());
    }

    private void tickMobArrows(long tick, ru.nexora.combat.PlayerTargetIndex playerIndex) {
        for (var entry : mobArrows.entrySet()) {
            ru.nexora.combat.ArrowProjectile current = entry.getValue();
            ru.nexora.combat.ArrowProjectile next = current.advance();
            boolean removed = next.expired();
            if (!removed) {
                removed = isMobArrowPathBlocked(current, next);
            }
            if (!removed) {
                for (ru.nexora.combat.LivingEntityAiService.PlayerTarget candidate
                        : playerIndex.withinSegment(current.x(), current.y() - 0.9, current.z(),
                                next.x(), next.y() - 0.9, next.z(), 0.55)) {
                    String playerNetworkId = candidate.networkId();
                    SessionState player = sessions.get(playerNetworkId);
                    if (player == null) continue;
                    if (next.ageTicks() <= 5 && current.ownerNetworkId().equals(playerNetworkId)) continue;
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
                                applyDamage(playerNetworkId, player, damage, tick, "death.attack.arrow");
                            }
                            if (poisonMobArrows.contains(entry.getKey())
                                    && !player.dead && !player.vitals.dead()) applyPoison(player);
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
                    unindexProjectile(mobArrowIdsByChunk, mobArrowChunkById, entry.getKey());
                    unindexProjectileOwner(mobArrowIdsByOwner, mobArrowOwnerById, entry.getKey());
                    poisonMobArrows.remove(entry.getKey());
                    broadcastLivingActorPacket(BedrockAddItemActorCodec.createRemoveActor(current.uniqueId()));
                }
            } else if (mobArrows.replace(entry.getKey(), current, next)) {
                reindexProjectile(mobArrowIdsByChunk, mobArrowChunkById,
                        entry.getKey(), current.x(), current.z(), next.x(), next.z());
                broadcastLivingActorPacket(BedrockArrowActorCodec.createMove(next, Math.max(0, tick)));
            }
        }
    }

    /**
     * Sweeps the whole per-tick arrow segment through loaded voxels. Sampling at
     * 0.1-block intervals prevents fast projectiles from tunnelling through thin blocks.
     * Unloaded chunks are treated as a safe stopping boundary and are never generated here.
     */
    private boolean isMobArrowPathBlocked(
            ru.nexora.combat.ArrowProjectile from,
            ru.nexora.combat.ArrowProjectile to
    ) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int samples = Math.max(1, (int) Math.ceil(length / 0.1));
        if (samples > 256) return true;
        for (int sample = 1; sample <= samples; sample++) {
            double fraction = (double) sample / samples;
            int blockX = (int) Math.floor(from.x() + dx * fraction);
            int blockY = (int) Math.floor(from.y() + dy * fraction);
            int blockZ = (int) Math.floor(from.z() + dz * fraction);
            if (blockY < WorldChunk.MIN_Y || blockY > WorldChunk.MAX_Y
                    || !world.isChunkLoadedAt(blockX, blockZ)
                    || world.getBlock(blockX, blockY, blockZ) != BlockType.AIR) {
                return true;
            }
        }
        return false;
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
                mobPathCache.remove(enderman.runtimeId());
                mobStuckTracker.clear(enderman.runtimeId());
                mobFallTracker.clear(enderman.runtimeId());
                mobDespawnTracker.clear(enderman.runtimeId());
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
            mobFuseTracker.isReadyToDetonate(creeper.runtimeId(), false, nowMillis, 1_500L);
            return false;
        }
        double dx = nearest.x() - creeper.x();
        double dy = nearest.y() - creeper.y();
        double dz = nearest.z() - creeper.z();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared > 3.0 * 3.0) {
            mobFuseTracker.isReadyToDetonate(creeper.runtimeId(), false, nowMillis, 1_500L);
            return false;
        }

        if (!mobFuseTracker.isReadyToDetonate(
                creeper.runtimeId(), true, nowMillis, 1_500L)) return true;
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
        return damagePlayerFromMob(networkId, mob, amount, tick,
                ru.nexora.combat.LivingEntityAiService.attackRangeFor(mob.identifier()));
    }

    private boolean damagePlayerFromMob(
            String networkId, WorldLivingEntity mob, float amount, long tick, double maxRange
    ) {
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
                if (!Double.isFinite(maxRange) || maxRange <= 0
                        || rangeX * rangeX + rangeY * rangeY + rangeZ * rangeZ
                        > maxRange * maxRange) return false;
                int targetBlockX = (int) Math.floor(target.positionX);
                int targetBlockY = (int) Math.floor(target.positionY + 1.0);
                int targetBlockZ = (int) Math.floor(target.positionZ);
                if (!WorldRaycast.hasLineOfSightInLoadedChunks(world, mob.x(), mob.y() + 1.2, mob.z(),
                        targetBlockX, targetBlockY, targetBlockZ)) return false;
                List<ItemStack> armor = target.equipment.armorSnapshot().stream()
                        .flatMap(Optional::stream).toList();
                float mitigatedDamage = ru.nexora.combat.EnchantmentCombatService
                        .applyIncomingDamage(amount, armor,
                                ru.nexora.combat.EnchantmentCombatService.DamageType.GENERIC);
                if (mitigatedDamage > 0.0f) {
                    applyDamage(networkId, target, mitigatedDamage, tick, "death.attack.mob");
                    if (!target.vitals.dead() && "minecraft:cave_spider".equals(mob.identifier())) {
                        applyPoison(target);
                    }
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
        SessionState recipient = sessions.get(networkId);
        if (recipient != null) syncVisibleWorldActors(networkId, recipient);
    }

    /**
     * Routes mob and projectile packets only to clients that currently track the actor.
     * If an actor crosses into a subscribed chunk it is spawned there; when it leaves,
     * clients receive RemoveActor instead of continuing to receive global movement updates.
     */
    private void broadcastLivingActorPacket(BedrockPacket packet) {
        int packetId = packet.packetId();
        if (packetId != BedrockAddActorCodec.ADD_ACTOR_PACKET_ID
                && packetId != BedrockAddItemActorCodec.REMOVE_ACTOR_PACKET_ID
                && packetId != BedrockMoveActorAbsoluteCodec.MOVE_ACTOR_ABSOLUTE_PACKET_ID
                && packetId != BedrockVitalsCodec.UPDATE_ATTRIBUTES_PACKET_ID
                && packetId != BedrockActorMotionCodec.SET_ACTOR_MOTION_PACKET_ID) {
            broadcastPacket(packet);
            return;
        }

        boolean uniqueIdEncoded = packetId == BedrockAddActorCodec.ADD_ACTOR_PACKET_ID
                || packetId == BedrockAddItemActorCodec.REMOVE_ACTOR_PACKET_ID;
        long encodedId = readFirstVarLong(packet.payload());
        long runtimeId = uniqueIdEncoded ? Math.abs((encodedId >>> 1) ^ -(encodedId & 1L)) : encodedId;
        if (runtimeId <= 0) return;

        if (packetId == BedrockAddItemActorCodec.REMOVE_ACTOR_PACKET_ID) {
            List<byte[]> encodedFrames = null;
            for (String networkId : actorReplicationIndex.actorViewers(runtimeId)) {
                SessionState recipient = sessions.get(networkId);
                if (recipient == null) {
                    actorReplicationIndex.removeActorViewer(runtimeId, networkId);
                    continue;
                }
                boolean wasVisible = recipient.visibleLivingEntityIds.remove(runtimeId)
                        | recipient.visibleArrowEntityIds.remove(runtimeId);
                actorReplicationIndex.removeActorViewer(runtimeId, networkId);
                if (wasVisible && recipient.clientInitialized && !recipient.rejected) {
                    if (encodedFrames == null) encodedFrames = encodeFrames(List.of(packet), true);
                    sendEncodedFrames(networkId, encodedFrames);
                }
            }
            return;
        }

        WorldLivingEntity entity = world.livingEntities().find(runtimeId).orElse(null);
        ru.nexora.combat.ArrowProjectile arrow = mobArrows.get(runtimeId);
        ru.nexora.combat.FireballProjectile fireball = mobFireballs.get(runtimeId);
        if (entity == null && arrow == null && fireball == null) {
            // Keep unknown actor packet types safe rather than silently dropping them.
            broadcastPacket(packet);
            return;
        }

        double x = entity != null ? entity.x() : arrow != null ? arrow.x() : fireball.x();
        double z = entity != null ? entity.z() : arrow != null ? arrow.z() : fireball.z();
        long uniqueId = entity != null ? entity.uniqueId() : arrow != null ? arrow.uniqueId() : fireball.uniqueId();
        int chunkX = Math.floorDiv((int) Math.floor(x), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(z), 16);
        long chunkKey = ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
        List<byte[]> movementFrames = null;
        List<byte[]> spawnFrames = null;
        List<byte[]> removeFrames = null;

        // New/current viewers are found directly from the actor's current chunk.
        for (String networkId : actorReplicationIndex.chunkViewers(chunkKey)) {
            SessionState recipient = sessions.get(networkId);
            if (recipient == null || !recipient.clientInitialized || recipient.rejected) continue;
            Set<Long> visibleIds = entity != null
                    ? recipient.visibleLivingEntityIds : recipient.visibleArrowEntityIds;
            boolean alreadyVisible = visibleIds.contains(runtimeId);
            try {
                if (!alreadyVisible) {
                    visibleIds.add(runtimeId);
                    actorReplicationIndex.addActorViewer(runtimeId, networkId);
                    if (spawnFrames == null) {
                        BedrockPacket spawn = entity != null
                                ? BedrockAddActorCodec.createAddActor(entity)
                                : arrow != null ? BedrockArrowActorCodec.createSpawn(arrow)
                                : BedrockFireballActorCodec.createSpawn(fireball);
                        List<BedrockPacket> initialActorPackets = entity != null
                                ? List.of(spawn, BedrockVitalsCodec.createActorHealthUpdate(
                                        entity.runtimeId(), entity.health(), entity.maxHealth(), 0))
                                : List.of(spawn);
                        spawnFrames = encodeFrames(initialActorPackets, true);
                    }
                    sendEncodedFrames(networkId, spawnFrames);
                } else {
                    if (movementFrames == null) movementFrames = encodeFrames(List.of(packet), true);
                    sendEncodedFrames(networkId, movementFrames);
                }
            } catch (RuntimeException exception) {
                if (!alreadyVisible) {
                    visibleIds.remove(runtimeId);
                    actorReplicationIndex.removeActorViewer(runtimeId, networkId);
                }
                System.err.println("NEXORA: unable to sync actor " + runtimeId + " to "
                        + networkId + ": " + exception.getMessage());
            }
        }

        // Previously tracking viewers outside the new chunk must receive one despawn.
        for (String networkId : actorReplicationIndex.actorViewers(runtimeId)) {
            SessionState recipient = sessions.get(networkId);
            if (recipient == null) {
                actorReplicationIndex.removeActorViewer(runtimeId, networkId);
                continue;
            }
            if (isWorldPositionVisible(recipient, x, z)) continue;
            Set<Long> visibleIds = entity != null
                    ? recipient.visibleLivingEntityIds : recipient.visibleArrowEntityIds;
            if (!visibleIds.remove(runtimeId)) {
                actorReplicationIndex.removeActorViewer(runtimeId, networkId);
                continue;
            }
            actorReplicationIndex.removeActorViewer(runtimeId, networkId);
            try {
                if (removeFrames == null) {
                    removeFrames = encodeFrames(
                            List.of(BedrockAddItemActorCodec.createRemoveActor(uniqueId)), true);
                }
                sendEncodedFrames(networkId, removeFrames);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to remove actor " + runtimeId + " from "
                        + networkId + ": " + exception.getMessage());
            }
        }
    }

    private static long readFirstVarLong(byte[] payload) {
        long value = 0;
        int shift = 0;
        for (int index = 0; index < Math.min(payload.length, 10); index++) {
            int current = Byte.toUnsignedInt(payload[index]);
            if (index == 9 && (current & 0xfe) != 0) {
                throw new IllegalArgumentException("Actor ID VarLong exceeds 64 bits");
            }
            value |= (long) (current & 0x7f) << shift;
            if ((current & 0x80) == 0) return value;
            shift += 7;
        }
        throw new IllegalArgumentException("Truncated actor ID VarLong");
    }

    /**
     * Explicitly prepares every chunk touched by an actor's 0.6-block horizontal footprint.
     * This is called only when an actor is spawned or placed; collision and movement queries
     * never load chunks as a side effect.
     */
    private void ensureActorFootprintChunksLoaded(double x, double z) {
        int minChunkX = Math.floorDiv((int) Math.floor(x - 0.3), 16);
        int maxChunkX = Math.floorDiv((int) Math.floor(x + 0.3), 16);
        int minChunkZ = Math.floorDiv((int) Math.floor(z - 0.3), 16);
        int maxChunkZ = Math.floorDiv((int) Math.floor(z + 0.3), 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                world.getChunk(chunkX, chunkZ);
            }
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
        ensureActorFootprintChunksLoaded(x, z);
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

    private String activeMobProvoker(long runtimeId, long nowMillis) {
        String provoker = livingEntityAggroTargets.get(runtimeId);
        if (provoker == null) return null;
        Long expiresAt = livingEntityAggroExpiresAtMillis.get(runtimeId);
        if (expiresAt == null || nowMillis >= expiresAt) {
            livingEntityAggroTargets.remove(runtimeId, provoker);
            livingEntityAggroExpiresAtMillis.remove(runtimeId);
            return null;
        }
        return provoker;
    }

    /** Clears every transient subsystem reference when a mob dies or is despawned. */
    private void cleanupLivingEntityRuntimeState(long runtimeId) {
        nextMobAttackMillis.remove(runtimeId);
        guardianBeamTracker.clear(runtimeId);
        mobAiLoadController.remove(runtimeId);
        mobPathCache.remove(runtimeId);
        mobStuckTracker.clear(runtimeId);
        mobFallTracker.clear(runtimeId);
        mobJumpController.clear(runtimeId);
        mobDespawnTracker.clear(runtimeId);
        mobFuseTracker.clear(runtimeId);
        endermanTeleportCooldownMillis.remove(runtimeId);
        livingEntityAggroTargets.remove(runtimeId);
        livingEntityAggroExpiresAtMillis.remove(runtimeId);
        livingEntityKillCredit.remove(runtimeId);
        String ownerKey = "mob:" + runtimeId;
        Set<Long> ownedArrows = mobArrowIdsByOwner.remove(ownerKey);
        if (ownedArrows != null) {
            for (long projectileId : List.copyOf(ownedArrows)) {
                ru.nexora.combat.ArrowProjectile arrow = mobArrows.get(projectileId);
                if (arrow != null && mobArrows.remove(projectileId, arrow)) {
                    unindexProjectile(mobArrowIdsByChunk, mobArrowChunkById, projectileId);
                    mobArrowOwnerById.remove(projectileId, ownerKey);
                    poisonMobArrows.remove(projectileId);
                    broadcastLivingActorPacket(
                            BedrockAddItemActorCodec.createRemoveActor(arrow.uniqueId()));
                }
            }
        }
        Set<Long> ownedFireballs = mobFireballIdsByOwner.remove(ownerKey);
        if (ownedFireballs != null) {
            for (long projectileId : List.copyOf(ownedFireballs)) {
                ru.nexora.combat.FireballProjectile fireball = mobFireballs.get(projectileId);
                if (fireball != null && mobFireballs.remove(projectileId, fireball)) {
                    unindexProjectile(mobFireballIdsByChunk, mobFireballChunkById, projectileId);
                    mobFireballOwnerById.remove(projectileId, ownerKey);
                    broadcastLivingActorPacket(
                            BedrockAddItemActorCodec.createRemoveActor(fireball.uniqueId()));
                }
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

    static void validateLoginProtocol(
            BedrockLoginPacketParser.LoginRequest login,
            int negotiatedProtocol,
            int requiredProtocol
    ) {
        Objects.requireNonNull(login, "login");
        if (negotiatedProtocol != requiredProtocol || login.clientProtocol() != negotiatedProtocol) {
            throw new SecurityException("Login protocol does not match the negotiated Bedrock protocol");
        }
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
        validateLoginProtocol(login, state.clientProtocol, serverProtocol);
        if (login.authenticationType() != 0) {
            throw new SecurityException("Only authenticated Xbox Live login is supported");
        }
        if (!state.identity.gameServerToken().equals(login.multiplayerToken())) {
            throw new SecurityException("Login GameServerToken does not match the verified NetherNet identity");
        }
        JsonNode clientData = verifyClientDataToken(login.rawClientDataToken(), state.identity);
        synchronized (xuidLoginLock(state.identity.xuid())) {
            ensureNoDuplicateXuidSession(message.networkId(), state.identity.xuid());
            ru.nexora.network.PlayerTransferRecord latestTransfer = assertPlayerMayLogin(
                    state.identity.xuid());
            acquireOnlinePlayerLease(state);
        boolean legacyStateFound = false;
        boolean authoritativeCheckpointRestored = false;
        PendingPlayerTransferRestoreRegistry.PendingRestore pendingTransfer =
                pendingTransferRestores.find(state.identity.xuid()).orElse(null);
        if (pendingTransfer != null) {
            try {
                pendingTransfer.fence().assertCurrent();
                PlayerStateCheckpointService.LoadedCheckpoint loaded = checkpointService
                        .loadAtRevision(state.identity.xuid(), pendingTransfer.checkpointRevision())
                        .orElseThrow(() -> new IOException("Exact transfer checkpoint revision is unavailable"));
                PlayerStateBundle.Restored restored = loaded.bundle().restore();
                state.inventory = restored.inventory();
                state.craftingUiInventory.restore(restored.craftingUi().snapshot());
                state.equipment = restored.equipment();
                restorePlayerProfile(state, restored.profile(),
                        state.identity.displayName() == null ? "unknown" : state.identity.displayName());
                pendingTransfer.fence().assertCurrent();
                if (!pendingTransferRestores.consume(pendingTransfer)) {
                    throw new IOException("Pending transfer restore was concurrently cancelled");
                }
                state.transferFence = pendingTransfer.fence();
                legacyStateFound = true;
                authoritativeCheckpointRestored = true;
            } catch (IOException | RuntimeException exception) {
                try {
                    if (pendingTransfer.fence().isRevokedOrExpired()) {
                        pendingTransferRestores.cancel(state.identity.xuid(),
                                pendingTransfer.fence().transferId(),
                                pendingTransfer.fence().fencingToken());
                    }
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                state.rejected = true;
                throw new SecurityException("Unable to safely restore claimed transfer checkpoint", exception);
            }
        }
        if (!legacyStateFound && transferOwnershipLedger != null) {
            try {
                Optional<PlayerStateBundle> canonical = checkpointService.load(state.identity.xuid());
                if (canonical.isPresent()) {
                    PlayerStateBundle.Restored restored = canonical.orElseThrow().restore();
                    state.inventory = restored.inventory();
                    state.craftingUiInventory.restore(restored.craftingUi().snapshot());
                    state.equipment = restored.equipment();
                    restorePlayerProfile(state, restored.profile(),
                            state.identity.displayName() == null ? "unknown" : state.identity.displayName());
                    legacyStateFound = true;
                    authoritativeCheckpointRestored = true;
                } else if (latestTransfer != null) {
                    throw new IOException(
                            "Durable transfer exists but its canonical player checkpoint is unavailable");
                }
            } catch (IOException | RuntimeException exception) {
                state.rejected = true;
                throw new SecurityException("Unable to restore shared canonical player state", exception);
            }
        }
        if (!authoritativeCheckpointRestored && inventoryStorage != null) {
            try {
                var restored = inventoryStorage.loadState(state.identity.xuid());
                if (restored.isPresent()) {
                    legacyStateFound = true;
                    state.inventory = restored.orElseThrow().inventory();
                    state.craftingUiInventory.restore(
                            restored.orElseThrow().craftingUiInventory().snapshot());
                }
            } catch (IOException | RuntimeException exception) {
                state.rejected = true;
                throw new SecurityException("Unable to safely restore authenticated player inventory", exception);
            }
        }
        if (!authoritativeCheckpointRestored && equipmentStorage != null) {
            try {
                Optional<PlayerEquipment> restoredEquipment = equipmentStorage.load(state.identity.xuid());
                if (restoredEquipment.isPresent()) {
                    legacyStateFound = true;
                    state.equipment = restoredEquipment.orElseThrow();
                } else {
                    state.equipment = new PlayerEquipment();
                }
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
        try {
            state.playerDeviceId = clientData.path("DeviceId").asText("");
            state.platformChatId = clientData.path("PlatformOnlineId").asText("");
            state.buildPlatform = BedrockPlayerSkinCodec.buildPlatform(clientData);
            state.playerListEntry = createPlayerListEntry(state.identity, clientData, state.entityId);
        } catch (RuntimeException exception) {
            // Cosmetic data must never prevent an otherwise authenticated player from joining.
            state.playerListEntry = null;
            System.err.println("NEXORA: player-list skin data rejected for " + state.identity.xuid()
                    + ": " + exception.getMessage());
        }

        String displayName = state.identity.displayName() == null
                ? "unknown" : state.identity.displayName();
        if (!authoritativeCheckpointRestored && playerStorage != null) {
            try {
                Optional<PlayerData> saved = playerStorage.load(state.identity.xuid());
                if (saved.isPresent()) {
                    legacyStateFound = true;
                    restorePlayerProfile(state, saved.orElseThrow(), displayName);
                }
            } catch (IOException | RuntimeException exception) {
                System.err.println("NEXORA: unable to load player profile for " + displayName
                        + ": " + exception.getMessage());
            }
        }
        if (!authoritativeCheckpointRestored && transferOwnershipLedger == null && checkpointService != null) {
            try {
                Optional<PlayerStateBundle> canonical = checkpointService.load(state.identity.xuid());
                if (canonical.isPresent()) {
                    PlayerStateBundle.Restored restored = canonical.orElseThrow().restore();
                    state.inventory = restored.inventory();
                    state.craftingUiInventory.restore(restored.craftingUi().snapshot());
                    state.equipment = restored.equipment();
                    restorePlayerProfile(state, restored.profile(), displayName);
                    System.out.println("NEXORA: restored canonical player checkpoint for " + displayName
                            + " because no legacy player components were present");
                }
            } catch (IOException | RuntimeException exception) {
                state.rejected = true;
                throw new SecurityException("Unable to safely restore canonical player checkpoint", exception);
            }
        }
        // NetherNet already provides an encrypted DTLS transport. Bedrock's
        // ServerToClientHandshake/ClientToServerHandshake exchange is optional;
        // waiting for ClientToServerHandshake without first sending the server
        // handshake deadlocks the vanilla client immediately after Login.
        state.clientHandshakeReceived = true;
        state.resourcePacksInfoSent = true;
        sendPacket(message.networkId(), BedrockBatchCodec.createPlayStatus(LOGIN_SUCCESS), true);
        sendPacket(message.networkId(), BedrockBatchCodec.createResourcePacksInfo(), true);
        System.out.printf("NEXORA: sent LoginSuccess and ResourcePacksInfo to %s (NetherNet DTLS; Bedrock handshake skipped)%n",
                displayName);
        System.out.printf("NEXORA: authenticated player %s%n", displayName);
        }
    }

    private ru.nexora.network.PlayerTransferRecord assertPlayerMayLogin(String xuid) {
        ru.nexora.network.DurableHandoffCoordinator ledger = transferOwnershipLedger;
        String nodeId = transferOwnershipNodeId;
        if (ledger == null || nodeId == null) return null;
        try {
            java.util.Optional<ru.nexora.network.PlayerTransferRecord> latest =
                    ledger.findLatestForPlayer(xuid);
            long now = System.currentTimeMillis();
            if (latest.isPresent()) {
                ru.nexora.network.PlayerTransferRecord record = latest.orElseThrow();
                if (record.state() == ru.nexora.network.PlayerTransferRecord.State.PREPARED
                        && nodeId.equals(record.sourceNode())
                        && now >= record.updatedAtEpochMillis()
                        && now - record.updatedAtEpochMillis() >= 30_000L) {
                    ledger.abortPrepared(record.transferId(), now);
                    latest = ledger.findLatestForPlayer(xuid);
                } else if (record.state() == ru.nexora.network.PlayerTransferRecord.State.CLAIMED
                        && record.leaseExpired(now)) {
                    // An expired claim can no longer commit. Whichever node
                    // wins this CAS revokes the old fence before login proceeds.
                    ledger.abort(record.transferId(), record.fencingToken(), now);
                    latest = ledger.findLatestForPlayer(xuid);
                }
            }
            ru.nexora.network.PlayerTransferOwnershipGuard.loginDecision(latest, nodeId);
            return latest.orElse(null);
        } catch (IOException failure) {
            throw new SecurityException("Unable to verify durable player ownership before login", failure);
        }
    }

    private void assertTransferNodeOwnership(SessionState state) throws IOException {
        ru.nexora.network.DurableHandoffCoordinator ledger = transferOwnershipLedger;
        String nodeId = transferOwnershipNodeId;
        if (ledger == null || nodeId == null || state.identity == null) return;
        if (state.onlineLeaseLost) {
            throw new IOException("Player session has already lost global online ownership");
        }
        long now = nanoTime.getAsLong();
        String leaseSessionId = state.onlineLeaseSessionId;
        ru.nexora.network.PlayerOnlineLeaseRegistry leases = onlineLeaseRegistry;
        if (leases != null && state.loginAuthenticated && leaseSessionId == null) {
            throw new IOException("Authenticated player session has no global online ownership lease");
        }
        if (leases != null && leaseSessionId != null) {
            long previousLeaseRenewal = state.lastOnlineLeaseRenewalNanos;
            long renewalInterval = Math.max(500_000_000L, onlineLeaseMillis * 1_000_000L / 3L);
            if (previousLeaseRenewal == Long.MIN_VALUE || now - previousLeaseRenewal >= renewalInterval) {
                if (!leases.renew(state.identity.xuid(), nodeId, leaseSessionId,
                        System.currentTimeMillis(), onlineLeaseMillis)) {
                    state.onlineLeaseLost = true;
                    throw new IOException("Global online player lease expired or was acquired by another node");
                }
                state.lastOnlineLeaseRenewalNanos = now;
            }
        }
        long previous = state.lastTransferOwnershipCheckNanos;
        if (previous != Long.MIN_VALUE && now - previous < TRANSFER_OWNERSHIP_RECHECK_NANOS) return;
        java.util.Optional<ru.nexora.network.PlayerTransferRecord> latest =
                ledger.findLatestForPlayer(state.identity.xuid());
        if (!ru.nexora.network.PlayerTransferOwnershipGuard.mayProcessSession(latest, nodeId)) {
            throw new IOException("Latest durable transfer decision assigns this player to another node or is unresolved");
        }
        state.lastTransferOwnershipCheckNanos = now;
    }

    private Object xuidLoginLock(String xuid) {
        return xuidLoginLocks[Math.floorMod(xuid.hashCode(), xuidLoginLocks.length)];
    }

    private void ensureNoDuplicateXuidSession(String incomingNetworkId, String xuid) {
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            if (entry.getKey().equals(incomingNetworkId)) continue;
            SessionState existing = entry.getValue();
            synchronized (existing) {
                if (!existing.rejected && existing.identity != null
                        && xuid.equals(existing.identity.xuid())
                        && (existing.loginAuthenticated || existing.transferFrozen)) {
                    throw new SecurityException("XUID already has an active or transferring session on this node");
                }
            }
        }
    }

    private static Object[] createXuidLoginLocks() {
        Object[] locks = new Object[256];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    private void restorePlayerProfile(SessionState state, PlayerData player, String displayName) {
        state.vitals = player.vitals();
        state.experiencePoints = player.experiencePoints();
        state.experienceRemainderMilli = player.experienceRemainderMilli();
        state.dead = state.vitals.dead();
        if (player.worldName().equals(world.name())) {
            ensureActorFootprintChunksLoaded(player.x(), player.z());
        }
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

    private void handleClientHandshake(String networkId, SessionState state, BedrockPacket packet) {
        if (!state.loginAuthenticated) {
            throw new SecurityException("ClientToServerHandshake arrived before authenticated Login");
        }
        if (packet.payload().length != 0) {
            throw new IllegalArgumentException("ClientToServerHandshake must have an empty payload");
        }

        // NetherNet's DTLS layer provides transport encryption; no Bedrock application cipher is enabled.
        // Login already completed this optional exchange for NetherNet clients. Some Bedrock clients
        // still send ClientToServerHandshake, so accept it as an idempotent no-op instead of rejecting
        // an otherwise valid login as a duplicate.
        if (state.clientHandshakeReceived) {
            return;
        }
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
        System.out.printf("NEXORA: resource-pack response from %s: %s (%d)%n",
                networkId, response.name(), response.response());
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
                        ensureActorFootprintChunksLoaded(state.positionX, state.positionZ);
                        if (!WorldCollision.canOccupy(world, state.positionX, state.positionY, state.positionZ)) {
                            throw new IllegalStateException("Configured NEXORA world spawn is obstructed");
                        }
                        state.positionInitialized = true;
                    }
                    // Loading the player's destination is an explicit spawn operation.
                    // Collision probes themselves remain read-only and never generate chunks.
                    ensureActorFootprintChunksLoaded(state.positionX, state.positionZ);
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
                    sendPackets(networkId, List.of(
                            BedrockBiomeDefinitionCodec.createPacket(),
                            BedrockChunkRadiusCodec.createUpdatedPacket(state.chunkViewRadius)), true);
                    sendInitialChunkArea(networkId, state);
                }
                System.out.printf("NEXORA: resource-pack negotiation completed for %s; StartGame and initial chunks queued%n",
                        networkId);
            }
            default -> throw new IllegalArgumentException("Unknown resource-pack response");
        }
    }


    private void handlePlayerAuthInput(String networkId, SessionState state, BedrockPacket packet) {
        if (!state.clientInitialized) {
            // Bedrock clients can emit their first movement input while the spawn
            // acknowledgement is still in flight over the reliable channel. Do not
            // treat this transient ordering as a protocol violation: discard input
            // until SetLocalPlayerAsInitialized admits gameplay.
            if (!state.preInitializationInputObserved) {
                state.preInitializationInputObserved = true;
                System.out.printf("NEXORA: ignored early PlayerAuthInput from %s while awaiting SetLocalPlayerAsInitialized%n",
                        networkId);
            }
            return;
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
        broadcastPlayerMovement(networkId, state);

        for (BedrockPlayerAuthInputCodec.BlockAction action : input.blockActions()) {
            handleBlockAction(networkId, state, action);
        }
        handleItemUseTransaction(networkId, state, input.itemUseTransaction());
        tickWorldItemActors(input.tick());

        int chunkX = Math.floorDiv((int) Math.floor(input.x()), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(input.z()), 16);
        if (isChunkViewCenterDifferent(state, chunkX, chunkZ)) {
            sendPacket(networkId, BedrockStartGameCodec.createChunkPublisherUpdate(
                    (int) Math.floor(input.x()), (int) Math.floor(input.y()), (int) Math.floor(input.z()),
                    state.chunkViewRadius), true);
            sendChunkArea(networkId, state, chunkX, chunkZ);
        } else {
            processChunkStreaming(networkId, state);
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
        broadcastPlayerMovement(networkId, state);
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
        if (isChunkViewCenterDifferent(state, chunkX, chunkZ)) {
            sendPacket(networkId, BedrockStartGameCodec.createChunkPublisherUpdate(
                    (int) Math.floor(state.positionX), (int) Math.floor(state.positionY),
                    (int) Math.floor(state.positionZ), state.chunkViewRadius), true);
            sendChunkArea(networkId, state, chunkX, chunkZ);
        } else {
            processChunkStreaming(networkId, state);
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


    private void applyPoison(SessionState target) {
        long now = nanoTime.getAsLong();
        if (target.poisonUntilNanos <= now) {
            target.nextPoisonDamageNanos = safeAddNanos(now, 1_000_000_000L);
        }
        target.poisonUntilNanos = safeAddNanos(now, 4_000_000_000L);
    }

    private void tickPlayerPoisonEffects(long tick) {
        long now = nanoTime.getAsLong();
        for (var entry : sessions.entrySet()) {
            SessionState target = entry.getValue();
            target.combatLock.lock();
            try {
                synchronized (target) {
                    if (target.poisonUntilNanos == Long.MIN_VALUE) continue;
                    if (!target.loginAuthenticated || target.dead || target.vitals.dead()
                            || now >= target.poisonUntilNanos) {
                        target.poisonUntilNanos = Long.MIN_VALUE;
                        target.nextPoisonDamageNanos = Long.MIN_VALUE;
                        continue;
                    }
                    if (now < target.nextPoisonDamageNanos) continue;
                    target.nextPoisonDamageNanos = safeAddNanos(now, 1_000_000_000L);
                    float poisonDamage = Math.min(1.0f, Math.max(0.0f, target.vitals.health() - 1.0f));
                    if (poisonDamage > 0.0f) {
                        applyDamage(entry.getKey(), target, poisonDamage, tick, "death.attack.poison");
                    }
                }
            } finally {
                target.combatLock.unlock();
            }
        }
    }

    static long mobAttackDeadline(long nowMillis, long cooldownMillis) {
        if (nowMillis < 0 || cooldownMillis < 0) {
            throw new IllegalArgumentException("Mob attack timestamps must be non-negative");
        }
        return nowMillis > Long.MAX_VALUE - cooldownMillis
                ? Long.MAX_VALUE : nowMillis + cooldownMillis;
    }

    private static long safeAddNanos(long now, long duration) {
        return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
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
            case AIR, BEDROCK, SPAWNER -> null;
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
            case SPAWNER -> 5_000_000_000L;
        };
    }

    private void handleSubChunkRequest(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        if (!message.reliable()) {
            throw new SecurityException("SubChunkRequest must arrive on ReliableDataChannel");
        }
        if (!state.loginAuthenticated || !state.gameStarted || !state.positionInitialized
                || state.sentChunkKeys.isEmpty()) {
            throw new SecurityException("SubChunkRequest arrived before chunk streaming was ready");
        }

        BedrockSubChunkCodec.Request request;
        try {
            request = BedrockSubChunkCodec.decodeRequest(packet);
        } catch (IllegalArgumentException exception) {
            state.rejected = true;
            throw new SecurityException("Malformed Bedrock SubChunkRequest", exception);
        }

        List<BedrockSubChunkCodec.Response> responses = new ArrayList<>(request.offsets().size());
        for (BedrockSubChunkCodec.Offset offset : request.offsets()) {
            if (request.dimension() != 0) {
                responses.add(new BedrockSubChunkCodec.Response(
                        offset, BedrockSubChunkCodec.RESULT_WRONG_DIMENSION, null));
                continue;
            }
            long chunkX = (long) request.centerX() + offset.x();
            long subChunkY = (long) request.centerY() + offset.y();
            long chunkZ = (long) request.centerZ() + offset.z();
            if (chunkX < Integer.MIN_VALUE || chunkX > Integer.MAX_VALUE
                    || chunkZ < Integer.MIN_VALUE || chunkZ > Integer.MAX_VALUE
                    || subChunkY < -4 || subChunkY > 19) {
                responses.add(new BedrockSubChunkCodec.Response(
                        offset, BedrockSubChunkCodec.RESULT_INDEX_OUT_OF_BOUNDS, null));
                continue;
            }

            int absoluteChunkX = (int) chunkX;
            int absoluteChunkZ = (int) chunkZ;
            long chunkKey = ((long) absoluteChunkX << 32) | (absoluteChunkZ & 0xffff_ffffL);
            if (!state.sentChunkKeys.contains(chunkKey)) {
                responses.add(new BedrockSubChunkCodec.Response(
                        offset, BedrockSubChunkCodec.RESULT_LEVEL_CHUNK_DOES_NOT_EXIST, null));
                continue;
            }

            // A SubChunkRequest may arrive for the previous published view while
            // residency pressure is retiring its chunks. Never resurrect chunks from
            // this read-only network path; only the bounded streaming queue may load them.
            WorldChunk residentChunk = world.getLoadedChunkOrNull(absoluteChunkX, absoluteChunkZ);
            if (residentChunk == null) {
                responses.add(new BedrockSubChunkCodec.Response(
                        offset, BedrockSubChunkCodec.RESULT_LEVEL_CHUNK_DOES_NOT_EXIST, null));
                continue;
            }
            byte[] serialized = BedrockLevelChunkCodec.createSubChunkData(
                    residentChunk, (int) subChunkY);
            if (serialized == null) {
                responses.add(new BedrockSubChunkCodec.Response(
                        offset, BedrockSubChunkCodec.RESULT_SUCCESS_ALL_AIR, null));
            } else {
                responses.add(new BedrockSubChunkCodec.Response(
                        offset, BedrockSubChunkCodec.RESULT_SUCCESS, serialized));
            }
        }

        if (!responses.isEmpty()) {
            sendPacket(message.networkId(), BedrockSubChunkCodec.createResponsePacket(request, responses), true);
        }
    }

    private void handleChunkRadiusRequest(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        if (!message.reliable()) {
            throw new SecurityException("RequestChunkRadius must arrive on ReliableDataChannel");
        }
        if (!state.loginAuthenticated || !state.gameStarted || !state.positionInitialized) {
            throw new SecurityException("RequestChunkRadius arrived before the world was ready");
        }
        BedrockChunkRadiusCodec.Request request;
        try {
            request = BedrockChunkRadiusCodec.decodeRequest(packet);
        } catch (IllegalArgumentException exception) {
            state.rejected = true;
            throw new SecurityException("Malformed Bedrock RequestChunkRadius", exception);
        }
        int radius = BedrockChunkRadiusCodec.negotiateRadius(request);
        int previousRadius = state.chunkViewRadius;
        state.chunkViewRadius = radius;
        sendPacket(message.networkId(), BedrockChunkRadiusCodec.createUpdatedPacket(radius), true);
        int centerX = Math.floorDiv((int) Math.floor(state.positionX), 16);
        int centerZ = Math.floorDiv((int) Math.floor(state.positionZ), 16);
        if (previousRadius != radius || isChunkViewCenterDifferent(state, centerX, centerZ)) {
            sendPacket(message.networkId(), BedrockStartGameCodec.createChunkPublisherUpdate(
                    (int) Math.floor(state.positionX), (int) Math.floor(state.positionY),
                    (int) Math.floor(state.positionZ), state.chunkViewRadius), true);
            sendChunkArea(message.networkId(), state, centerX, centerZ);
        }
    }

    private void sendInitialChunkArea(String networkId, SessionState state) {
        int centerX = Math.floorDiv((int) Math.floor(state.positionX), 16);
        int centerZ = Math.floorDiv((int) Math.floor(state.positionZ), 16);
        sendChunkArea(networkId, state, centerX, centerZ);
    }

    private static boolean isChunkViewCenterDifferent(SessionState state, int centerX, int centerZ) {
        ChunkStreamingQueue pending = state.chunkStreamingQueue;
        if (pending != null) {
            return pending.centerX() != centerX || pending.centerZ() != centerZ;
        }
        return state.lastChunkX != centerX || state.lastChunkZ != centerZ;
    }

    private static final int MAX_CHUNK_PACKETS_PER_BATCH = 4;
    private static final int MAX_CHUNKS_PER_SESSION_TICK = 4;
    private static final int MAX_WORLD_CHUNK_LOADS_PER_TICK = 16;

    private void sendChunkArea(String networkId, SessionState state, int centerX, int centerZ) {
        synchronized (chunkStreamingMonitor) {
            ChunkStreamingQueue pending = state.chunkStreamingQueue;
            if (pending == null || pending.centerX() != centerX || pending.centerZ() != centerZ
                    || pending.radius() != state.chunkViewRadius) {
                chunkStreamingBudget.yield(networkId, nanoTime.getAsLong());
                state.chunkStreamingQueue = new ChunkStreamingQueue(centerX, centerZ, state.chunkViewRadius);
                state.pendingChunkLoads.clear();
            }
            processChunkStreaming(networkId, state);
        }
    }

    /**
     * Encodes a bounded number of chunks on each world tick until the initial view is sent. A changed center
     * replaces the unfinished traversal; already published chunks are reused.
     */
    public void tickInitialChunkStreams() {
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            String networkId = entry.getKey();
            SessionState state = entry.getValue();

            // Like PocketMine/Nukkit's per-tick send queues, retry queued frames even
            // when this peer has no new inbound packet. This is required when the
            // global byte budget defers a large but valid frame until the next window.
            drainOutboundForSession(networkId);

            if (!state.gameStarted || state.clientInitialized || state.rejected
                    || state.chunkStreamingQueue == null) continue;
            try {
                processChunkStreaming(networkId, state);
            } catch (RuntimeException failure) {
                System.err.println("NEXORA: initial chunk streaming failed for " + networkId
                        + ": " + failure.getMessage());
            }
        }
    }

    private void processChunkStreaming(String networkId, SessionState state) {
        synchronized (chunkStreamingMonitor) {
            long startedNanos = nanoTime.getAsLong();
            try {
                processChunkStreamingInternal(networkId, state);
            } finally {
                hotPathProfiler.record(ru.nexora.ai.HotPathProfiler.Path.BEDROCK_CHUNK_STREAMING,
                        Math.max(0L, nanoTime.getAsLong() - startedNanos));
            }
        }
    }

    private void processChunkStreamingInternal(String networkId, SessionState state) {
        ChunkStreamingQueue queue = state.chunkStreamingQueue;
        if (queue == null) return;

        List<Long> chunkKeys = queue.peekBatch(MAX_CHUNKS_PER_SESSION_TICK);
        if (!chunkKeys.isEmpty()) {
            chunkStreamingBatches.incrementAndGet();
            List<BedrockPacket> packets = new ArrayList<>(MAX_CHUNK_PACKETS_PER_BATCH);
            List<Long> publishedKeys = new ArrayList<>(MAX_CHUNK_PACKETS_PER_BATCH);
            boolean batchReady = true;
            boolean budgetDenied = false;
            for (long key : chunkKeys) {
                if (state.sentChunkKeys.contains(key)) {
                    chunkPacketsReused.incrementAndGet();
                    continue;
                }
                int chunkX = (int) (key >> 32);
                int chunkZ = (int) key;
                CompletableFuture<Optional<WorldChunk>> load = state.pendingChunkLoads.computeIfAbsent(
                        key, ignored -> world.requestStoredChunkLoad(networkId, chunkX, chunkZ));
                if (!load.isDone()) {
                    batchReady = false;
                    continue;
                }
                Optional<WorldChunk> prepared;
                try {
                    prepared = load.join();
                } catch (java.util.concurrent.CompletionException failure) {
                    if (failure.getCause() instanceof java.util.concurrent.RejectedExecutionException) {
                        // Keep this chunk pending and retry on a later input tick once I/O capacity frees.
                        state.pendingChunkLoads.remove(key, load);
                        batchReady = false;
                        continue;
                    }
                    throw failure;
                }
                if (!chunkStreamingBudget.tryAcquire(networkId, nanoTime.getAsLong())) {
                    batchReady = false;
                    budgetDenied = true;
                    break;
                }
                state.pendingChunkLoads.remove(key, load);
                WorldChunk chunk = world.getChunkFromPreparedStorageLoad(chunkX, chunkZ, prepared);
                packets.add(BedrockLevelChunkCodec.createPacket(chunk));
                chunkPacketsEncoded.incrementAndGet();
                publishedKeys.add(key);
                if (packets.size() >= MAX_CHUNK_PACKETS_PER_BATCH) {
                    flushChunkBatch(networkId, state, packets, publishedKeys);
                }
            }
            flushChunkBatch(networkId, state, packets, publishedKeys);
            // Ready chunks can publish while the rest of this four-key batch waits for disk I/O.
            // Keep the cursor in place until every key is either sent or ready.
            if (!batchReady) {
                if (!budgetDenied) chunkStreamingBudget.yield(networkId, nanoTime.getAsLong());
                return;
            }
            queue.advance(chunkKeys.size());
            chunkStreamingPendingPeak.accumulateAndGet(queue.remaining(), Math::max);
        }

        if (!queue.isComplete()) return;

        // Reconcile visibility only after the complete target view has been published.
        state.sentChunkKeys.retainAll(queue.desiredChunkKeys());
        actorReplicationIndex.replaceChunkView(networkId, state.sentChunkKeys);
        itemActorReplicationIndex.replaceChunkView(networkId, state.sentChunkKeys);
        playerActorReplicationIndex.replaceChunkView(networkId, state.sentChunkKeys);
        state.lastChunkX = queue.centerX();
        state.lastChunkZ = queue.centerZ();
        if (!state.clientInitialized) {
            System.out.printf("NEXORA: initial chunk view published for %s: center=(%d,%d), chunks=%d%n",
                    networkId, queue.centerX(), queue.centerZ(), state.sentChunkKeys.size());
            // Bedrock's spawn handshake is two-sided: after the complete initial chunk
            // view is available, the server sends PlayStatus.PLAYER_SPAWN (3); only then
            // does the client send SetLocalPlayerAsInitialized. Sending status 3 from
            // handleClientInitialized deadlocks the first join because the client is
            // waiting for this status before it can acknowledge initialization.
            if (!state.playerSpawnStatusSent) {
                sendPacket(networkId, BedrockBatchCodec.createPlayStatus(3), true);
                state.playerSpawnStatusSent = true;
                System.out.printf("NEXORA: sent PlayStatus PLAYER_SPAWN to %s after initial chunks%n", networkId);
            }
        }
        state.chunkStreamingQueue = null;
        state.pendingChunkLoads.clear();
        chunkStreamingBudget.pause(networkId);
        chunkStreamingCompleted.incrementAndGet();
        syncVisiblePlayerActors(networkId, state);
        syncVisibleWorldActors(networkId, state);
    }

    private void flushChunkBatch(
            String networkId, SessionState state,
            List<BedrockPacket> pendingPackets, List<Long> pendingChunkKeys
    ) {
        if (pendingPackets.isEmpty()) return;
        // Only publish chunk keys after the complete batch was accepted by the transport.
        sendPackets(networkId, pendingPackets, true);
        state.sentChunkKeys.addAll(pendingChunkKeys);
        pendingPackets.clear();
        pendingChunkKeys.clear();
    }

    /** Reconciles remote player actors whenever a client's published chunk view changes. */
    private void syncVisiblePlayerActors(String recipientNetworkId, SessionState recipient) {
        if (!recipient.clientInitialized || !recipient.playerListAdvertised) return;
        BedrockPacket remove = null;
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            if (entry.getKey().equals(recipientNetworkId)) continue;
            SessionState player = entry.getValue();
            if (!player.clientInitialized || !player.playerListAdvertised
                    || player.playerListEntry == null) continue;
            boolean visible = isPlayerChunkVisible(recipient, player);
            if (visible && recipient.visiblePlayerEntityIds.add(player.entityId)) {
                playerActorReplicationIndex.addActorViewer(player.entityId, recipientNetworkId);
                try {
                    sendPackets(recipientNetworkId, createRemotePlayerSpawnPackets(player), true);
                } catch (RuntimeException exception) {
                    recipient.visiblePlayerEntityIds.remove(player.entityId);
                    playerActorReplicationIndex.removeActorViewer(player.entityId, recipientNetworkId);
                    System.err.println("NEXORA: unable to add player entering chunk view: " + exception.getMessage());
                }
            } else if (!visible && recipient.visiblePlayerEntityIds.remove(player.entityId)) {
                playerActorReplicationIndex.removeActorViewer(player.entityId, recipientNetworkId);
                if (remove == null) remove = BedrockAddItemActorCodec.createRemoveActor(player.entityId);
                try {
                    sendPacket(recipientNetworkId, remove, true);
                } catch (RuntimeException exception) {
                    System.err.println("NEXORA: unable to remove player leaving chunk view: " + exception.getMessage());
                }
            }
        }
    }

    /** Reconciles server-owned mobs and projectiles against the recipient's loaded chunk set. */
    private void syncVisibleWorldActors(String recipientNetworkId, SessionState recipient) {
        if (!recipient.clientInitialized || recipient.rejected) return;
        java.util.Set<ru.nexora.world.WorldChunkResidencyManager.ChunkCoordinate> residentChunks =
                new java.util.HashSet<>(recipient.sentChunkKeys.size());
        for (long chunkKey : recipient.sentChunkKeys) {
            residentChunks.add(new ru.nexora.world.WorldChunkResidencyManager.ChunkCoordinate(
                    (int) (chunkKey >> 32), (int) chunkKey));
        }
        // The living-actor manager maintains a chunk index, so a newly published view
        // only visits mobs in those chunks rather than every dormant world actor.
        for (WorldLivingEntity entity : world.livingEntities()
                .snapshotInChunks(residentChunks).residentEntities()) {
            if (!isWorldPositionVisible(recipient, entity.x(), entity.z())
                    || !recipient.visibleLivingEntityIds.add(entity.runtimeId())) continue;
            actorReplicationIndex.addActorViewer(entity.runtimeId(), recipientNetworkId);
            try {
                sendPackets(recipientNetworkId, List.of(
                        BedrockAddActorCodec.createAddActor(entity),
                        BedrockVitalsCodec.createActorHealthUpdate(
                                entity.runtimeId(), entity.health(), entity.maxHealth(), 0)), true);
            } catch (RuntimeException exception) {
                recipient.visibleLivingEntityIds.remove(entity.runtimeId());
                actorReplicationIndex.removeActorViewer(entity.runtimeId(), recipientNetworkId);
                System.err.println("NEXORA: unable to add living actor entering chunk view: "
                        + exception.getMessage());
            }
        }
        // Actors that were visible before a view change may have walked out of it.
        // Reconcile only this recipient's visible set; dormant actors are never scanned.
        for (long runtimeId : java.util.List.copyOf(recipient.visibleLivingEntityIds)) {
            WorldLivingEntity entity = world.livingEntities().find(runtimeId).orElse(null);
            if (entity != null && isWorldPositionVisible(recipient, entity.x(), entity.z())) continue;
            if (!recipient.visibleLivingEntityIds.remove(runtimeId)) continue;
            actorReplicationIndex.removeActorViewer(runtimeId, recipientNetworkId);
            if (entity == null) continue; // Explicit death/despawn replication owns this removal.
            try {
                sendPacket(recipientNetworkId,
                        BedrockAddItemActorCodec.createRemoveActor(entity.uniqueId()), true);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to remove living actor leaving chunk view: "
                        + exception.getMessage());
            }
        }
        if (itemRegistry != null) {
            for (WorldItemEntity entity : world.itemEntities().snapshotInChunkKeys(recipient.sentChunkKeys)) {
                if (itemRegistry.findByIdentifier(entity.stack().itemId()) == null) continue;
                boolean visible = isWorldPositionVisible(recipient, entity.x(), entity.z());
                if (visible && recipient.visibleItemEntityIds.add(entity.runtimeId())) {
                    itemActorReplicationIndex.addActorViewer(entity.runtimeId(), recipientNetworkId);
                    try {
                        sendPacket(recipientNetworkId,
                                BedrockAddItemActorCodec.createAddItemActor(entity, itemRegistry), true);
                    } catch (RuntimeException exception) {
                        recipient.visibleItemEntityIds.remove(entity.runtimeId());
                        itemActorReplicationIndex.removeActorViewer(entity.runtimeId(), recipientNetworkId);
                        System.err.println("NEXORA: unable to add dropped item entering chunk view: "
                                + exception.getMessage());
                    }
                } else if (!visible && recipient.visibleItemEntityIds.remove(entity.runtimeId())) {
                    itemActorReplicationIndex.removeActorViewer(entity.runtimeId(), recipientNetworkId);
                    try {
                        sendPacket(recipientNetworkId,
                                BedrockAddItemActorCodec.createRemoveActor(entity.uniqueId()), true);
                    } catch (RuntimeException exception) {
                        System.err.println("NEXORA: unable to remove dropped item leaving chunk view: "
                                + exception.getMessage());
                    }
                }
            }
        }
        // Only inspect projectiles indexed in chunks already published to this client.
        for (ru.nexora.combat.ArrowProjectile arrow : snapshotMobArrowsInChunkKeys(recipient.sentChunkKeys)) {
            if (!recipient.visibleArrowEntityIds.add(arrow.runtimeId())) continue;
            actorReplicationIndex.addActorViewer(arrow.runtimeId(), recipientNetworkId);
            try {
                sendPacket(recipientNetworkId, BedrockArrowActorCodec.createSpawn(arrow), true);
            } catch (RuntimeException exception) {
                recipient.visibleArrowEntityIds.remove(arrow.runtimeId());
                actorReplicationIndex.removeActorViewer(arrow.runtimeId(), recipientNetworkId);
                System.err.println("NEXORA: unable to add projectile entering chunk view: "
                        + exception.getMessage());
            }
        }
        for (ru.nexora.combat.FireballProjectile fireball
                : snapshotMobFireballsInChunkKeys(recipient.sentChunkKeys)) {
            if (!recipient.visibleArrowEntityIds.add(fireball.runtimeId())) continue;
            actorReplicationIndex.addActorViewer(fireball.runtimeId(), recipientNetworkId);
            try {
                sendPacket(recipientNetworkId, BedrockFireballActorCodec.createSpawn(fireball), true);
            } catch (RuntimeException exception) {
                recipient.visibleArrowEntityIds.remove(fireball.runtimeId());
                actorReplicationIndex.removeActorViewer(fireball.runtimeId(), recipientNetworkId);
                System.err.println("NEXORA: unable to add fireball entering chunk view: "
                        + exception.getMessage());
            }
        }
        // Reconcile only projectiles this recipient was already tracking; no global scan.
        for (long runtimeId : List.copyOf(recipient.visibleArrowEntityIds)) {
            ru.nexora.combat.ArrowProjectile arrow = mobArrows.get(runtimeId);
            ru.nexora.combat.FireballProjectile fireball = mobFireballs.get(runtimeId);
            if (arrow != null && isWorldPositionVisible(recipient, arrow.x(), arrow.z())) continue;
            if (fireball != null && isWorldPositionVisible(recipient, fireball.x(), fireball.z())) continue;
            if (!recipient.visibleArrowEntityIds.remove(runtimeId)) continue;
            actorReplicationIndex.removeActorViewer(runtimeId, recipientNetworkId);
            if (arrow == null && fireball == null) continue; // Explicit despawn owns this removal.
            long uniqueId = arrow != null ? arrow.uniqueId() : fireball.uniqueId();
            try {
                sendPacket(recipientNetworkId,
                        BedrockAddItemActorCodec.createRemoveActor(uniqueId), true);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to remove projectile leaving chunk view: "
                        + exception.getMessage());
            }
        }
    }

    private static long projectileChunkKey(double x, double z) {
        int chunkX = Math.floorDiv((int) Math.floor(x), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(z), 16);
        return ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
    }

    private static void indexProjectile(
            Map<Long, Set<Long>> idsByChunk, Map<Long, Long> chunkById,
            long runtimeId, double x, double z
    ) {
        long key = projectileChunkKey(x, z);
        chunkById.put(runtimeId, key);
        idsByChunk.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(runtimeId);
    }

    private static void unindexProjectile(
            Map<Long, Set<Long>> idsByChunk, Map<Long, Long> chunkById, long runtimeId
    ) {
        Long key = chunkById.remove(runtimeId);
        if (key == null) return;
        Set<Long> ids = idsByChunk.get(key);
        if (ids == null) return;
        ids.remove(runtimeId);
        if (ids.isEmpty()) idsByChunk.remove(key, ids);
    }

    private static void indexProjectileOwner(
            Map<String, Set<Long>> idsByOwner, Map<Long, String> ownerById,
            long runtimeId, String ownerNetworkId
    ) {
        ownerById.put(runtimeId, ownerNetworkId);
        idsByOwner.computeIfAbsent(ownerNetworkId, ignored -> ConcurrentHashMap.newKeySet())
                .add(runtimeId);
    }

    private static void unindexProjectileOwner(
            Map<String, Set<Long>> idsByOwner, Map<Long, String> ownerById, long runtimeId
    ) {
        String owner = ownerById.remove(runtimeId);
        if (owner == null) return;
        Set<Long> ids = idsByOwner.get(owner);
        if (ids == null) return;
        ids.remove(runtimeId);
        if (ids.isEmpty()) idsByOwner.remove(owner, ids);
    }

    private static void reindexProjectile(
            Map<Long, Set<Long>> idsByChunk, Map<Long, Long> chunkById,
            long runtimeId, double oldX, double oldZ, double newX, double newZ
    ) {
        long previous = projectileChunkKey(oldX, oldZ);
        long next = projectileChunkKey(newX, newZ);
        if (previous == next) return;
        unindexProjectile(idsByChunk, chunkById, runtimeId);
        indexProjectile(idsByChunk, chunkById, runtimeId, newX, newZ);
    }

    List<ru.nexora.combat.ArrowProjectile> snapshotMobArrowsInChunkKeys(Set<Long> chunkKeys) {
        java.util.TreeSet<Long> ids = new java.util.TreeSet<>();
        for (long key : chunkKeys) {
            Set<Long> inChunk = mobArrowIdsByChunk.get(key);
            if (inChunk != null) ids.addAll(inChunk);
        }
        List<ru.nexora.combat.ArrowProjectile> result = new ArrayList<>(ids.size());
        for (long id : ids) {
            ru.nexora.combat.ArrowProjectile arrow = mobArrows.get(id);
            if (arrow != null) result.add(arrow);
        }
        return List.copyOf(result);
    }

    List<ru.nexora.combat.FireballProjectile> snapshotMobFireballsInChunkKeys(Set<Long> chunkKeys) {
        java.util.TreeSet<Long> ids = new java.util.TreeSet<>();
        for (long key : chunkKeys) {
            Set<Long> inChunk = mobFireballIdsByChunk.get(key);
            if (inChunk != null) ids.addAll(inChunk);
        }
        List<ru.nexora.combat.FireballProjectile> result = new ArrayList<>(ids.size());
        for (long id : ids) {
            ru.nexora.combat.FireballProjectile fireball = mobFireballs.get(id);
            if (fireball != null) result.add(fireball);
        }
        return List.copyOf(result);
    }

    private static boolean isWorldPositionVisible(SessionState recipient, double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) return false;
        int chunkX = Math.floorDiv((int) Math.floor(x), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(z), 16);
        long key = ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
        return recipient.sentChunkKeys.contains(key);
    }

    private static boolean isPlayerChunkVisible(SessionState recipient, SessionState player) {
        if (!Float.isFinite(player.positionX) || !Float.isFinite(player.positionZ)) return false;
        int chunkX = Math.floorDiv((int) Math.floor(player.positionX), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(player.positionZ), 16);
        long key = ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
        return recipient.sentChunkKeys.contains(key);
    }

    private void handlePacketViolationWarning(String networkId, BedrockPacket packet) {
        try {
            BedrockPacketViolationWarningCodec.Violation violation =
                    BedrockPacketViolationWarningCodec.decode(packet);
            String type = BedrockPacketViolationWarningCodec.typeName(violation.type());
            String severity = BedrockPacketViolationWarningCodec.severityName(violation.severity());
            String rawHex = java.util.HexFormat.of().formatHex(packet.payload());
            System.err.println("NEXORA: client PacketViolationWarning from " + networkId
                    + ": type=" + type + " (" + violation.type() + ")"
                    + ", severity=" + severity + " (" + violation.severity() + ")"
                    + ", offendingServerPacket=0x" + Integer.toHexString(violation.packetId()).toUpperCase(Locale.ROOT)
                    + " (" + violation.packetId() + ")"
                    + ", context=\"" + violation.context().replace("\\", "\\\\")
                            .replace("\"", "\\\"").replace("\\n", " ") + "\""
                    + ", rawPayloadHex=" + rawHex);
        } catch (IllegalArgumentException malformed) {
            String rawHex = java.util.HexFormat.of().formatHex(packet.payload());
            System.err.println("NEXORA: malformed client PacketViolationWarning from " + networkId
                    + ": " + malformed.getMessage() + ", rawPayloadHex=" + rawHex);
        }
    }

    private void handleLoadingScreen(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        String networkId = message.networkId();
        byte[] payload = packet.payload();
        String payloadHex = java.util.HexFormat.of().formatHex(payload);
        if (!message.reliable()) {
            throw new SecurityException("ServerboundLoadingScreen must arrive on ReliableDataChannel");
        }
        if (!state.gameStarted) {
            throw new SecurityException("Loading screen state arrived before StartGame");
        }

        final BedrockLoadingScreenCodec.LoadingScreen loadingScreen;
        try {
            loadingScreen = BedrockLoadingScreenCodec.decode(packet);
        } catch (IllegalArgumentException malformed) {
            System.err.printf("NEXORA: rejected ServerboundLoadingScreen from %s; payload=%s (%d bytes): %s%n",
                    networkId, payloadHex, payload.length, malformed.getMessage());
            throw new SecurityException("Malformed protocol-2225 ServerboundLoadingScreen", malformed);
        }
        System.out.printf("NEXORA: ServerboundLoadingScreen from %s; type=%s, screenId=%s, payload=%s%n",
                networkId, loadingScreen.isStart() ? "START" : "END",
                loadingScreen.screenId() == null ? "absent" : loadingScreen.screenId(), payloadHex);
        if (loadingScreen.screenId() != null) {
            // NEXORA currently has no server-initiated ChangeDimension flow. Initial world join
            // must therefore use the optional, absent loading-screen ID from protocol 2225.
            throw new SecurityException("Unexpected loading screen ID without a pending dimension transition");
        }
        if (loadingScreen.isStart()) {
            state.initialLoadingScreenStarted = true;
        } else {
            state.initialLoadingScreenEnded = true;
        }
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
        if (state.transferFence != null) {
            try {
                state.transferFence.assertCommitted();
            } catch (IOException uncommitted) {
                state.rejected = true;
                throw new SecurityException("Destination ownership is not committed; gameplay admission denied",
                        uncommitted);
            }
        }
        long runtimeEntityId = readUnsignedVarLong(packet.payload());
        if (runtimeEntityId != state.entityId) {
            throw new SecurityException("SetLocalPlayerAsInitialized runtime ID does not match this player");
        }
        if (transferOwnershipLedger != null) {
            try {
                // First login on a cluster node may have migrated legacy local files.
                // Persist the complete canonical aggregate before admitting gameplay.
                checkpointCanonicalPlayerState(state);
            } catch (IOException | RuntimeException failure) {
                state.rejected = true;
                state.loginAuthenticated = false;
                throw new SecurityException("Unable to persist shared canonical player state before admission",
                        failure);
            }
        }
        state.clientInitialized = true;
        System.out.printf("NEXORA: SetLocalPlayerAsInitialized accepted from %s (runtimeId=%d); admitting gameplay.%n",
                message.networkId(), runtimeEntityId);
        advertisePlayerList(message.networkId(), state);
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
            sendPackets(networkId, initialPackets, true);
        } catch (IllegalArgumentException exception) {
            System.err.println("NEXORA: initial inventory sync deferred because a stored item has no Bedrock definition: " + exception.getMessage());
        } catch (RuntimeException exception) {
            System.err.println("NEXORA: initial inventory sync failed for " + networkId + ": "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            exception.printStackTrace(System.err);
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
        boolean removing = packet.packetId() == BedrockItemActorCodec.REMOVE_ACTOR_PACKET_ID;
        long runtimeId = entity.runtimeId();
        if (removing) {
            List<byte[]> removeFrames = null;
            for (String networkId : itemActorReplicationIndex.actorViewers(runtimeId)) {
                SessionState recipient = sessions.get(networkId);
                itemActorReplicationIndex.removeActorViewer(runtimeId, networkId);
                if (recipient == null || !recipient.visibleItemEntityIds.remove(runtimeId)
                        || !recipient.clientInitialized || recipient.rejected) continue;
                try {
                    if (removeFrames == null) removeFrames = encodeFrames(List.of(packet), true);
                    sendEncodedFrames(networkId, removeFrames);
                } catch (RuntimeException exception) {
                    System.err.println("NEXORA: unable to remove item actor from " + networkId
                            + ": " + exception.getMessage());
                }
            }
            return;
        }

        int chunkX = Math.floorDiv((int) Math.floor(entity.x()), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(entity.z()), 16);
        long chunkKey = ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
        List<byte[]> movementFrames = null;
        List<byte[]> spawnFrames = null;
        List<byte[]> removeFrames = null;

        // Visit only sessions subscribed to the item's current chunk.
        for (String networkId : itemActorReplicationIndex.chunkViewers(chunkKey)) {
            SessionState recipient = sessions.get(networkId);
            if (recipient == null || !recipient.clientInitialized || recipient.rejected) continue;
            boolean alreadyVisible = recipient.visibleItemEntityIds.contains(runtimeId);
            try {
                if (!alreadyVisible) {
                    if (itemRegistry.findByIdentifier(entity.stack().itemId()) == null) continue;
                    recipient.visibleItemEntityIds.add(runtimeId);
                    itemActorReplicationIndex.addActorViewer(runtimeId, networkId);
                    if (spawnFrames == null) {
                        spawnFrames = encodeFrames(
                                List.of(BedrockAddItemActorCodec.createAddItemActor(entity, itemRegistry)), true);
                    }
                    sendEncodedFrames(networkId, spawnFrames);
                } else {
                    if (movementFrames == null) movementFrames = encodeFrames(List.of(packet), true);
                    sendEncodedFrames(networkId, movementFrames);
                }
            } catch (RuntimeException exception) {
                if (!alreadyVisible) {
                    recipient.visibleItemEntityIds.remove(runtimeId);
                    itemActorReplicationIndex.removeActorViewer(runtimeId, networkId);
                }
                System.err.println("NEXORA: unable to sync item actor to " + networkId
                        + ": " + exception.getMessage());
            }
        }

        // Previous viewers that no longer subscribe to this chunk receive one despawn.
        for (String networkId : itemActorReplicationIndex.actorViewers(runtimeId)) {
            SessionState recipient = sessions.get(networkId);
            if (recipient == null) {
                itemActorReplicationIndex.removeActorViewer(runtimeId, networkId);
                continue;
            }
            if (isWorldPositionVisible(recipient, entity.x(), entity.z())) continue;
            if (!recipient.visibleItemEntityIds.remove(runtimeId)) {
                itemActorReplicationIndex.removeActorViewer(runtimeId, networkId);
                continue;
            }
            itemActorReplicationIndex.removeActorViewer(runtimeId, networkId);
            try {
                if (removeFrames == null) {
                    removeFrames = encodeFrames(
                            List.of(BedrockAddItemActorCodec.createRemoveActor(entity.uniqueId())), true);
                }
                sendEncodedFrames(networkId, removeFrames);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to remove item actor from " + networkId
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
        broadcastPlayerEquipment(networkId, state);
    }

    /** Publishes authoritative held-item changes to clients that currently see this player. */
    private void broadcastPlayerEquipment(String sourceNetworkId, SessionState player) {
        BedrockItemRegistry registry = itemRegistry;
        if (registry == null || player == null || player.entityId <= 0) return;
        BedrockPacket equipment = BedrockMobEquipmentCodec.createPlayerEquipment(
                player.entityId, player.inventory, registry);
        List<byte[]> frames = null;
        for (String networkId : playerActorReplicationIndex.actorViewers(player.entityId)) {
            if (networkId.equals(sourceNetworkId)) continue;
            SessionState recipient = sessions.get(networkId);
            if (recipient == null || !recipient.clientInitialized || !recipient.playerListAdvertised
                    || !recipient.visiblePlayerEntityIds.contains(player.entityId)) continue;
            try {
                if (frames == null) frames = encodeFrames(List.of(equipment), true);
                sendEncodedFrames(networkId, frames);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to broadcast held item to " + networkId
                        + ": " + exception.getMessage());
            }
        }
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

    private BedrockPlayerListCodec.AddEntry createPlayerListEntry(
            NetherNetClientIdentity identity, JsonNode clientData, long actorUniqueId
    ) {
        UUID playerUuid = parsePlayerUuid(identity.identityUuid());
        if (playerUuid == null) playerUuid = parsePlayerUuid(clientData.path("SelfSignedId").asText(""));
        if (playerUuid == null) {
            playerUuid = UUID.nameUUIDFromBytes(
                    ("NEXORA:" + identity.xuid()).getBytes(StandardCharsets.UTF_8));
        }
        String playerName = identity.displayName() == null || identity.displayName().isBlank()
                ? "Player" : identity.displayName();
        return new BedrockPlayerListCodec.AddEntry(
                playerUuid,
                actorUniqueId,
                playerName,
                identity.xuid(),
                identity.playFabId(),
                clientData.path("PlatformOnlineId").asText(""),
                BedrockPlayerSkinCodec.buildPlatform(clientData),
                BedrockPlayerSkinCodec.encode(clientData),
                false,
                false,
                false,
                0);
    }

    /** Sends the current player-list snapshot, then advertises this player to existing clients. */
    private synchronized void advertisePlayerList(String networkId, SessionState joining) {
        if (joining.rejected || joining.playerListAdvertised || joining.playerListEntry == null) return;
        List<BedrockPlayerListCodec.AddEntry> snapshot = new ArrayList<>();
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            if (entry.getKey().equals(networkId)) continue;
            SessionState existing = entry.getValue();
            if (existing.clientInitialized && existing.playerListAdvertised
                    && existing.playerListEntry != null) {
                snapshot.add(existing.playerListEntry);
            }
        }
        snapshot.add(joining.playerListEntry);
        try {
            sendPacket(networkId, BedrockPlayerListCodec.createAdd(snapshot), true);
            joining.playerListAdvertised = true;
        } catch (RuntimeException exception) {
            System.err.println("NEXORA: unable to send player-list snapshot to " + networkId
                    + ": " + exception.getMessage());
            return;
        }
        // Spawn only actors inside the joining client's currently published chunk view.
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            if (entry.getKey().equals(networkId)) continue;
            SessionState existing = entry.getValue();
            if (!existing.clientInitialized || !existing.playerListAdvertised
                    || existing.playerListEntry == null
                    || !isPlayerChunkVisible(joining, existing)) continue;
            try {
                sendPackets(networkId, createRemotePlayerSpawnPackets(existing), true);
                joining.visiblePlayerEntityIds.add(existing.entityId);
                playerActorReplicationIndex.addActorViewer(existing.entityId, networkId);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to spawn existing player for " + networkId
                        + ": " + exception.getMessage());
            }
        }
        BedrockPacket addListEntry = BedrockPlayerListCodec.createAdd(List.of(joining.playerListEntry));
        List<BedrockPacket> addPlayer = createRemotePlayerSpawnPackets(joining);
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            if (entry.getKey().equals(networkId)) continue;
            SessionState recipient = entry.getValue();
            if (!recipient.clientInitialized || !recipient.playerListAdvertised) continue;
            try {
                // The client must know the UUID/skin before AddPlayer can create the remote actor.
                sendPacket(entry.getKey(), addListEntry, true);
                if (isPlayerChunkVisible(recipient, joining)) {
                    sendPackets(entry.getKey(), addPlayer, true);
                    recipient.visiblePlayerEntityIds.add(joining.entityId);
                    playerActorReplicationIndex.addActorViewer(joining.entityId, entry.getKey());
                }
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to advertise and spawn joining player for "
                        + entry.getKey() + ": " + exception.getMessage());
            }
        }
    }

    /** Removes a disconnected player from every remaining initialized client's list. */
    private synchronized void removePlayerListEntry(String networkId, SessionState leaving) {
        if (!leaving.playerListAdvertised || leaving.playerListEntry == null) return;
        leaving.playerListAdvertised = false;
        BedrockPacket remove = BedrockPlayerListCodec.createRemove(
                List.of(leaving.playerListEntry.uuid()));
        BedrockPacket removeActor = BedrockAddItemActorCodec.createRemoveActor(leaving.entityId);
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            if (entry.getKey().equals(networkId)) continue;
            SessionState recipient = entry.getValue();
            if (!recipient.clientInitialized || !recipient.playerListAdvertised) continue;
            try {
                if (recipient.visiblePlayerEntityIds.remove(leaving.entityId)) {
                    playerActorReplicationIndex.removeActorViewer(leaving.entityId, entry.getKey());
                    sendPacket(entry.getKey(), removeActor, true);
                }
                sendPacket(entry.getKey(), remove, true);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to remove disconnected player from " + entry.getKey()
                        + ": " + exception.getMessage());
            }
        }
    }

    private static UUID parsePlayerUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Builds a complete remote-player spawn, including the currently held hotbar item. */
    private List<BedrockPacket> createRemotePlayerSpawnPackets(SessionState player) {
        List<BedrockPacket> packets = new ArrayList<>();
        packets.add(createAddPlayerPacket(player));
        BedrockItemRegistry registry = itemRegistry;
        if (registry != null) {
            packets.add(BedrockMobEquipmentCodec.createPlayerEquipment(
                    player.entityId, player.inventory, registry));
        }
        // A remote player needs the same authoritative health, hunger and experience
        // snapshot as the local player; AddPlayer alone does not carry these values.
        packets.add(BedrockVitalsCodec.createSetHealth(player.vitals));
        packets.add(createPlayerAttributes(player, Math.max(0, player.lastInputTick)));
        return List.copyOf(packets);
    }

    private BedrockPacket createAddPlayerPacket(SessionState player) {
        BedrockPlayerListCodec.AddEntry entry = Objects.requireNonNull(
                player.playerListEntry, "playerListEntry");
        return BedrockAddPlayerCodec.createSpawn(new BedrockAddPlayerCodec.SpawnData(
                entry.uuid(),
                entry.playerName(),
                player.entityId,
                player.platformChatId,
                player.positionX,
                player.positionY + 1.62,
                player.positionZ,
                (float) player.velocityX,
                (float) player.velocityY,
                (float) player.velocityZ,
                player.pitch,
                player.yaw,
                player.yaw,
                player.playerDeviceId,
                player.buildPlatform));
    }

    private void broadcastPlayerMovement(String sourceNetworkId, SessionState player) {
        if (!player.clientInitialized || !player.playerListAdvertised || player.playerListEntry == null) return;
        boolean onGround = player.physicsState != null && player.physicsState.onGround();
        BedrockPacket movement = BedrockMoveActorAbsoluteCodec.create(
                player.entityId, player.positionX, player.positionY + 1.62, player.positionZ,
                player.pitch, player.yaw, player.yaw, onGround);
        int chunkX = Math.floorDiv((int) Math.floor(player.positionX), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(player.positionZ), 16);
        long chunkKey = ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
        List<byte[]> movementFrames = null;
        List<byte[]> spawnFrames = null;
        List<byte[]> removeFrames = null;

        // Current recipients come directly from the player's destination chunk.
        for (String networkId : playerActorReplicationIndex.chunkViewers(chunkKey)) {
            if (networkId.equals(sourceNetworkId)) continue;
            SessionState recipient = sessions.get(networkId);
            if (recipient == null || !recipient.clientInitialized || !recipient.playerListAdvertised) continue;
            boolean alreadyVisible = recipient.visiblePlayerEntityIds.contains(player.entityId);
            try {
                if (!alreadyVisible) {
                    recipient.visiblePlayerEntityIds.add(player.entityId);
                    playerActorReplicationIndex.addActorViewer(player.entityId, networkId);
                    if (spawnFrames == null) {
                        List<BedrockPacket> spawnPackets = new ArrayList<>(createRemotePlayerSpawnPackets(player));
                        spawnPackets.add(movement);
                        spawnFrames = encodeFrames(spawnPackets, true);
                    }
                    sendEncodedFrames(networkId, spawnFrames);
                } else {
                    if (movementFrames == null) movementFrames = encodeFrames(List.of(movement), true);
                    sendEncodedFrames(networkId, movementFrames);
                }
            } catch (RuntimeException exception) {
                if (!alreadyVisible) {
                    recipient.visiblePlayerEntityIds.remove(player.entityId);
                    playerActorReplicationIndex.removeActorViewer(player.entityId, networkId);
                }
                System.err.println("NEXORA: unable to sync player movement to " + networkId
                        + ": " + exception.getMessage());
            }
        }

        // Previously visible recipients outside the new chunk receive one despawn.
        for (String networkId : playerActorReplicationIndex.actorViewers(player.entityId)) {
            if (networkId.equals(sourceNetworkId)) continue;
            SessionState recipient = sessions.get(networkId);
            if (recipient == null) {
                playerActorReplicationIndex.removeActorViewer(player.entityId, networkId);
                continue;
            }
            if (isPlayerChunkVisible(recipient, player)) continue;
            if (!recipient.visiblePlayerEntityIds.remove(player.entityId)) {
                playerActorReplicationIndex.removeActorViewer(player.entityId, networkId);
                continue;
            }
            playerActorReplicationIndex.removeActorViewer(player.entityId, networkId);
            try {
                if (removeFrames == null) {
                    removeFrames = encodeFrames(
                            List.of(BedrockAddItemActorCodec.createRemoveActor(player.entityId)), true);
                }
                sendEncodedFrames(networkId, removeFrames);
            } catch (RuntimeException exception) {
                System.err.println("NEXORA: unable to sync player despawn to " + networkId
                        + ": " + exception.getMessage());
            }
        }
    }

    private void sendPacket(String networkId, BedrockPacket packet, boolean compressionNegotiated) {
        sendPackets(networkId, List.of(packet), compressionNegotiated);
    }

    private void sendPackets(String networkId, List<BedrockPacket> packets, boolean compressionNegotiated) {
        sendEncodedFrames(networkId, encodeFrames(packets, compressionNegotiated));
    }

    /**
     * Encodes and fragments a packet batch once so actor fanout can reuse the immutable
     * wire representation for every interested client instead of recompressing per recipient.
     */
    private List<byte[]> encodeFrames(List<BedrockPacket> packets, boolean compressionNegotiated) {
        byte[] batch = BedrockBatchCodec.encode(
                packets, compressionNegotiated, compressionThreshold);
        return NetherNetMessageFramer.fragment(batch, SCTP_MAX_MESSAGE_BYTES, true);
    }

    private void sendEncodedFrames(String networkId, List<byte[]> frames) {
        SessionState state = sessions.get(networkId);
        if (state == null) {
            sendFramesDirect(networkId, frames);
            return;
        }
        synchronized (state.outboundLock) {
            if (state.outboundClosed) {
                throw new IllegalStateException("Cannot send Bedrock frames to a disconnected session");
            }
            // Drain an older transient backlog before admitting more data for this session.
            drainOutboundFrames(networkId, state);
            long batchBytes = outboundFrameBytes(frames);
            if (!reserveOutboundBytes(batchBytes)) {
                outboundBackpressureRejections.incrementAndGet();
                throw new IllegalStateException("NEXORA global outbound queue budget is full");
            }
            if (!state.outboundQueue.offerAll(frames)) {
                outboundQueuedBytesGlobal.addAndGet(-batchBytes);
                outboundBackpressureRejections.incrementAndGet();
                throw new IllegalStateException("Bedrock outbound queue is full for session " + networkId
                        + " (frames=" + state.outboundQueue.frameCount()
                        + ", bytes=" + state.outboundQueue.queuedBytes() + ")");
            }
            drainOutboundFrames(networkId, state);
        }
        // A frame admitted for one peer may rotate the global budget to another
        // peer. Give every currently queued peer a bounded opportunity now that
        // the originating session lock is released; otherwise idle peers can
        // remain queued forever waiting for their own next inbound packet.
        drainPendingOutboundSessions(networkId);
    }

    private void drainPendingOutboundSessions(String alreadyDrainedNetworkId) {
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            if (!entry.getKey().equals(alreadyDrainedNetworkId)) {
                drainOutboundForSession(entry.getKey());
            }
        }
    }

    private static long outboundFrameBytes(List<byte[]> frames) {
        long total = 0L;
        for (byte[] frame : frames) {
            Objects.requireNonNull(frame, "outbound frame");
            if (frame.length > Long.MAX_VALUE - total) {
                throw new IllegalArgumentException("Outbound frame batch size overflow");
            }
            total += frame.length;
        }
        return total;
    }

    private boolean reserveOutboundBytes(long bytes) {
        if (bytes < 0 || bytes > MAX_TOTAL_OUTBOUND_QUEUED_BYTES) return false;
        while (true) {
            long current = outboundQueuedBytesGlobal.get();
            if (bytes > MAX_TOTAL_OUTBOUND_QUEUED_BYTES - current) return false;
            if (outboundQueuedBytesGlobal.compareAndSet(current, current + bytes)) return true;
        }
    }

    private void clearOutboundQueue(SessionState state) {
        long releasedBytes = state.outboundQueue.queuedBytes();
        state.outboundQueue.clear();
        if (releasedBytes != 0L) outboundQueuedBytesGlobal.addAndGet(-releasedBytes);
    }

    private void drainOutboundFrames(String networkId, SessionState state) {
        long startedNanos = nanoTime.getAsLong();
        try {
            drainOutboundFramesInternal(networkId, state);
        } finally {
            hotPathProfiler.record(ru.nexora.ai.HotPathProfiler.Path.BEDROCK_OUTBOUND_DRAIN,
                    Math.max(0L, nanoTime.getAsLong() - startedNanos));
        }
    }

    private void drainOutboundFramesInternal(String networkId, SessionState state) {
        int sentFrames = 0;
        long sentBytes = 0L;
        while (sentFrames < MAX_OUTBOUND_FRAMES_PER_DRAIN) {
            int nextSize = state.outboundQueue.nextFrameSize();
            if (nextSize < 0) {
                outboundDrainBudget.pause(networkId);
                return;
            }
            if (sentFrames > 0 && nextSize > MAX_OUTBOUND_BYTES_PER_DRAIN - sentBytes) return;
            if (!outboundDrainBudget.tryAcquire(networkId, nextSize, nanoTime.getAsLong())) return;
            byte[] frame = state.outboundQueue.poll();
            if (frame == null) return;
            try {
                // The queue owns this copy, so the transport receives an isolated buffer.
                outbound.sendFrame(networkId, "ReliableDataChannel", frame);
                outboundFramesSent.incrementAndGet();
                outboundBytesSent.addAndGet(frame.length);
                outboundQueuedBytesGlobal.addAndGet(-frame.length);
                sentFrames++;
                sentBytes += frame.length;
            } catch (Exception exception) {
                outboundSendFailures.incrementAndGet();
                state.outboundQueue.offerFirst(frame);
                throw new IllegalStateException("Unable to send Bedrock packet batch", exception);
            }
        }
    }

    /** Gives a pending session backlog another bounded drain opportunity after inbound work. */
    private void drainOutboundForSession(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state == null) return;
        synchronized (state.outboundLock) {
            if (state.outboundClosed) {
                outboundDrainBudget.unregister(networkId);
                return;
            }
            if (state.outboundQueue.isEmpty()) {
                outboundDrainBudget.pause(networkId);
                return;
            }
            try {
                drainOutboundFrames(networkId, state);
            } catch (RuntimeException exception) {
                // Keep the failed frame at the queue head; a later inbound/send callback retries it.
                System.err.println("NEXORA: outbound queue drain deferred for " + networkId
                        + ": " + exception.getMessage());
            }
        }
    }

    private void sendFramesDirect(String networkId, List<byte[]> frames) {
        try {
            for (byte[] frame : frames) {
                outbound.sendFrame(networkId, "ReliableDataChannel", frame.clone());
                outboundFramesSent.incrementAndGet();
                outboundBytesSent.addAndGet(frame.length);
            }
        } catch (Exception exception) {
            outboundSendFailures.incrementAndGet();
            throw new IllegalStateException("Unable to send Bedrock packet batch", exception);
        }
    }

    private static JsonNode verifyClientDataToken(String token, NetherNetClientIdentity identity) {
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
            return claims;
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
                if (state.transferFrozen) continue;
                try {
                    returnCraftingGridItems(state);
                } catch (IOException | RuntimeException exception) {
                    System.err.println("NEXORA: unable to return crafting-grid items on shutdown: "
                            + exception.getMessage());
                }
            }
        }
        IOException persistenceFailure = null;
        try {
            // Do not report a clean shutdown when any authenticated player's
            // aggregate or compatibility persistence failed. The lifecycle must
            // receive this failure so operators and service managers can detect it.
            savePlayers();
        } catch (IOException exception) {
            persistenceFailure = exception;
            System.err.println("NEXORA: player persistence failed during shutdown: " + exception.getMessage());
        }
        for (SessionState state : sessions.values()) {
            // On persistence failure, keep shared ownership fenced until its TTL
            // expires. Releasing it immediately could let another node admit the
            // player using an older durable checkpoint.
            if (persistenceFailure == null) releaseOnlinePlayerLease(state);
        }
        for (SessionState state : sessions.values()) {
            synchronized (state.outboundLock) {
                state.outboundClosed = true;
                clearOutboundQueue(state);
            }
        }
        sessions.clear();
        actorReplicationIndex.clear();
        itemActorReplicationIndex.clear();
        playerActorReplicationIndex.clear();
        mobArrows.clear();
        mobArrowIdsByChunk.clear();
        mobArrowChunkById.clear();
        mobArrowIdsByOwner.clear();
        mobArrowOwnerById.clear();
        poisonMobArrows.clear();
        mobFireballs.clear();
        mobFireballIdsByChunk.clear();
        mobFireballChunkById.clear();
        mobFireballIdsByOwner.clear();
        mobFireballOwnerById.clear();
        guardianBeamTracker.clearAll();
        if (persistenceFailure != null) {
            throw new IllegalStateException(
                    "NEXORA shutdown completed with player persistence failures; inspect suppressed save errors",
                    persistenceFailure);
        }
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
        private final Object outboundLock = new Object();
        private final NetherNetOutboundQueue outboundQueue = new NetherNetOutboundQueue();
        private boolean outboundClosed;
        private long lastAttackNanos = Long.MIN_VALUE;
        private long chatWindowStartedNanos = Long.MIN_VALUE;
        private int chatMessagesInWindow;
        private long lastCombatDamageNanos = Long.MIN_VALUE;
        private long spearChargeStartedNanos = Long.MIN_VALUE;
        private long spearChargeReleasedNanos = Long.MIN_VALUE;
        private int spearChargeSlot = -1;
        private String spearChargeItemId;
        private ItemStack spearChargeItemSnapshot;
        private long fireUntilNanos = Long.MIN_VALUE;
        private long nextFireDamageNanos = Long.MIN_VALUE;
        private long poisonUntilNanos = Long.MIN_VALUE;
        private long nextPoisonDamageNanos = Long.MIN_VALUE;
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
        private volatile boolean gameStarted;
        private boolean initialLoadingScreenStarted;
        private boolean initialLoadingScreenEnded;
        private boolean playerSpawnStatusSent;
        private volatile boolean clientInitialized;
        private boolean preInitializationInputObserved;
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
        private volatile int chunkViewRadius = BedrockChunkRadiusCodec.DEFAULT_RADIUS;
        private volatile ChunkStreamingQueue chunkStreamingQueue;
        private final Map<Long, CompletableFuture<Optional<WorldChunk>>> pendingChunkLoads = new HashMap<>();
        private final Set<Long> sentChunkKeys = ConcurrentHashMap.newKeySet();
        private final Set<Long> visiblePlayerEntityIds = ConcurrentHashMap.newKeySet();
        private final Set<Long> visibleLivingEntityIds = ConcurrentHashMap.newKeySet();
        private final Set<Long> visibleArrowEntityIds = ConcurrentHashMap.newKeySet();
        private final Set<Long> visibleItemEntityIds = ConcurrentHashMap.newKeySet();
        private BreakTarget breakTarget;
        private long entityId;
        private volatile boolean rejected;
        private boolean disconnecting;
        private volatile boolean transferFrozen;
        private boolean transferRedirected;
        private PlayerTransferFence transferFence;
        private long lastTransferOwnershipCheckNanos = Long.MIN_VALUE;
        private volatile String onlineLeaseSessionId;
        private volatile long lastOnlineLeaseRenewalNanos = Long.MIN_VALUE;
        private volatile boolean onlineLeaseLost;
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
        private volatile NetherNetClientIdentity identity;
        private BedrockPlayerListCodec.AddEntry playerListEntry;
        private volatile boolean playerListAdvertised;
        private String playerDeviceId = "";
        private String platformChatId = "";
        private int buildPlatform = -1;
    }
}
