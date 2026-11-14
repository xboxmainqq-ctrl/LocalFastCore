package ru.nexora.runtime;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Command-line entry point for the standalone NEXORA development runtime.
 *
 * <p>Network ingress is disabled by default. The explicitly enabled
 * development network mode accepts unauthenticated offers and must only be
 * used on a trusted private network.</p>
 */
public final class NexoraMain {
    private static final String VERSION = "0.1.0-SNAPSHOT";
    private static final String DEFAULT_WORLD_ID = "world";
    private static final WorldHeight DEFAULT_WORLD_HEIGHT = new WorldHeight(-64, 320);

    private NexoraMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        LaunchOptions options;
        try {
            options = parseArguments(args);
        } catch (IllegalArgumentException invalidArguments) {
            System.err.println("NEXORA: " + invalidArguments.getMessage());
            printUsage(System.err::println);
            System.exit(2);
            return;
        }

        if (options.help()) {
            printUsage(System.out::println);
            return;
        }
        if (options.version()) {
            System.out.println("NEXORA Core " + VERSION);
            return;
        }

        RuntimeHandle runtime = createRuntime(options, System.out::println);
        runtime.server().start();
        System.out.println("NEXORA Core " + VERSION + " started.");
        System.out.println("World: " + DEFAULT_WORLD_ID
                + " | spawn chunk: 0,0 | tick rate: " + options.tickRate());
        if (options.developmentNetworkEnabled()) {
            System.out.println("NEXORA development signaling: http://"
                    + options.bindAddress() + ":" + options.signalingPort() + "/v1/join");
            System.out.println("WARNING: unauthenticated development mode is enabled; "
                    + "use only on a trusted private network.");
            System.out.println("WebRTC requires reachable UDP host candidates; "
                    + "STUN/TURN relay is not configured.");
        } else {
            System.out.println("Network ingress: disabled (safe offline development mode).");
        }
        System.out.println("Data directory: " + options.dataDirectory());
        System.out.println("Press Ctrl+C to stop and save the world.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                runtime.server().stop();
                System.out.println("NEXORA Core stopped; world saved.");
            } catch (RuntimeException failure) {
                System.err.println("NEXORA shutdown failed: " + failure.getMessage());
            }
        }, "nexora-shutdown"));

        new CountDownLatch(1).await();
    }

    static RuntimeHandle createRuntime(Path dataDirectory, long seed, Consumer<String> log) {
        return createRuntime(new LaunchOptions(dataDirectory.toAbsolutePath().normalize(),
                seed, false, false, false, "0.0.0.0", 8080, 20), log);
    }

    static RuntimeHandle createRuntime(LaunchOptions options, Consumer<String> log) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(log, "log");
        Path dataDirectory = options.dataDirectory();
        long seed = options.seed();

        WorldRegistry worlds = new WorldRegistry();
        WorldStorageModule storage = new WorldStorageModule(
                worlds,
                new FileWorldStorage(dataDirectory.resolve("worlds")),
                Map.of(DEFAULT_WORLD_ID, DEFAULT_WORLD_HEIGHT));

        FlatChunkGenerator generator = new FlatChunkGenerator(
                64,
                5,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:dirt"),
                new BlockState("minecraft:bedrock"));

        NexoraServer server = new NexoraServer();
        server.register(storage);
        server.register(new RuntimeModule() {
            @Override
            public String id() {
                return "spawn-world";
            }

            @Override
            public void start() {
                World world = worlds.get(DEFAULT_WORLD_ID)
                        .orElseThrow(() -> new IllegalStateException("Default world was not loaded"));
                ChunkPosition spawnChunk = new ChunkPosition(0, 0);
                if (!world.isChunkLoaded(spawnChunk)) {
                    if (!world.loadChunk(generator.generate(spawnChunk, world.height(), seed))) {
                        throw new IllegalStateException("Could not create the initial spawn chunk");
                    }
                    log.accept("Generated initial flat spawn chunk at 0,0.");
                } else {
                    log.accept("Loaded existing spawn chunk at 0,0.");
                }
            }

            @Override
            public void stop() {
                // WorldStorageModule persists the world after runtime services stop.
            }
        });

        TickScheduler scheduler = new TickScheduler();
        EntityRegistry entities = new EntityRegistry();
        int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        server.register(new RuntimeModule() {
            private WorldChunkPipeline pipeline;

            @Override
            public String id() {
                return "chunk-generation";
            }

            @Override
            public void start() throws Exception {
                World world = worlds.get(DEFAULT_WORLD_ID)
                        .orElseThrow(() -> new IllegalStateException("Default world was not loaded"));
                AsyncChunkGenerationService generation = new AsyncChunkGenerationService(
                        generator, world.height(), seed, workers, 256, 256);
                pipeline = new WorldChunkPipeline(
                        world, scheduler, generation, 4,
                        failure -> log.accept("Chunk generation error: " + failure.getMessage()));
                pipeline.start();
            }

            @Override
            public void stop() throws Exception {
                if (pipeline != null) {
                    pipeline.stop();
                    pipeline = null;
                }
            }
        });

        GameLoop gameLoop = new GameLoop(
                options.tickRate(), 3, scheduler,
                failure -> log.accept("Game tick error: " + failure.getMessage()));
        server.register(gameLoop);
        server.register(new RuntimeModule() {
            private EntitySimulationModule simulation;

            @Override
            public String id() {
                return "entity-simulation";
            }

            @Override
            public void start() {
                World world = worlds.get(DEFAULT_WORLD_ID)
                        .orElseThrow(() -> new IllegalStateException("Default world was not loaded"));
                simulation = new EntitySimulationModule(scheduler, new EntityTickEngine(entities, world));
                simulation.start();
            }

            @Override
            public void stop() {
                if (simulation != null) {
                    simulation.stop();
                    simulation = null;
                }
            }
        });

        if (options.developmentNetworkEnabled()) {
            WebRtcJavaNetherNetPeerHost peerHost = new WebRtcJavaNetherNetPeerHost();
            KeyPair signingKey = createDevelopmentSigningKey();
            NetherNetServerInfo serverInfo = new NetherNetServerInfo(
                    "NEXORA", 0, VERSION, DEFAULT_WORLD_ID, 0,
                    ServerSettings.DEFAULT_MAX_PLAYERS, 0);
            NetherNetSignalingServer signaling = new NetherNetSignalingServer(
                    new InetSocketAddress(options.bindAddress(), options.signalingPort()),
                    serverInfo,
                    new NetherNetDevelopmentOfferValidator(true),
                    peerHost,
                    "nexora.local",
                    signingKey);
            NexoraPrivateProtocolHandler protocolHandler = new NexoraPrivateProtocolHandler(
                    UUID.randomUUID(),
                    WebRtcJavaNetherNetPeerHost.MAX_MESSAGE_BYTES,
                    () -> {
                        World world = worlds.get(DEFAULT_WORLD_ID)
                                .orElseThrow(() -> new IllegalStateException("Default world is not loaded"));
                        return NexoraWorldInfo.from(world, 0, 65, 0, options.tickRate());
                    },
                    () -> worlds.get(DEFAULT_WORLD_ID)
                            .orElseThrow(() -> new IllegalStateException("Default world is not loaded")),
                    position -> {
                        World world = worlds.get(DEFAULT_WORLD_ID)
                                .orElseThrow(() -> new IllegalStateException("Default world is not loaded"));
                        if (world.isChunkLoaded(position)) return true;
                        return world.loadChunk(generator.generate(position, world.height(), seed));
                    },
                    connection -> log.accept("NEXORA client completed protocol handshake: "
                            + connection.id()));
            server.register(new NetherNetPrivateProtocolModule(
                    peerHost, peerHost, signaling, protocolHandler));
        }

        return new RuntimeHandle(server, worlds, entities, gameLoop, options.tickRate());
    }

    private static KeyPair createDevelopmentSigningKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp384r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Could not create temporary NEXORA development identity", failure);
        }
    }

    static LaunchOptions parseArguments(String[] args) {
        Objects.requireNonNull(args, "args");
        Path dataDirectory = Path.of("nexora-data");
        long seed = 0L;
        boolean help = false;
        boolean version = false;
        boolean developmentNetworkEnabled = false;
        String bindAddress = "0.0.0.0";
        int signalingPort = 8080;
        int tickRate = 20;

        for (int index = 0; index < args.length; index++) {
            String argument = Objects.requireNonNull(args[index], "argument");
            switch (argument) {
                case "--help", "-h" -> help = true;
                case "--version", "-v" -> version = true;
                case "--enable-development-network" -> developmentNetworkEnabled = true;
                case "--bind-address" -> {
                    if (++index >= args.length || args[index].isBlank()) {
                        throw new IllegalArgumentException("--bind-address requires a host or IP");
                    }
                    bindAddress = args[index].trim();
                }
                case "--signaling-port" -> {
                    if (++index >= args.length) {
                        throw new IllegalArgumentException("--signaling-port requires a port");
                    }
                    try {
                        signalingPort = Integer.parseInt(args[index]);
                    } catch (NumberFormatException invalidPort) {
                        throw new IllegalArgumentException("--signaling-port must be an integer", invalidPort);
                    }
                    if (signalingPort < 1 || signalingPort > 65535) {
                        throw new IllegalArgumentException("--signaling-port must be between 1 and 65535");
                    }
                }
                case "--tick-rate" -> {
                    if (++index >= args.length) {
                        throw new IllegalArgumentException("--tick-rate requires an integer");
                    }
                    try {
                        tickRate = Integer.parseInt(args[index]);
                    } catch (NumberFormatException invalidTickRate) {
                        throw new IllegalArgumentException("--tick-rate must be an integer", invalidTickRate);
                    }
                    if (tickRate < 1 || tickRate > 120) {
                        throw new IllegalArgumentException("--tick-rate must be between 1 and 120");
                    }
                }
                case "--data-dir" -> {
                    if (++index >= args.length || args[index].isBlank()) {
                        throw new IllegalArgumentException("--data-dir requires a non-empty path");
                    }
                    dataDirectory = Path.of(args[index]);
                }
                case "--seed" -> {
                    if (++index >= args.length) {
                        throw new IllegalArgumentException("--seed requires a 64-bit integer");
                    }
                    try {
                        seed = Long.parseLong(args[index]);
                    } catch (NumberFormatException invalidSeed) {
                        throw new IllegalArgumentException("--seed must be a 64-bit integer", invalidSeed);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + argument);
            }
        }
        if (!developmentNetworkEnabled
                && (!"0.0.0.0".equals(bindAddress) || signalingPort != 8080)) {
            throw new IllegalArgumentException(
                    "--bind-address and --signaling-port require --enable-development-network");
        }
        return new LaunchOptions(dataDirectory.toAbsolutePath().normalize(), seed, help, version,
                developmentNetworkEnabled, bindAddress, signalingPort, tickRate);
    }

    private static void printUsage(Consumer<String> output) {
        output.accept("Usage: java -jar nexora-runtime.jar [options]");
        output.accept("  --data-dir <path>              Directory for world data (default: ./nexora-data)");
        output.accept("  --seed <number>                World generation seed (default: 0)");
        output.accept("  --tick-rate <number>           Game tick rate (default: 20)");
        output.accept("  --enable-development-network   Enable unauthenticated private-network testing");
        output.accept("  --bind-address <host>          Signaling bind host (default: 0.0.0.0)");
        output.accept("  --signaling-port <port>        HTTP signaling port (default: 8080)");
        output.accept("  --version                      Print the NEXORA version");
        output.accept("  --help                         Show this help");
    }

    record LaunchOptions(Path dataDirectory, long seed, boolean help, boolean version,
                         boolean developmentNetworkEnabled, String bindAddress,
                         int signalingPort, int tickRate) {
        LaunchOptions {
            Objects.requireNonNull(dataDirectory, "dataDirectory");
            Objects.requireNonNull(bindAddress, "bindAddress");
            if (bindAddress.isBlank() || signalingPort < 1 || signalingPort > 65535
                    || tickRate < 1 || tickRate > 120) {
                throw new IllegalArgumentException("Invalid NEXORA launch configuration");
            }
        }
    }

    record RuntimeHandle(NexoraServer server, WorldRegistry worlds, EntityRegistry entities, GameLoop gameLoop, int tickRate) {
        RuntimeHandle {
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(worlds, "worlds");
            Objects.requireNonNull(entities, "entities");
            Objects.requireNonNull(gameLoop, "gameLoop");
            if (tickRate < 1 || tickRate > 120) {
                throw new IllegalArgumentException("tickRate must be between 1 and 120");
            }
        }

        NexoraWorldInfo worldInfo() {
            World world = worlds.get(DEFAULT_WORLD_ID)
                    .orElseThrow(() -> new IllegalStateException("Default world is not loaded"));
            return NexoraWorldInfo.from(world, 0, 65, 0, tickRate);
        }

        NexoraPrivateProtocolHandler privateProtocolHandler(
                UUID serverId, Consumer<TransportConnection> readyListener) {
            return new NexoraPrivateProtocolHandler(serverId,
                    WebRtcJavaNetherNetPeerHost.MAX_MESSAGE_BYTES,
                    this::worldInfo, readyListener);
        }
    }
}
