package ru.nexora;

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
import ru.nexora.world.WorldAutosave;
import ru.nexora.world.PlayerDataStorage;
import ru.nexora.inventory.PlayerInventoryStorage;

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
        String bindHost = args.length > 0 ? args[0] : "0.0.0.0";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 19132;
        String motd = args.length > 2 ? args[2] : "NEXORA Core - Development";
        int maxPlayers = args.length > 3 ? Integer.parseInt(args[3]) : 20;

        InetAddress bindAddress = InetAddress.getByName(bindHost);
        InetSocketAddress socketAddress = new InetSocketAddress(bindAddress, port);
        String httpBindHost = System.getenv().getOrDefault("NEXORA_HTTP_BIND", bindHost);
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
        RakNetDiscoveryServer discovery = new RakNetDiscoveryServer(
                socketAddress,
                legacyStatus,
                new java.security.SecureRandom().nextLong()
        );

        String dataDirectory = System.getenv().getOrDefault("NEXORA_DATA_DIR", "data");
        DefaultNetherNetIdentityService identityService =
                new DefaultNetherNetIdentityService(Path.of(dataDirectory, "identity"));
        WorldStorage worldStorage = new WorldStorage(Path.of(dataDirectory, "worlds"), "NEXORA World");
        World world = new World(
                "NEXORA World", 0L, new FlatWorldGenerator(), 0, 65, 0, worldStorage);
        PlayerDataStorage playerStorage = new PlayerDataStorage(Path.of(dataDirectory, "players"));
        PlayerInventoryStorage inventoryStorage = new PlayerInventoryStorage(Path.of(dataDirectory, "inventories"));
        AtomicReference<WebRtcSdpNegotiator> negotiatorReference = new AtomicReference<>();
        BedrockSessionManager bedrockSessions = new BedrockSessionManager(
                2225,
                "1.26.62",
                BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world,
                playerStorage,
                inventoryStorage,
                (networkId, channelLabel, frame) -> {
                    WebRtcSdpNegotiator active = negotiatorReference.get();
                    if (active == null) throw new IllegalStateException("WebRTC transport is not ready");
                    active.sendFrame(networkId, channelLabel, frame);
                },
                event -> System.out.printf("NEXORA: Bedrock packet 0x%02X from %s (%d bytes)%n",
                        event.packet().packetId(), event.networkId(), event.packet().payload().length));
        bedrockSessions.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        String stunEnvironment = System.getenv("NEXORA_STUN_URLS");
        List<String> stunUrls = stunEnvironment == null || stunEnvironment.isBlank()
                ? List.of()
                : Arrays.stream(stunEnvironment.split(";"))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .toList();
        int udpPortMin = Integer.parseInt(System.getenv().getOrDefault("NEXORA_UDP_PORT_MIN", "30000"));
        int udpPortMax = Integer.parseInt(System.getenv().getOrDefault("NEXORA_UDP_PORT_MAX", "30199"));
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
                negotiator);

        long autosaveSeconds = Long.parseLong(System.getenv().getOrDefault("NEXORA_AUTOSAVE_SECONDS", "300"));
        WorldAutosave autosave = new WorldAutosave(world, Duration.ofSeconds(autosaveSeconds),
                exception -> System.err.println("NEXORA: periodic world/player autosave failed: " + exception.getMessage()),
                bedrockSessions::savePlayers);
        try {
            discovery.start();
            signaling.start();
        } catch (Exception exception) {
            signaling.close();
            negotiator.close();
            try {
                autosave.close();
            } catch (java.io.IOException saveException) {
                exception.addSuppressed(saveException);
            }
            bedrockSessions.close();
            discovery.close();
            throw exception;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            signaling.close();
            negotiator.close();
            try {
                autosave.close();
            } catch (java.io.IOException exception) {
                System.err.println("NEXORA: unable to flush world/player autosave: " + exception.getMessage());
            }
            bedrockSessions.close();
            discovery.close();
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
        System.out.printf("World autosave interval: %d seconds%n", autosaveSeconds);
        System.out.println("Saved player positions and rotations are restored on re-login.");
        System.out.println("Next gameplay stages: block placement, movement physics and entity replication.");
    }
}
