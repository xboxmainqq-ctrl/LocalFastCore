package ru.nexora.world;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Server-authoritative in-memory Overworld. Persistence is a separate layer added next. */
public final class World {
    private final String name;
    private final long seed;
    private final WorldGenerator generator;
    private final ConcurrentMap<Long, WorldChunk> chunks = new ConcurrentHashMap<>();

    public World(String name, long seed, WorldGenerator generator) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("World name must not be blank");
        this.name = name;
        this.seed = seed;
        this.generator = Objects.requireNonNull(generator, "generator");
    }

    public String name() {
        return name;
    }

    public long seed() {
        return seed;
    }

    public WorldChunk getChunk(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        return chunks.computeIfAbsent(key, ignored -> {
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

    public void setBlock(int blockX, int y, int blockZ, BlockType type) {
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        int localX = Math.floorMod(blockX, 16);
        int localZ = Math.floorMod(blockZ, 16);
        getChunk(chunkX, chunkZ).setBlock(localX, y, localZ, type);
    }

    public int loadedChunkCount() {
        return chunks.size();
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffff_ffffL);
    }
}
