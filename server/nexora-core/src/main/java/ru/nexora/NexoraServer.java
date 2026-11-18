package ru.nexora;

import ru.nexora.network.NetworkRuntimeModule;
import ru.nexora.network.nethernet.DefaultNetherNetIdentityService;
import ru.nexora.network.nethernet.NetherNetServerMetadata;
import ru.nexora.network.bedrock.BedrockSessionManager;
import ru.nexora.network.bedrock.BedrockVanillaItemCatalog;
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
import ru.nexora.inventory.PlayerInventoryStorage;
import ru.nexora.inventory.PlayerEquipmentStorage;
import ru.nexora.inventory.VanillaArmorSlotCatalog;
import ru.nexora.inventory.NexoraMagicEquipmentCatalog;
import ru.nexora.inventory.DurabilitySettings;
import ru.nexora.ai.NexoraAiDiagnostics;
import ru.nexora.ai.NexoraAiMonitor;
import ru.nexora.ai.NexoraAiAssistant;
import ru.nexora.lifecycle.ServerLifecycle;
import ru.nexora.lifecycle.ServerReadiness;
import ru.nexora.config.NexoraServerConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
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
        NexoraAiDiagnostics aiDiagnostics = new NexoraAiDiagnostics();
        NexoraAiMonitor aiMonitor = new NexoraAiMonitor(aiDiagnostics, snapshot -> {
            if (snapshot.health() == NexoraAiDiagnostics.Health.WARNING) {
                String heapUsage = snapshot.heapUsageRatio() < 0.0 ? "unknown"
                        : String.format(java.util.Locale.ROOT, "%.1f%%", snapshot.heapUsageRatio() * 100.0);
                System.err.printf("NEXORA AI: runtime warning: %s (heap %s, threads %d, failures %d, slow operations %d)%n",
                        snapshot.reason(), heapUsage,
                        snapshot.liveThreadCount(), snapshot.recordedFailures(), snapshot.slowOperationCount());
            }
        });
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
        AtomicReference<WebRtcSdpNegotiator> negotiatorReference = new AtomicReference<>();
        BedrockSessionManager bedrockSessions = new BedrockSessionManager(
                2225,
                "1.26.62",
                BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world,
                playerStorage,
                inventoryStorage,
                equipmentStorage,
                (networkId, channelLabel, frame) -> {
                    WebRtcSdpNegotiator active = negotiatorReference.get();
                    if (active == null) throw new IllegalStateException("WebRTC transport is not ready");
                    active.sendFrame(networkId, channelLabel, frame);
                },
                event -> System.out.printf("NEXORA: Bedrock packet 0x%02X from %s (%d bytes)%n",
                        event.packet().packetId(), event.networkId(), event.packet().payload().length));
        bedrockSessions.setAiDiagnostics(aiDiagnostics);
        NexoraAiAssistant aiAssistant = NexoraAiAssistant.fromEnvironment(System.getenv(), aiDiagnostics);
        aiAssistant.setAdminRuntimeSnapshotProvider(() -> bedrockSessions.adminRuntimeSummary()
                + "\n" + bedrockSessions.guardSummary());
        bedrockSessions.setAiAssistant(aiAssistant);
        bedrockSessions.enableServerAuthoritativeMovement();
        bedrockSessions.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        bedrockSessions.setArmorSlotRegistry(VanillaArmorSlotCatalog.createRegistry());
        bedrockSessions.setEquipmentSlotRegistry(NexoraMagicEquipmentCatalog.createRegistry());
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
                bedrockSessions);

        // Reverse close order: network admission/discovery, WebRTC, Bedrock
        // sessions and player persistence; then world ticking/final save; diagnostics last.
        lifecycle.register(aiMonitor);
        lifecycle.register(worldRuntime);
        lifecycle.register(networkRuntime);

        try {
            networkRuntime.start();
            aiMonitor.start();
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
}
