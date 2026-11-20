package ru.nexora;

import ru.nexora.network.NetworkRuntimeModule;
import ru.nexora.network.PlayerTransferLedgerFactory;
import ru.nexora.network.ClusterTransportFactory;
import ru.nexora.network.PlayerTransferClusterEndpoint;
import ru.nexora.network.PlayerTransferSourceCoordinator;
import ru.nexora.network.PlayerHandoff;
import ru.nexora.network.bedrock.BedrockPlayerTransferSource;
import ru.nexora.network.CheckpointedPlayerTransferDestination;
import ru.nexora.network.DurableHandoffCoordinator;
import ru.nexora.gameplay.GameplayRuntimeModule;
import ru.nexora.network.nethernet.CloudburstNetherNetTransport;
import ru.nexora.network.bedrock.BedrockSessionManager;
import ru.nexora.network.bedrock.BedrockPendingTransferActivator;
import ru.nexora.network.raknet.BedrockServerStatus;
import ru.nexora.network.raknet.RakNetDiscoveryServer;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;
import ru.nexora.world.WorldStorage;
import ru.nexora.world.WorldItemEntityStorage;
import ru.nexora.world.WorldLivingEntityStorage;
import ru.nexora.inventory.ContainerInventoryStorage;
import ru.nexora.inventory.FurnaceStateStorage;
import ru.nexora.world.WorldRuntimeModule;
import ru.nexora.world.PlayerDataStorage;
import ru.nexora.persistence.PlayerStateStoreFactory;
import ru.nexora.persistence.PlayerStateBundleCodec;
import ru.nexora.persistence.PlayerStateCheckpointService;
import ru.nexora.inventory.PlayerInventoryStorage;
import ru.nexora.inventory.PlayerEquipmentStorage;
import ru.nexora.inventory.DurabilitySettings;
import ru.nexora.ai.NexoraAiDiagnostics;
import ru.nexora.ai.DiagnosticsRuntimeModule;
import ru.nexora.lifecycle.ServerLifecycle;
import ru.nexora.lifecycle.ServerReadiness;
import ru.nexora.config.NexoraServerConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NEXORA Core bootstrap. Modern Bedrock transport is Cloudburst NetherNet over WebRTC.
 */
public final class NexoraServer {
    private NexoraServer() {
    }

