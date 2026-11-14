package ru.nexora.runtime;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Command-line entry point for the standalone NEXORA development runtime.
 *
 * <p>This bootstrap starts world persistence, a generated spawn chunk, bounded
 * asynchronous chunk generation, and the game tick loop. It deliberately does
 * not enable a network listener: authenticated NetherNet/Bedrock composition
 * is not yet a production-ready launch profile.</p>
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

        RuntimeHandle runtime = createRuntime(options.dataDirectory(), options.seed(),
                System.out::println);
        runtime.server().start();
        System.out.println("NEXORA Core " + VERSION + " started.");
        System.out.println("World: " + DEFAULT_WORLD_ID
                + " | spawn chunk: 0,0 | tick rate: 20");
        System.out.println("Network ingress: disabled (development runtime only).");
        System.out.println("Data directory: " + options.dataDirectory().toAbsolutePath().normalize());
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
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(log, "log");

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
                // WorldStorageModule owns persistence and saves after all runtime modules stop.
            }
        });

        TickScheduler scheduler = new TickScheduler();
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
                20, 3, scheduler,
                failure -> log.accept("Game tick error: " + failure.getMessage()));
        server.register(gameLoop);
        return new RuntimeHandle(server, worlds, gameLoop);
    }

    static LaunchOptions parseArguments(String[] args) {
        Objects.requireNonNull(args, "args");
        Path dataDirectory = Path.of("nexora-data");
        long seed = 0L;
        boolean help = false;
        boolean version = false;

        for (int index = 0; index < args.length; index++) {
            String argument = Objects.requireNonNull(args[index], "argument");
            switch (argument) {
                case "--help", "-h" -> help = true;
                case "--version", "-v" -> version = true;
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
        return new LaunchOptions(dataDirectory.toAbsolutePath().normalize(), seed, help, version);
    }

    private static void printUsage(Consumer<String> output) {
        output.accept("Usage: java -jar nexora-runtime.jar [options]");
        output.accept("  --data-dir <path>  Directory for world data (default: ./nexora-data)");
        output.accept("  --seed <number>    World generation seed (default: 0)");
        output.accept("  --version          Print the NEXORA version");
        output.accept("  --help             Show this help");
    }

    record LaunchOptions(Path dataDirectory, long seed, boolean help, boolean version) {
        LaunchOptions {
            Objects.requireNonNull(dataDirectory, "dataDirectory");
        }
    }

    record RuntimeHandle(NexoraServer server, WorldRegistry worlds, GameLoop gameLoop) {
        RuntimeHandle {
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(worlds, "worlds");
            Objects.requireNonNull(gameLoop, "gameLoop");
        }
    }

    /**
     * Defers the world lookup until after the storage module has been started.
     * The pipeline only retains the actual world instance once the module
     * registration order guarantees that it exists.
     */
    private record WorldReference(WorldRegistry registry, String worldId) {
        private World worldAfterLoad() {
            return new DeferredWorld(registry, worldId);
        }
    }

    /**
     * Resolves world operations lazily so runtime modules can be assembled
     * before WorldStorageModule starts. This wrapper is intentionally avoided
     * by the actual pipeline; it exists only to guard construction ordering.
     */
    private static final class DeferredWorld implements World {
        private final WorldRegistry registry;
        private final String worldId;

        private DeferredWorld(WorldRegistry registry, String worldId) {
            this.registry = registry;
            this.worldId = worldId;
        }

        private World delegate() {
            return registry.get(worldId)
                    .orElseThrow(() -> new IllegalStateException("World is not loaded: " + worldId));
        }

        @Override public String id() { return delegate().id(); }
        @Override public WorldHeight height() { return delegate().height(); }
        @Override public BlockState blockAt(BlockPosition position) { return delegate().blockAt(position); }
        @Override public void setBlock(BlockPosition position, BlockState state) { delegate().setBlock(position, state); }
        @Override public BlockState fluidAt(BlockPosition position) { return delegate().fluidAt(position); }
        @Override public void setFluid(BlockPosition position, BlockState state) { delegate().setFluid(position, state); }
        @Override public java.util.Map<BlockPosition, BlockState> fluidsInChunk(ChunkPosition position) { return delegate().fluidsInChunk(position); }
        @Override public int biomeAt(BlockPosition position) { return delegate().biomeAt(position); }
        @Override public void setBiome(BlockPosition position, int biomeId) { delegate().setBiome(position, biomeId); }
        @Override public int[] biomesInSection(ChunkPosition position, int sectionY) { return delegate().biomesInSection(position, sectionY); }
        @Override public java.util.List<Integer> biomeSectionsInChunk(ChunkPosition position) { return delegate().biomeSectionsInChunk(position); }
        @Override public boolean loadChunk(GeneratedChunk chunk) { return delegate().loadChunk(chunk); }
        @Override public boolean isChunkLoaded(ChunkPosition position) { return delegate().isChunkLoaded(position); }
        @Override public int loadedChunkCount() { return delegate().loadedChunkCount(); }
        @Override public java.util.List<ChunkPosition> loadedChunks() { return delegate().loadedChunks(); }
        @Override public java.util.Map<BlockPosition, BlockState> blocksInChunk(ChunkPosition position) { return delegate().blocksInChunk(position); }
    }
}
