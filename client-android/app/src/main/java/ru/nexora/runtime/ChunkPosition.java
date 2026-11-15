package ru.nexora.runtime;

/** Immutable horizontal chunk coordinates. Chunks are 16 by 16 blocks. */
public record ChunkPosition(int x, int z) {
    public static ChunkPosition fromBlock(int blockX, int blockZ) {
        return new ChunkPosition(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
    }
}