    public static void main(String[] args) throws Exception {
        NexoraServerConfig config = NexoraServerConfig.from(args, System.getenv());
        String bindHost = config.bindHost();
        int port = config.port();
        String motd = config.motd();
        int maxPlayers = config.maxPlayers();

        InetAddress bindAddress = InetAddress.getByName(bindHost);
        InetSocketAddress socketAddress = new InetSocketAddress(bindAddress, port);
        String httpBindHost = config.httpBindHost();
        InetAddress httpBindAddress = InetAddress.getByName(httpBindHost);
        InetSocketAddress signalingAddress = new InetSocketAddress(httpBindAddress, port);

        BedrockServerStatus legacyStatus = new BedrockServerStatus(
                motd,
                2225,
                "1.26.62",
                0,
                maxPlayers,
                "Legacy RakNet discovery only",
                "Survival",
                1,
                port,
                port
        );
        ServerLifecycle lifecycle = new ServerLifecycle();
        ServerReadiness readiness = new ServerReadiness();
        RakNetDiscoveryServer discovery = new RakNetDiscoveryServer(
                socketAddress,
                legacyStatus,
                new java.security.SecureRandom().nextLong()
        );

        DurabilitySettings durabilitySettings = DurabilitySettings.fromEnvironment(System.getenv());
        DiagnosticsRuntimeModule diagnosticsRuntime = new DiagnosticsRuntimeModule(snapshot -> {
            if (snapshot.health() == NexoraAiDiagnostics.Health.WARNING) {
                String heapUsage = snapshot.heapUsageRatio() < 0.0 ? "unknown"
                        : String.format(java.util.Locale.ROOT, "%.1f%%", snapshot.heapUsageRatio() * 100.0);
                System.err.printf("NEXORA AI: runtime warning: %s (heap %s, threads %d, failures %d, slow operations %d)%n",
                        snapshot.reason(), heapUsage,
                        snapshot.liveThreadCount(), snapshot.recordedFailures(), snapshot.slowOperationCount());
            }
        });
        NexoraAiDiagnostics aiDiagnostics = diagnosticsRuntime.diagnostics();
        String dataDirectory = config.dataDirectory();
        WorldStorage worldStorage = new WorldStorage(Path.of(dataDirectory, "worlds"), "NEXORA World");
        WorldItemEntityStorage itemStorage = new WorldItemEntityStorage(
                Path.of(dataDirectory, "worlds"), "NEXORA World");
        WorldLivingEntityStorage livingEntityStorage = new WorldLivingEntityStorage(
                Path.of(dataDirectory, "worlds"), "NEXORA World");
        ContainerInventoryStorage containerStorage = new ContainerInventoryStorage(
                Path.of(dataDirectory, "worlds"), "NEXORA World");
        FurnaceStateStorage furnaceStateStorage = new FurnaceStateStorage(
                Path.of(dataDirectory, "worlds"), "NEXORA World");
        World world = new World(
                "NEXORA World", 0L, new FlatWorldGenerator(), 0, 65, 0,
                worldStorage, itemStorage, containerStorage, furnaceStateStorage, livingEntityStorage);
        PlayerDataStorage playerStorage = new PlayerDataStorage(Path.of(dataDirectory, "players"));
        PlayerInventoryStorage inventoryStorage = new PlayerInventoryStorage(Path.of(dataDirectory, "inventories"));
        PlayerEquipmentStorage equipmentStorage = new PlayerEquipmentStorage(Path.of(dataDirectory, "equipment"));
        PlayerStateStoreFactory.ConfiguredStore configuredPlayerStateStore =
                PlayerStateStoreFactory.open(Path.of(dataDirectory, "player-state"), System.getenv());
        PlayerStateCheckpointService playerStateCheckpoints = new PlayerStateCheckpointService(
                configuredPlayerStateStore.store(), new PlayerStateBundleCodec());
        PlayerTransferLedgerFactory.ConfiguredLedger configuredTransferLedger =
                PlayerTransferLedgerFactory.open(Path.of(dataDirectory), System.getenv());
        if (configuredTransferLedger.shared() && !configuredPlayerStateStore.shared()) {
            throw new IllegalStateException(
                    "A shared transfer ledger requires a shared JDBC player checkpoint store on every node");
        }
        String configuredNodeId = System.getenv("NEXORA_NODE_ID");
        if (configuredTransferLedger.shared() && (configuredNodeId == null || configuredNodeId.isBlank())) {
            throw new IllegalStateException(
                    "NEXORA_NODE_ID must be explicitly configured when using a shared JDBC transfer ledger");
        }
        String transferNodeId = configuredNodeId == null || configuredNodeId.isBlank()
                ? "standalone" : configuredNodeId.trim();
        DurableHandoffCoordinator transferLedger = configuredTransferLedger.coordinator();
        java.util.Optional<ClusterTransportFactory.ConfiguredTransport> configuredClusterTransport =
                ClusterTransportFactory.open(transferNodeId, Path.of(dataDirectory), System.getenv());
        if (configuredClusterTransport.isPresent()
                && (!configuredTransferLedger.shared() || !configuredPlayerStateStore.shared())) {
            throw new IllegalStateException(
                    "Cluster transport requires both shared JDBC transfer ledger and shared player checkpoints");
        }
        long transferLeaseMillis = longEnvironmentValue(
                "NEXORA_TRANSFER_LEASE_MS", 15_000L, 1_000L, 120_000L);
        long onlinePlayerLeaseMillis = longEnvironmentValue(
                "NEXORA_ONLINE_PLAYER_LEASE_MS", 15_000L, 3_000L, 120_000L);
        AtomicReference<CloudburstNetherNetTransport> transportReference = new AtomicReference<>();
        BedrockSessionManager bedrockSessions = new BedrockSessionManager(
                2225,
                "1.26.62",
                BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world,
                playerStorage,
                inventoryStorage,
                equipmentStorage,
                playerStateCheckpoints,
                (networkId, channelLabel, frame) -> {
                    CloudburstNetherNetTransport active = transportReference.get();
                    if (active == null) throw new IllegalStateException("NetherNet transport is not ready");
                    active.sendFrame(networkId, channelLabel, frame);
                },
                event -> System.out.printf("NEXORA: Bedrock packet 0x%02X from %s (%d bytes)%n",
                        event.packet().packetId(), event.networkId(), event.packet().payload().length),
                config.maximumResidentChunks());

        if (configuredTransferLedger.shared()) {
            bedrockSessions.configureOnlinePlayerLeases(
                    configuredTransferLedger.onlineLeases(), onlinePlayerLeaseMillis);
            bedrockSessions.configureTransferOwnershipGuard(transferLedger, transferNodeId);
        }
        ru.nexora.network.PlayerTransferRecoveryService.RecoveryReport transferRecovery =
                bedrockSessions.recoverPendingTransferRestores(transferLedger, transferNodeId, 10_000);
        System.out.printf("NEXORA: transfer recovery for node %s scanned %d, restored %d, already pending %d, expired %d, superseded %d%n",
                transferNodeId, transferRecovery.scanned(), transferRecovery.registered(),
                transferRecovery.alreadyRegistered(), transferRecovery.expiredClaims(),
                transferRecovery.supersededRecords());
        NetworkRuntimeModule.ManagedService clusterService;
        if (configuredClusterTransport.isPresent()) {
            PlayerTransferClusterEndpoint transferEndpoint = new PlayerTransferClusterEndpoint(
                    transferNodeId,
                    transferLedger,
                    new CheckpointedPlayerTransferDestination(
                            transferLedger, playerStateCheckpoints, transferNodeId,
                            new BedrockPendingTransferActivator(bedrockSessions), System::currentTimeMillis),
                    transferLeaseMillis,
                    System::currentTimeMillis);
            ClusterTransportFactory.ConfiguredTransport cluster = configuredClusterTransport.orElseThrow();
            clusterService = NetworkRuntimeModule.service(
                    () -> cluster.transport().start(message -> {
                        try {
                            transferEndpoint.handle(message);
                        } catch (Exception failure) {
                            throw new IllegalStateException("Rejected NEXORA cluster message", failure);
                        }
                    }),
                    cluster.transport());
            System.out.println("NEXORA: " + cluster.description() + ".");
        } else {
            clusterService = NetworkRuntimeModule.service(() -> {}, () -> {});
        }
        GameplayRuntimeModule gameplayRuntime = new GameplayRuntimeModule(
                bedrockSessions, aiDiagnostics, System.getenv());
        diagnosticsRuntime.safeRepairs().register(
                "cache.mob-path.clear",
                "Clear transient mob navigation cache (world and entity state are untouched)",
                false,
                () -> bedrockSessions.mobPathCacheSize() > 0,
                bedrockSessions::clearMobPathCache,
                () -> bedrockSessions.mobPathCacheSize() == 0);
        diagnosticsRuntime.safeRepairs().register(
                "cache.mob-path.prune-expired",
                "Prune expired transient mob navigation entries",
                true,
                () -> bedrockSessions.expiredMobPathCacheEntries() > 0,
                bedrockSessions::pruneExpiredMobPathCache,
                () -> bedrockSessions.expiredMobPathCacheEntries() == 0);
        List<String> stunUrls = config.stunUrls();
        int udpPortMin = config.udpPortMin();
        int udpPortMax = config.udpPortMax();
        CloudburstNetherNetTransport transport = new CloudburstNetherNetTransport(
                signalingAddress,
                motd,
                world.name(),
                maxPlayers,
                udpPortMin,
                udpPortMax,
                stunUrls,
                System.getenv(),
                Path.of(dataDirectory),
                bedrockSessions,
                () -> (int) Math.min(bedrockSessions.authenticatedPlayerCount(), maxPlayers));
        transportReference.set(transport);

        System.out.printf("NEXORA: world chunk residency target is %d resident chunks (entity-pinned chunks may exceed it).%n",
                config.maximumResidentChunks());
        long autosaveSeconds = config.autosaveSeconds();
        WorldRuntimeModule worldRuntime = new WorldRuntimeModule(
                world,
                Duration.ofSeconds(autosaveSeconds),
                exception -> {
                    aiDiagnostics.recordFailure("world-autosave", exception);
                    System.err.println("NEXORA: periodic world/player autosave failed: " + exception.getMessage());
                },
                bedrockSessions::savePlayers,
                bedrockSessions::renewOnlinePlayerLeases,
                exception -> {
                    aiDiagnostics.recordFailure("online-player-lease-heartbeat", exception);
                    System.err.println("NEXORA: online player lease heartbeat failed: " + exception.getMessage());
                },
                elapsed -> aiDiagnostics.recordSlowOperation(
                        "world-autosave", elapsed, Duration.ofSeconds(1).toNanos()),
                exception -> {
                    aiDiagnostics.recordFailure("furnace-tick", exception);
                    System.err.println("NEXORA: furnace tick failed: " + exception.getMessage());
                },
                elapsed -> aiDiagnostics.recordSlowOperation("furnace-tick", elapsed, 50_000_000L));

        NetworkRuntimeModule networkRuntime = new NetworkRuntimeModule(
                clusterService,
                NetworkRuntimeModule.service(discovery::start, discovery),
                NetworkRuntimeModule.service(transport::start, transport),
                () -> {},
                () -> {
                    try {
                        if (!bedrockSessions.awaitNoActiveSessions(Duration.ofSeconds(5))) {
                            System.err.println("NEXORA: graceful session drain timed out; remaining sessions will be force-closed and saved.");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("Interrupted while draining Bedrock sessions", exception);
                    }
                });

        // Reverse close order: stop network transport, drain and persist gameplay
        // sessions, stop world ticking/final persistence, then stop diagnostics.
        lifecycle.register(diagnosticsRuntime);
        lifecycle.register(worldRuntime);
        lifecycle.register(gameplayRuntime);
        lifecycle.register(networkRuntime);

        try {
            networkRuntime.start();
            diagnosticsRuntime.start();
        } catch (Exception exception) {
            readiness.beginShutdown();
            try {
                lifecycle.close();
            } catch (Exception closeException) {
                exception.addSuppressed(closeException);
            } finally {
                readiness.markStopped();
            }
            throw exception;
        }
        readiness.markReady();
        startAiConsole(gameplayRuntime.assistant(), diagnosticsRuntime.safeRepairs(),
                bedrockSessions, transport, transferNodeId, transferLedger, configuredClusterTransport);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            readiness.beginShutdown();
            try {
                lifecycle.close();
            } catch (Exception exception) {
                System.err.println("NEXORA: one or more server resources failed to stop cleanly: "
                        + exception.getMessage());
                for (Throwable suppressed : exception.getSuppressed()) {
                    System.err.println("NEXORA shutdown detail: " + suppressed.getMessage());
                }
            } finally {
                readiness.markStopped();
            }
        }, "nexora-shutdown"));

        System.out.printf("NEXORA Cloudburst NetherNet signaling listening on TCP %s:%d%n", httpBindHost, port);
        System.out.printf("NEXORA legacy RakNet discovery listening on UDP %s:%d%n", bindHost, port);
        System.out.println("NetherNet identity is persisted at data/identity/nethernet-operator.pem.");
        System.out.println("Use HTTPS in production (TLS reverse proxy or configured signaling TLS).");
        if (!stunUrls.isEmpty()) {
            System.out.println("Configured STUN endpoints: " + String.join(", ", stunUrls));
        }
        System.out.printf("NetherNet ICE UDP port range: %d-%d%n", udpPortMin, udpPortMax);
        System.out.println("Bedrock NetworkSettings, authenticated Login and StartGame bootstrap are active.");
        System.out.println("World chunks use versioned, checksummed files under " + Path.of(dataDirectory, "worlds").toAbsolutePath());
        System.out.println("Player profiles use versioned, checksummed files under " + Path.of(dataDirectory, "players").toAbsolutePath());
        System.out.println("Player inventories use versioned, checksummed files under " + Path.of(dataDirectory, "inventories").toAbsolutePath());
        System.out.println("Player armor, offhand, cursor and magical equipment use checksummed files under " + Path.of(dataDirectory, "equipment").toAbsolutePath());
        System.out.println("Canonical player checkpoints: " + configuredPlayerStateStore.description() + "; legacy files remain login authority during migration.");
        System.out.println("Player-transfer ledger: " + configuredTransferLedger.description() + ".");
        System.out.println(configuredTransferLedger.shared()
                ? "Global online XUID leases: shared JDBC, TTL " + onlinePlayerLeaseMillis + " ms."
                : "Global online XUID leases: disabled (single-process ownership only).");
        System.out.println("Dropped world items use checksummed storage under " + Path.of(dataDirectory, "worlds", "NEXORA World", "items").toAbsolutePath());
        System.out.println("Block-container inventories use checksummed storage under " + Path.of(dataDirectory, "worlds", "NEXORA World", "containers").toAbsolutePath());
        System.out.println("Furnace inventories and burn/cook progress use checksummed storage under " + Path.of(dataDirectory, "worlds", "NEXORA World", "furnaces").toAbsolutePath());
        System.out.println("Server-authoritative movement, block drops, item pickups, fall damage, death/respawn, food use and persisted survival vitals are enabled.");
        System.out.println("Players can open the magical equipment interface with /equipment.");
        System.out.println("NexoraAI chat command /ai is available; provider configured: "
                + !System.getenv().getOrDefault("NEXORA_AI_API_KEY", "").isBlank()
                + "; operator allowlist configured: "
                + !System.getenv().getOrDefault("NEXORA_AI_ADMIN_XUIDS", "").isBlank());
        System.out.println("Weapon and armor durability wear is disabled by default; NEXORA_DURABILITY_ENABLED="
                + durabilitySettings.enabled() + " (wear processing is not implemented yet).");
        System.out.printf("World autosave interval: %d seconds%n", autosaveSeconds);
        System.out.println("Saved player positions and rotations are restored on re-login.");
        System.out.println("Next gameplay stages: item bounce/despawn, player/mob entity replication, containers/crafting, death inventory drops and real-client validation.");
    }

    private static long longEnvironmentValue(String name, long fallback, long minimum, long maximum) {
        String configured = System.getenv(name);
        if (configured == null || configured.isBlank()) return fallback;
        try {
            long value = Long.parseLong(configured.trim());
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be in range " + minimum + ".." + maximum, invalid);
        }
    }

    /** Reads private operator AI requests only from the server process console. */
    private static void startAiConsole(
            ru.nexora.ai.NexoraAiAssistant assistant,
            ru.nexora.ai.NexoraAiSafeRepairRegistry safeRepairs,
            BedrockSessionManager bedrockSessions,
            CloudburstNetherNetTransport transport,
            String localNodeId,
            DurableHandoffCoordinator transferLedger,
            java.util.Optional<ClusterTransportFactory.ConfiguredTransport> clusterTransport
    ) {
        Thread console = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = input.readLine()) != null) {
                    String command = line.trim();
                    if (command.equalsIgnoreCase("help")) {
                        System.out.println("Server console: players | network | transfer <xuid> <target-node> | transfer recover | ai <вопрос> | ai status | ai repair list");
                        continue;
                    }
                    if (command.equalsIgnoreCase("players")) {
                        for (BedrockSessionManager.AuthenticatedPlayer player : bedrockSessions.authenticatedPlayers()) {
                            System.out.println(player.xuid() + " | " + player.displayName()
                                    + " | entity=" + player.entityId());
                        }
                        continue;
                    }
                    if (command.equalsIgnoreCase("network")) {
                        var inbound = bedrockSessions.inboundStats();
                        System.out.println("NEXORA network: activeNetherNetPeers=" + transport.connectedPeerCount()
                                + ", authenticatedPlayers=" + bedrockSessions.authenticatedPlayerCount()
                                + ", transportStarted=" + transport.isStarted());
                        System.out.println("Bedrock ingress: frames=" + inbound.frames()
                                + ", bytes=" + inbound.bytes()
                                + ", messages=" + inbound.completedMessages()
                                + ", failed=" + inbound.failedFrames()
                                + ", channels=" + inbound.activeChannels());
                        System.out.println("Ingress processing: avg="
                                + String.format(java.util.Locale.ROOT, "%.2f us",
                                        inbound.averageProcessingNanos() / 1_000.0)
                                + ", max=" + String.format(java.util.Locale.ROOT, "%.2f us",
                                        inbound.maxProcessingNanos() / 1_000.0)
                                + " (pipeline framing/dispatch only)");
                        System.out.println("Cumulative transport bytes: sent=" + transport.bytesSent()
                                + ", received=" + transport.bytesReceived());
                        continue;
                    }
                    if (command.equalsIgnoreCase("transfer recover")) {
                        if (clusterTransport.isEmpty()) {
                            System.out.println("NEXORA transfer recovery: cluster transport is not configured.");
                            continue;
                        }
                        try {
                            BedrockSessionManager.TransferReconciliationReport report =
                                    bedrockSessions.reconcileFrozenPlayerTransfers(
                                            transferLedger, localNodeId,
                                            clusterTransport.orElseThrow().bedrockRoutes());
                            System.out.println("NEXORA transfer recovery: scanned " + report.scanned()
                                    + ", resumed " + report.resumed() + ", redirected " + report.redirected()
                                    + ", unresolved " + report.unresolved());
                        } catch (Exception failure) {
                            System.err.println("NEXORA transfer recovery failed closed: " + failure.getMessage());
                        }
                        continue;
                    }
                    if (command.regionMatches(true, 0, "transfer ", 0, 9)) {
                        String[] parts = command.split("\\s+");
                        if (parts.length != 3) {
                            System.out.println("Использование: transfer <xuid> <target-node>");
                            continue;
                        }
                        if (clusterTransport.isEmpty()) {
                            System.out.println("NEXORA transfer: cluster transport is not configured.");
                            continue;
                        }
                        BedrockSessionManager.AuthenticatedPlayer player = bedrockSessions.authenticatedPlayers()
                                .stream().filter(candidate -> candidate.xuid().equals(parts[1]))
                                .findFirst().orElse(null);
                        if (player == null) {
                            System.out.println("NEXORA transfer: authenticated player XUID was not found.");
                            continue;
                        }
                        java.net.InetSocketAddress route = clusterTransport.orElseThrow()
                                .bedrockRoutes().get(parts[2]);
                        if (route == null) {
                            System.out.println("NEXORA transfer: no Bedrock route configured for node " + parts[2]);
                            continue;
                        }
                        PlayerHandoff handoff = new PlayerHandoff(
                                UUID.randomUUID(), player.entityId(), localNodeId, parts[2], new byte[0]);
                        PlayerTransferSourceCoordinator transfer = new PlayerTransferSourceCoordinator(
                                localNodeId, transferLedger,
                                new BedrockPlayerTransferSource(bedrockSessions, localNodeId,
                                        ignored -> player.xuid()),
                                bedrockSessions, clusterTransport.orElseThrow().transport(),
                                Duration.ofSeconds(5), System::currentTimeMillis);
                        try {
                            var committed = transfer.transfer(
                                    handoff, player.xuid(), route.getHostString(), route.getPort());
                            System.out.println("NEXORA transfer: committed " + committed.transferId()
                                    + " to " + committed.targetNode() + " with fence " + committed.fencingToken());
                        } catch (Exception failure) {
                            System.err.println("NEXORA transfer failed: " + failure.getMessage()
                                    + ". If the outcome is uncertain, the source remains frozen for recovery.");
                        }
                        continue;
                    }
                    if (command.equalsIgnoreCase("ai") || command.equalsIgnoreCase("ai help")) {
                        System.out.println("NEXORA AI console: ai <вопрос> | ai status | ai repair list | ai repair history | ai repair run <id> confirm");
                        continue;
                    }
                    if (command.equalsIgnoreCase("ai status")) {
                        System.out.println(assistant.adminStatus());
                        continue;
                    }
                    if (command.equalsIgnoreCase("ai repair list")) {
                        for (ru.nexora.ai.NexoraAiSafeRepairRegistry.ActionDescriptor action : safeRepairs.actions()) {
                            System.out.println(action.id() + " — " + action.description()
                                    + " (auto-safe: " + action.autoSafe() + ")");
                        }
                        continue;
                    }
                    if (command.equalsIgnoreCase("ai repair history")) {
                        for (ru.nexora.ai.NexoraAiSafeRepairRegistry.Execution execution : safeRepairs.history()) {
                            System.out.println(execution.at() + " " + execution.actionId()
                                    + " " + execution.status() + " — " + execution.detail());
                        }
                        continue;
                    }
                    if (command.regionMatches(true, 0, "ai repair run ", 0, 14)) {
                        String[] parts = command.split("\\s+");
                        if (parts.length < 4) {
                            System.out.println("Использование: ai repair run <id> confirm");
                            continue;
                        }
                        boolean confirmed = parts.length >= 5 && parts[4].equalsIgnoreCase("confirm");
                        ru.nexora.ai.NexoraAiSafeRepairRegistry.Execution execution =
                                safeRepairs.execute(parts[3], "server-console", confirmed);
                        System.out.println("NEXORA AI repair: " + execution.status()
                                + " — " + execution.detail());
                        continue;
                    }
                    if (command.regionMatches(true, 0, "ai repair", 0, 9)) {
                        System.out.println("Использование: ai repair list | ai repair history | ai repair run <id> confirm");
                        continue;
                    }

                    if (!command.regionMatches(true, 0, "ai ", 0, 3)) continue;
                    String question = command.substring(3).trim();
                    if (question.isEmpty()) {
                        System.out.println("Использование: ai <вопрос>");
                        continue;
                    }
                    if (!assistant.isEnabled()) {
                        System.out.println("NEXORA AI: API не настроен (NEXORA_AI_API_KEY).");
                        continue;
                    }
                    ru.nexora.ai.NexoraAiAssistant.AskResult result = assistant.askConsole(
                            question, answer -> System.out.println("NEXORA AI: " + answer));
                    if (result == ru.nexora.ai.NexoraAiAssistant.AskResult.RATE_LIMITED) {
                        System.out.println("NEXORA AI: подожди несколько секунд.");
                    } else if (result == ru.nexora.ai.NexoraAiAssistant.AskResult.DISABLED) {
                        System.out.println("NEXORA AI: API не настроен.");
                    }
                }
            } catch (IOException exception) {
                System.err.println("NEXORA AI console input stopped: " + exception.getMessage());
            }
        }, "nexora-ai-console");
        console.setDaemon(true);
        console.start();
    }

}
