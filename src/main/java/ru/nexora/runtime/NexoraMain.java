package ru.nexora.runtime;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
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
        System.out.println("World: " + runtime.worldId()
                + " | spawn chunk: 0,0 | tick rate: " + runtime.tickRate());
        if (options.developmentNetworkEnabled()) {
            System.out.println("NEXORA development signaling: http://"
                    + options.bindAddress() + ":" + options.signalingPort() + "/v1/join");
            System.out.println("WARNING: unauthenticated development mode is enabled; "
                    + "use only on a trusted private network.");
            System.out.println("WebRTC requires reachable UDP host candidates; "
                    + "STUN/TURN relay is not configured.");
        }
        if (options.bedrockListenerSettings().enabled()) {
            BedrockListenerSettings bedrock = options.bedrockListenerSettings();
            System.out.println("Bedrock 1.26.60 NetherNet: TCP "
                    + bedrock.signalingBindAddress() + " + UDP " + bedrock.mediaBindAddress());
        }
        if (!options.developmentNetworkEnabled() && !options.bedrockListenerSettings().enabled()) {
            System.out.println("Network ingress: disabled (safe offline development mode).");
        }
        System.out.println("Data directory: " + options.dataDirectory());
        System.out.println("Press Ctrl+C to stop and save the world.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                try {
                    runtime.server().stop();
                } finally {
                    runtime.saveInventories();
                }
                System.out.println("NEXORA Core stopped; world and player inventories saved.");
            } catch (Exception failure) {
                System.err.println("NEXORA shutdown failed: " + failure.getMessage());
            }
        }, "nexora-shutdown"));

        new CountDownLatch(1).await();
    }

    static RuntimeHandle createRuntime(Path dataDirectory, long seed, Consumer<String> log) {
        return createRuntime(new LaunchOptions(dataDirectory.toAbsolutePath().normalize(),
                seed, false, false, false, "0.0.0.0", 8080, 0,
                BedrockListenerSettings.disabled()), log);
    }

    static RuntimeHandle createRuntime(LaunchOptions options, Consumer<String> log) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(log, "log");
        Path dataDirectory = options.dataDirectory();
        ServerConfiguration configuration;
        try {
            configuration = ServerConfiguration.loadOrCreate(dataDirectory.resolve("server.properties"));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not load server.properties", failure);
        }
        String worldId = configuration.worldId();
        long seed = options.seed() == Long.MIN_VALUE ? configuration.seed() : options.seed();
        int tickRate = options.tickRate() == 0 ? configuration.tickRate() : options.tickRate();
        WorldHeight worldHeight = configuration.worldHeight();

        WorldRegistry worlds = new WorldRegistry();
        WorldStorageModule storage = new WorldStorageModule(
                worlds,
                new FileWorldStorage(dataDirectory.resolve("worlds")),
                Map.of(worldId, worldHeight));

        WorldGeneratorRegistry generatorRegistry = WorldGeneratorRegistry.builtIn();
        WorldGeneratorExtensionLoader extensionLoader;
        try {
            extensionLoader = WorldGeneratorExtensionLoader.load(
                    dataDirectory.resolve("extensions").resolve("generators"), generatorRegistry);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not load world generator extensions", failure);
        }

        final ChunkGenerator generator;
        try {
            generator = generatorRegistry.create(configuration.generatorId());
        } catch (RuntimeException failure) {
            try {
                extensionLoader.close();
            } catch (java.io.IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }

        NexoraServer server = new NexoraServer();
        server.register(new RuntimeModule() {
            @Override
            public String id() {
                return "world-generator-extensions";
            }

            @Override
            public void start() {
                // Extension class loaders are opened before runtime startup.
            }

            @Override
            public void stop() throws java.io.IOException {
                extensionLoader.close();
            }
        });
        server.register(storage);
        server.register(new RuntimeModule() {
            @Override
            public String id() {
                return "spawn-world";
            }

            @Override
            public void start() {
                World world = worlds.get(worldId)
                        .orElseThrow(() -> new IllegalStateException("Default world was not loaded"));
                ChunkPosition spawnChunk = new ChunkPosition(0, 0);
                if (!world.isChunkLoaded(spawnChunk)) {
                    if (!world.loadChunk(generator.generate(spawnChunk, world.height(), seed))) {
                        throw new IllegalStateException("Could not create the initial spawn chunk");
                    }
                    log.accept("Generated initial procedural spawn chunk at 0,0.");
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
        Map<UUID, PlayerInventory> inventories = new ConcurrentHashMap<>();
        FilePlayerInventoryStorage playerInventoryStorage =
                new FilePlayerInventoryStorage(dataDirectory.resolve("players"));
        int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        AtomicReference<WorldChunkPipeline> activeChunkPipeline = new AtomicReference<>();
        AtomicReference<PlayerChunkStreamingManager> activeChunkStreaming = new AtomicReference<>();
        server.register(new RuntimeModule() {
            private WorldChunkPipeline pipeline;

            @Override
            public String id() {
                return "chunk-generation";
            }

            @Override
            public void start() throws Exception {
                World world = worlds.get(worldId)
                        .orElseThrow(() -> new IllegalStateException("Default world was not loaded"));
                AsyncChunkGenerationService generation = new AsyncChunkGenerationService(
                        generator, world.height(), seed, workers, 256, 256);
                pipeline = new WorldChunkPipeline(
                        world, scheduler, generation, 4,
                        failure -> log.accept("Chunk generation error: " + failure.getMessage()));
                pipeline.start();
                activeChunkPipeline.set(pipeline);
            }

            @Override
            public void stop() throws Exception {
                if (pipeline != null) {
                    pipeline.stop();
                    activeChunkPipeline.compareAndSet(pipeline, null);
                    pipeline = null;
                }
            }
        });

        server.register(new RuntimeModule() {
            private PlayerChunkStreamingManager streaming;
            private TickScheduler.TaskHandle streamingTask;

            @Override
            public String id() {
                return "player-chunk-streaming";
            }

            @Override
            public void start() {
                World world = worlds.get(worldId)
                        .orElseThrow(() -> new IllegalStateException("Default world was not loaded"));
                WorldChunkPipeline pipeline = activeChunkPipeline.get();
                if (pipeline == null) {
                    throw new IllegalStateException("Chunk generation pipeline is not active");
                }
                streaming = new PlayerChunkStreamingManager(world, pipeline, 1, 4);
                streamingTask = scheduler.scheduleRepeating(1, 1, streaming::tick);
                activeChunkStreaming.set(streaming);
            }

            @Override
            public void stop() {
                if (streamingTask != null) {
                    streamingTask.cancel();
                    streamingTask = null;
                }
                if (streaming != null) {
                    activeChunkStreaming.compareAndSet(streaming, null);
                    streaming = null;
                }
            }
        });

        GameLoop gameLoop = new GameLoop(
                tickRate, 3, scheduler,
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
                World world = worlds.get(worldId)
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

        if (options.bedrockListenerSettings().enabled()) {
            BedrockListenerSettings configured = options.bedrockListenerSettings();
            Path identityFile = configured.identityFile().isAbsolute()
                    ? configured.identityFile()
                    : dataDirectory.resolve(configured.identityFile()).normalize();
            BedrockListenerSettings effectiveSettings = new BedrockListenerSettings(
                    true, configured.bindAddress(), configured.signalingPort(),
                    configured.separateIcePort(), identityFile, true);
            server.register(new RuntimeModule() {
                private CloudburstNetherNetBedrockModule ingress;
                private java.util.concurrent.ExecutorService authenticationExecutor;

                @Override
                public String id() {
                    return "bedrock-nethernet";
                }

                @Override
                public void start() throws Exception {
                    World world = worlds.get(worldId)
                            .orElseThrow(() -> new IllegalStateException("Default world is not loaded"));
                    List<ChunkPosition> initialChunks = new ArrayList<>(9);
                    for (int chunkX = -1; chunkX <= 1; chunkX++) {
                        for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
                            ChunkPosition position = new ChunkPosition(chunkX, chunkZ);
                            if (!world.isChunkLoaded(position)
                                    && !world.loadChunk(generator.generate(position, world.height(), seed))) {
                                throw new IllegalStateException("Could not generate Bedrock spawn chunk " + position);
                            }
                            initialChunks.add(position);
                        }
                    }

                    BedrockNetworkSettings bedrockNetwork = new BedrockNetworkSettings(
                            1, BedrockNetworkSettings.CompressionAlgorithm.ZLIB, false, 0, 0.0f);
                    BedrockLoginIdentityVerifier loginVerifier = new BedrockLoginIdentityVerifier(
                            new NetherNetGameServerTokenVerifier(new NetherNetJwksKeyCache()));
                    authenticationExecutor = new java.util.concurrent.ThreadPoolExecutor(
                            2, 2, 30L, java.util.concurrent.TimeUnit.SECONDS,
                            new java.util.concurrent.ArrayBlockingQueue<>(64),
                            runnable -> {
                                Thread thread = new Thread(runnable, "nexora-bedrock-auth");
                                thread.setDaemon(true);
                                return thread;
                            },
                            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

                    BedrockWorldSessionPipeline sessions = new BedrockWorldSessionPipeline(
                            bedrockNetwork, world, 0, -1, initialChunks,
                            BedrockRuntimeBlockRegistryProfile.hashing(),
                            (connection, player) -> createBedrockStartGameSettings(
                                    world, player, seed, worldId),
                            (connection, login, request) -> CompletableFuture.supplyAsync(() -> {
                                try {
                                    NetherNetGameServerTokenVerifier.VerifiedClientIdentity identity =
                                            loginVerifier.verify(request);
                                    String clientData = BedrockClientDataJwtVerifier.verifyAndReadPayload(
                                            login.clientDataJwt(), identity.clientPublicKey());
                                    return new BedrockAuthenticatedPlayer(identity.xuid(),
                                            identity.displayName(), identity.clientPublicKey(), clientData);
                                } catch (Exception failure) {
                                    throw new java.util.concurrent.CompletionException(failure);
                                }
                            }, authenticationExecutor),
                            (connection, message) -> log.accept(
                                    "Bedrock gameplay packet not yet handled for " + connection.id()
                                            + " (" + message.sizeBytes() + " bytes)."));
                    ingress = new CloudburstNetherNetBedrockModule(
                            effectiveSettings, sessions, worldId, log);
                    ingress.start();
                    log.accept("Prepared " + initialChunks.size()
                            + " Bedrock spawn chunks for authenticated NetherNet sessions.");
                }

                @Override
                public void stop() {
                    if (ingress != null) {
                        ingress.stop();
                        ingress = null;
                    }
                    if (authenticationExecutor != null) {
                        authenticationExecutor.shutdownNow();
                        authenticationExecutor = null;
                    }
                }
            });
        }

        if (options.developmentNetworkEnabled()) {
            WebRtcJavaNetherNetPeerHost peerHost = new WebRtcJavaNetherNetPeerHost();
            KeyPair signingKey = createDevelopmentSigningKey();
            NetherNetServerInfo serverInfo = new NetherNetServerInfo(
                    "NEXORA", 0, VERSION, worldId, 0,
                    ServerSettings.DEFAULT_MAX_PLAYERS, 0);
            NetherNetSignalingServer signaling = new NetherNetSignalingServer(
                    new InetSocketAddress(options.bindAddress(), options.signalingPort()),
                    serverInfo,
                    new NetherNetDevelopmentOfferValidator(true),
                    peerHost,
                    "nexora.local",
                    signingKey);
            final List<BedrockResourcePackTransferCodec.Pack> serverResourcePacks;
            try {
                serverResourcePacks = new BedrockResourcePackArchiveLoader().load(
                        dataDirectory.resolve("resource-packs"), "1.0.0");
            } catch (java.io.IOException invalidResourcePacks) {
                throw new IllegalStateException(
                        "Could not load server resource packs from "
                                + dataDirectory.resolve("resource-packs"),
                        invalidResourcePacks);
            }
            log.accept("Loaded " + serverResourcePacks.size()
                    + " server resource pack(s) from " + dataDirectory.resolve("resource-packs"));
            NexoraPrivateProtocolHandler protocolHandler = new NexoraPrivateProtocolHandler(
                    UUID.randomUUID(),
                    WebRtcJavaNetherNetPeerHost.MAX_MESSAGE_BYTES,
                    () -> {
                        World world = worlds.get(worldId)
                                .orElseThrow(() -> new IllegalStateException("Default world is not loaded"));
                        return NexoraWorldInfo.from(world, 0, findSpawnY(world), 0, tickRate);
                    },
                    () -> worlds.get(worldId)
                            .orElseThrow(() -> new IllegalStateException("Default world is not loaded")),
                    null,
                    connection -> log.accept("NEXORA client completed protocol handshake: "
                            + connection.id()),
                    entities, inventories, playerInventoryStorage, serverResourcePacks,
                    position -> {
                        WorldChunkPipeline active = activeChunkPipeline.get();
                        return active == null
                                ? CompletableFuture.completedFuture(false)
                                : active.requestAsync(position);
                    },
                    activeChunkStreaming::get);
            server.register(new NetherNetPrivateProtocolModule(
                    peerHost, peerHost, signaling, protocolHandler));
        }

        return new RuntimeHandle(server, worlds, entities, inventories,
                playerInventoryStorage, gameLoop, tickRate, worldId);
    }

    private static BedrockStartGamePacketCodec.Settings createBedrockStartGameSettings(
            World world, BedrockAuthenticatedPlayer player, long seed, String worldId) {
        int spawnY = findSpawnY(world);
        long runtimeId = UUID.nameUUIDFromBytes(
                player.identityId().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .getLeastSignificantBits() & Long.MAX_VALUE;
        if (runtimeId == 0) {
            runtimeId = 1;
        }
        BedrockLevelSettingsCodec.Settings level = new BedrockLevelSettingsCodec.Settings(
                seed, 0, new BedrockStartGamePrimitivesCodec.BlockPosition(0, spawnY, 0),
                1, 0, 4, BedrockProtocolTarget.BEDROCK_1_26_60_VERSION);
        return new BedrockStartGamePacketCodec.Settings(
                runtimeId, runtimeId, 0,
                new BedrockStartGamePrimitivesCodec.Vec3(0.5f, spawnY, 0.5f),
                new BedrockStartGamePrimitivesCodec.Vec2(0, 0),
                level, worldId, "NEXORA", "", false,
                new BedrockStartGamePrimitivesCodec.SyncedPlayerMovementSettings(0, false),
                0L, 0, "nexora-" + runtimeId, false, "NEXORA/" + VERSION,
                BedrockStartGamePacketCodec.PlayerPropertyData.empty(),
                0L, 0L, 0L, false, true, false,
                BedrockStartGamePacketCodec.Telemetry.empty());
    }

    /**
     * Finds a safe vertical spawn coordinate above the highest solid block at
     * the default world spawn column. The spawn chunk must already be loaded.
     */
    static int findSpawnY(World world) {
        Objects.requireNonNull(world, "world");
        if (!world.isChunkLoaded(new ChunkPosition(0, 0))) {
            throw new IllegalStateException("Spawn chunk is not loaded");
        }
        for (int y = world.height().maxYExclusive() - 2; y >= world.height().minY(); y--) {
            if (!world.blockAt(new BlockPosition(0, y, 0)).isAir()) {
                return y + 1;
            }
        }
        throw new IllegalStateException("No solid spawn surface exists at 0,0");
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
        long seed = Long.MIN_VALUE;
        boolean help = false;
        boolean version = false;
        boolean developmentNetworkEnabled = false;
        String bindAddress = "0.0.0.0";
        int signalingPort = 8080;
        int tickRate = 0;
        boolean bedrockNetworkEnabled = false;
        boolean bedrockOptionsSpecified = false;
        String bedrockBindAddress = "0.0.0.0";
        int bedrockPort = 19132;
        int bedrockIcePort = 0;
        Path bedrockIdentityFile = Path.of("nethernet-identity.pem");

        for (int index = 0; index < args.length; index++) {
            String argument = Objects.requireNonNull(args[index], "argument");
            switch (argument) {
                case "--help", "-h" -> help = true;
                case "--version", "-v" -> version = true;
                case "--enable-development-network" -> developmentNetworkEnabled = true;
                case "--enable-bedrock-network" -> bedrockNetworkEnabled = true;
                case "--bedrock-bind-address" -> {
                    bedrockOptionsSpecified = true;
                    if (++index >= args.length || args[index].isBlank()) {
                        throw new IllegalArgumentException("--bedrock-bind-address requires a host or IP");
                    }
                    bedrockBindAddress = args[index].trim();
                }
                case "--bedrock-port" -> {
                    bedrockOptionsSpecified = true;
                    if (++index >= args.length) {
                        throw new IllegalArgumentException("--bedrock-port requires a port");
                    }
                    try {
                        bedrockPort = Integer.parseInt(args[index]);
                    } catch (NumberFormatException invalidPort) {
                        throw new IllegalArgumentException("--bedrock-port must be an integer", invalidPort);
                    }
                    if (bedrockPort < 1 || bedrockPort > 65535) {
                        throw new IllegalArgumentException("--bedrock-port must be between 1 and 65535");
                    }
                }
                case "--bedrock-ice-port" -> {
                    bedrockOptionsSpecified = true;
                    if (++index >= args.length) {
                        throw new IllegalArgumentException("--bedrock-ice-port requires a port (0 means shared)");
                    }
                    try {
                        bedrockIcePort = Integer.parseInt(args[index]);
                    } catch (NumberFormatException invalidPort) {
                        throw new IllegalArgumentException("--bedrock-ice-port must be an integer", invalidPort);
                    }
                    if (bedrockIcePort < 0 || bedrockIcePort > 65535) {
                        throw new IllegalArgumentException("--bedrock-ice-port must be between 0 and 65535");
                    }
                }
                case "--bedrock-identity-file" -> {
                    bedrockOptionsSpecified = true;
                    if (++index >= args.length || args[index].isBlank()) {
                        throw new IllegalArgumentException("--bedrock-identity-file requires a non-empty path");
                    }
                    bedrockIdentityFile = Path.of(args[index]);
                }
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
        if (!bedrockNetworkEnabled && bedrockOptionsSpecified) {
            throw new IllegalArgumentException(
                    "Bedrock listener options require --enable-bedrock-network");
        }
        BedrockListenerSettings bedrockSettings = bedrockNetworkEnabled
                ? new BedrockListenerSettings(true, bedrockBindAddress, bedrockPort,
                        bedrockIcePort, bedrockIdentityFile, true)
                : BedrockListenerSettings.disabled();
        return new LaunchOptions(dataDirectory.toAbsolutePath().normalize(), seed, help, version,
                developmentNetworkEnabled, bindAddress, signalingPort, tickRate, bedrockSettings);
    }

    private static void printUsage(Consumer<String> output) {
        output.accept("Usage: java -jar nexora-runtime.jar [options]");
        output.accept("  --data-dir <path>              Directory for world data (default: ./nexora-data)");
        output.accept("  --seed <number>                World generation seed (default: 0)");
        output.accept("  --tick-rate <number>           Override server.properties tick rate");
        output.accept("  --enable-development-network   Enable unauthenticated private-network testing");
        output.accept("  --bind-address <host>          Signaling bind host (default: 0.0.0.0)");
        output.accept("  --signaling-port <port>        HTTP signaling port (default: 8080)");
        output.accept("  --enable-bedrock-network       Enable authenticated Bedrock 1.26.60 NetherNet ingress");
        output.accept("  --bedrock-bind-address <host>  Bedrock listener bind host (default: 0.0.0.0)");
        output.accept("  --bedrock-port <port>          TCP signaling and shared UDP media port (default: 19132)");
        output.accept("  --bedrock-ice-port <port>      Separate UDP media port; 0 shares --bedrock-port");
        output.accept("  --bedrock-identity-file <path> Persistent NetherNet operator identity PEM");
        output.accept("  --version                      Print the NEXORA version");
        output.accept("  --help                         Show this help");
    }

    record LaunchOptions(Path dataDirectory, long seed, boolean help, boolean version,
                         boolean developmentNetworkEnabled, String bindAddress,
                         int signalingPort, int tickRate,
                         BedrockListenerSettings bedrockListenerSettings) {
        LaunchOptions {
            Objects.requireNonNull(dataDirectory, "dataDirectory");
            Objects.requireNonNull(bindAddress, "bindAddress");
            Objects.requireNonNull(bedrockListenerSettings, "bedrockListenerSettings");
            if (bindAddress.isBlank() || signalingPort < 1 || signalingPort > 65535
                    || tickRate < 0 || tickRate > 120) {
                throw new IllegalArgumentException("Invalid NEXORA launch configuration");
            }
        }
    }

    record RuntimeHandle(NexoraServer server, WorldRegistry worlds, EntityRegistry entities,
                    Map<UUID, PlayerInventory> inventories,
                    FilePlayerInventoryStorage playerInventoryStorage,
                    GameLoop gameLoop, int tickRate, String worldId) {
        RuntimeHandle {
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(worlds, "worlds");
            Objects.requireNonNull(entities, "entities");
            Objects.requireNonNull(inventories, "inventories");
            Objects.requireNonNull(playerInventoryStorage, "playerInventoryStorage");
            Objects.requireNonNull(gameLoop, "gameLoop");
            Objects.requireNonNull(worldId, "worldId");
            if (tickRate < 1 || tickRate > 120) {
                throw new IllegalArgumentException("tickRate must be between 1 and 120");
            }
        }


        void saveInventories() throws java.io.IOException {
            java.io.IOException aggregate = null;
            for (PlayerInventory inventory : inventories.values()) {
                try {
                    playerInventoryStorage.save(inventory);
                } catch (java.io.IOException failure) {
                    if (aggregate == null) {
                        aggregate = new java.io.IOException("One or more player inventories could not be saved");
                    }
                    aggregate.addSuppressed(failure);
                }
            }
            if (aggregate != null) throw aggregate;
        }

        PlayerInventory inventory(UUID playerId) {
            Objects.requireNonNull(playerId, "playerId");
            return inventories.computeIfAbsent(playerId, PlayerInventory::new);
        }

        ItemPickupService.PickupResult pickupItems(UUID playerId, double x, double y, double z) {
            World world = worlds.get(worldId)
                    .orElseThrow(() -> new IllegalStateException("Default world is not loaded"));
            return new ItemPickupService(entities, world).pickup(playerId, x, y, z, inventory(playerId));
        }

        NexoraWorldInfo worldInfo() {
            World world = worlds.get(worldId)
                    .orElseThrow(() -> new IllegalStateException("Default world is not loaded"));
            return NexoraWorldInfo.from(world, 0, NexoraMain.findSpawnY(world), 0, tickRate);
        }

        NexoraPrivateProtocolHandler privateProtocolHandler(
                UUID serverId, Consumer<TransportConnection> readyListener) {
            return new NexoraPrivateProtocolHandler(serverId,
                    WebRtcJavaNetherNetPeerHost.MAX_MESSAGE_BYTES,
                    this::worldInfo,
                    () -> worlds.get(worldId)
                            .orElseThrow(() -> new IllegalStateException("Default world is not loaded")),
                    position -> worlds.get(worldId)
                            .map(world -> world.isChunkLoaded(position)).orElse(false),
                    readyListener, entities, inventories, playerInventoryStorage);
        }
    }
}
