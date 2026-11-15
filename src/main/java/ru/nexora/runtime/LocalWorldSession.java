package ru.nexora.runtime;

import java.io.IOException;
import java.util.Objects;

/**
 * Owns one local single-player world session.
 *
 * <p>The session reuses the same protocol-independent world model as the
 * standalone server runtime. It loads an existing save when available,
 * generates missing chunks on demand, and persists the world when saved or
 * closed. Chunk generation is synchronous in this first milestone and should
 * be called from a worker thread by the Android client.</p>
 */
public final class LocalWorldSession implements AutoCloseable {
    private final FileWorldStorage storage;
    private final ChunkGenerator generator;
    private final long seed;
    private final InMemoryWorld world;
    private boolean closed;

    private LocalWorldSession(
            FileWorldStorage storage,
            ChunkGenerator generator,
            long seed,
            InMemoryWorld world
    ) {
        this.storage = storage;
        this.generator = generator;
        this.seed = seed;
        this.world = world;
    }

    /**
     * Opens a saved world, or creates a new empty world using the supplied
     * initial height. A saved world's stored height always takes precedence.
     */
    public static LocalWorldSession open(
            FileWorldStorage storage,
            String worldId,
            WorldHeight initialHeight,
            ChunkGenerator generator,
            long seed
    ) throws IOException {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(initialHeight, "initialHeight");
        Objects.requireNonNull(generator, "generator");
        InMemoryWorld world = storage.load(worldId)
                .orElseGet(() -> new InMemoryWorld(worldId, initialHeight));
        return new LocalWorldSession(storage, generator, seed, world);
    }

    /** Returns the live world model while this session remains open. */
    public synchronized World world() {
        ensureOpen();
        return world;
    }

    /**
     * Loads an existing chunk or generates and atomically installs a missing
     * one. Returns false only when another caller installed it first.
     */
    public synchronized boolean ensureChunkLoaded(ChunkPosition position) {
        ensureOpen();
        Objects.requireNonNull(position, "position");
        if (world.isChunkLoaded(position)) {
            return false;
        }
        GeneratedChunk generated = generator.generate(position, world.height(), seed);
        return world.loadChunk(generated);
    }

    /** Ensures a square chunk area around the supplied center is loaded. */
    public synchronized int ensureAreaLoaded(ChunkPosition center, int radius) {
        ensureOpen();
        Objects.requireNonNull(center, "center");
        if (radius < 0 || radius > 8) {
            throw new IllegalArgumentException("Chunk radius must be between 0 and 8");
        }
        int loaded = 0;
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                ChunkPosition position = new ChunkPosition(
                        Math.addExact(center.x(), offsetX),
                        Math.addExact(center.z(), offsetZ)
                );
                if (ensureChunkLoaded(position)) {
                    loaded++;
                }
            }
        }
        return loaded;
    }

    /** Saves the current world without closing the session. */
    public synchronized void save() throws IOException {
        ensureOpen();
        storage.save(world);
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    /** Saves before closing. If saving fails, the session remains open for retry. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        storage.save(world);
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Local world session is closed");
        }
    }
}
