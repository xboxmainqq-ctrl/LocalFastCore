package ru.nexora.world;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Server-authoritative in-memory Overworld. Persistence is a separate layer added next. */
public final class World {
    private final String name;
    private final long seed;
    private final WorldGenerator generator;
    private final int spawnX;
    private final int spawnY;
    private final int spawnZ;
    private final WorldStorage storage;
    private final ConcurrentMap<Long, WorldChunk> chunks = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<WorldBlockChangeListener> blockChangeListeners = new CopyOnWriteArrayList<>();

    public World(String name, long seed, WorldGenerator generator) {
        this(name, seed, generator, 0, 65, 0, null);
    }

    public World(String name, long seed, WorldGenerator generator, int spawnX, int spawnY, int spawnZ) {
        this(name, seed, generator, spawnX, spawnY, spawnZ, null);
    }

    public World(
            String name, long seed, WorldGenerator generator,
            int spawnX, int spawnY, int spawnZ, WorldStorage storage
    ) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("World name must not be blank");
        if (spawnY <= WorldChunk.MIN_Y || spawnY > WorldChunk.MAX_Y) {
            throw new IllegalArgumentException("Spawn Y must be inside the Overworld build height");
        }
        this.name = name;
        this.seed = seed;
        this.generator = Objects.requireNonNull(generator, "generator");
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.storage = storage;
    }

    public String name() {
        return name;
    }

    public long seed() {
        return seed;
    }

    public int spawnX() { return spawnX; }
    public int spawnY() { return spawnY; }
    public int spawnZ() { return spawnZ; }

    public WorldChunk getChunk(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        return chunks.computeIfAbsent(key, ignored -> {
            if (storage != null) {
                try {
                    Optional<WorldChunk> stored = storage.loadChunk(chunkX, chunkZ);
                    if (stored.isPresent()) return stored.get();
                } catch (IOException exception) {
                    throw new UncheckedIOException("Unable to load NEXORA chunk " + chunkX + "," + chunkZ, exception);
                }
            }
            WorldChunk chunk = new WorldChunk(chunkX, chunkZ);
            generator.generate(chunk, seed);
            return chunk;
        });
    }

    public BlockType getBlock(int blockX, int y, int blockZ) {
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        int localX = Math.floorMod(blockX, 16);
        int localZ = Math.floorMod(blockZ, 16);
        return getChunk(chunkX, chunkZ).getBlock(localX, y, localZ);
    }

    /**
     * Changes one block atomically within its chunk and publishes a runtime change event.
     * Generator and storage loading paths intentionally bypass this runtime event stream.
     */
    public void setBlock(int blockX, int y, int blockZ, BlockType type) {
        Objects.requireNonNull(type, "type");
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        int localX = Math.floorMod(blockX, 16);
        int localZ = Math.floorMod(blockZ, 16);
        BlockType previous = getChunk(chunkX, chunkZ).setBlockAndGetPrevious(localX, y, localZ, type);
        if (previous == type) return;
        BlockChange change = new BlockChange(blockX, y, blockZ, previous, type);
        for (WorldBlockChangeListener listener : blockChangeListeners) {
            listener.onBlockChanged(change);
        }
    }

    /** Registers a runtime block-change listener and returns a handle that unregisters it. */
    public AutoCloseable addBlockChangeListener(WorldBlockChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        blockChangeListeners.add(listener);
        return () -> blockChangeListeners.remove(listener);
    }

    public record BlockChange(int x, int y, int z, BlockType previous, BlockType current) {
        public BlockChange {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
        }
    }

    public int loadedChunkCount() {
        return chunks.size();
    }

    public void saveAll() throws IOException {
        if (storage == null) return;
        for (WorldChunk chunk : chunks.values()) storage.saveChunk(chunk);
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffff_ffffL);
    }
}
