package ru.nexora;

import ru.nexora.network.NetworkRuntimeModule;
import ru.nexora.network.PlayerTransferLedgerFactory;
import ru.nexora.network.DurableHandoffCoordinator;
import ru.nexora.gameplay.GameplayRuntimeModule;
import ru.nexora.network.nethernet.DefaultNetherNetIdentityService;
import ru.nexora.network.nethernet.NetherNetServerMetadata;
import ru.nexora.network.bedrock.BedrockSessionManager;
import ru.nexora.network.nethernet.NetherNetSignalingServer;
import ru.nexora.network.nethernet.WebRtcSdpNegotiator;
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
import ru.nexora.persistence.FilePlayerStateStore;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * NEXORA Core bootstrap. Modern Bedrock transport is NetherNet over WebRTC.
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
        DefaultNetherNetIdentityService identityService =
                new DefaultNetherNetIdentityService(Path.of(dataDirectory, "identity"));
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
        PlayerStateCheckpointService playerStateCheckpoints = new PlayerStateCheckpointService(
                new FilePlayerStateStore(Path.of(dataDirectory, "player-state")),
                new PlayerStateBundleCodec());
        PlayerTransferLedgerFactory.ConfiguredLedger configuredTransferLedger =
                PlayerTransferLedgerFactory.open(Path.of(dataDirectory), System.getenv());
        String configuredNodeId = System.getenv("NEXORA_NODE_ID");
        if (configuredTransferLedger.shared() && (configuredNodeId == null || configuredNodeId.isBlank())) {
            throw new IllegalStateException(
                    "NEXORA_NODE_ID must be explicitly configured when using a shared JDBC transfer ledger");
        }
        String transferNodeId = configuredNodeId == null || configuredNodeId.isBlank()
                ? "standalone" : configuredNodeId.trim();
        DurableHandoffCoordinator transferLedger = configuredTransferLedger.coordinator();
        AtomicReference<WebRtcSdpNegotiator> negotiatorReference = new AtomicReference<>();
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
                    WebRtcSdpNegotiator active = negotiatorReference.get();
                    if (active == null) throw new IllegalStateException("WebRTC transport is not ready");
                    active.sendFrame(networkId, channelLabel, frame);
                },
                event -> System.out.printf("NEXORA: Bedrock packet 0x%02X from %s (%d bytes)%n",
                        event.packet().packetId(), event.networkId(), event.packet().payload().length));
        ru.nexora.network.PlayerTransferRecoveryService.RecoveryReport transferRecovery =
                bedrockSessions.recoverPendingTransferRestores(transferLedger, transferNodeId, 10_000);
        System.out.printf("NEXORA: transfer recovery for node %s scanned %d, restored %d, already pending %d, expired %d, superseded %d%n",
                transferNodeId, transferRecovery.scanned(), transferRecovery.registered(),
                transferRecovery.alreadyRegistered(), transferRecovery.expiredClaims(),
                transferRecovery.supersededRecords());
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
        WebRtcSdpNegotiator negotiator = new WebRtcSdpNegotiator(
                identityService, bedrockSessions, maxPlayers, stunUrls, udpPortMin, udpPortMax);
        negotiatorReference.set(negotiator);
        NetherNetSignalingServer signaling = new NetherNetSignalingServer(
                signalingAddress,
                () -> new NetherNetServerMetadata(
                        motd,
                        2225,
                        "1.26.62",
                        world.name(),
                        (int) Math.min(bedrockSessions.authenticatedPlayerCount(), maxPlayers),
                        maxPlayers,
                        0
                ),
                negotiator,
                readiness::isAcceptingConnections);

        long autosaveSeconds = config.autosaveSeconds();
        WorldRuntimeModule worldRuntime = new WorldRuntimeModule(
                world,
                Duration.ofSeconds(autosaveSeconds),
                exception -> {
                    aiDiagnostics.recordFailure("world-autosave", exception);
                    System.err.println("NEXORA: periodic world/player autosave failed: " + exception.getMessage());
                },
                bedrockSessions::savePlayers,
                elapsed -> aiDiagnostics.recordSlowOperation(
                        "world-autosave", elapsed, Duration.ofSeconds(1).toNanos()),
                exception -> {
                    aiDiagnostics.recordFailure("furnace-tick", exception);
                    System.err.println("NEXORA: furnace tick failed: " + exception.getMessage());
                },
                elapsed -> aiDiagnostics.recordSlowOperation("furnace-tick", elapsed, 50_000_000L));

        NetworkRuntimeModule networkRuntime = new NetworkRuntimeModule(
                NetworkRuntimeModule.service(discovery::start, discovery),
                NetworkRuntimeModule.service(signaling::start, signaling),
                negotiator,
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
        startAiConsole(gameplayRuntime.assistant(), diagnosticsRuntime.safeRepairs());

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

        System.out.printf("NEXORA NetherNet signaling listening on TCP %s:%d%n", httpBindHost, port);
        System.out.printf("NEXORA legacy RakNet discovery listening on UDP %s:%d%n", bindHost, port);
        System.out.println("WebRTC sessions use the persistent operator identity in data/identity/.");
        System.out.println("Plain HTTP signaling is enabled; use HTTPS in production.");
        if (!stunUrls.isEmpty()) {
            System.out.println("Configured STUN endpoints: " + String.join(", ", stunUrls));
        }
        System.out.printf("WebRTC ICE UDP port range: %d-%d%n", udpPortMin, udpPortMax);
        System.out.println("Bedrock NetworkSettings, authenticated Login and StartGame bootstrap are active.");
        System.out.println("World chunks use versioned, checksummed files under " + Path.of(dataDirectory, "worlds").toAbsolutePath());
        System.out.println("Player profiles use versioned, checksummed files under " + Path.of(dataDirectory, "players").toAbsolutePath());
        System.out.println("Player inventories use versioned, checksummed files under " + Path.of(dataDirectory, "inventories").toAbsolutePath());
        System.out.println("Player armor, offhand, cursor and magical equipment use checksummed files under " + Path.of(dataDirectory, "equipment").toAbsolutePath());
        System.out.println("Canonical player shadow checkpoints use versioned snapshots under " + Path.of(dataDirectory, "player-state").toAbsolutePath() + "; legacy files remain login authority during migration.");
        System.out.println("Player-transfer ledger: " + configuredTransferLedger.description() + ".");
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

    /** Reads private operator AI requests only from the server process console. */
    private static void startAiConsole(
            ru.nexora.ai.NexoraAiAssistant assistant,
            ru.nexora.ai.NexoraAiSafeRepairRegistry safeRepairs
    ) {
        Thread console = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = input.readLine()) != null) {
                    String command = line.trim();
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
