package ru.nexora.runtime;

/** Horizontal biome coordinate shared by every vertical section of a chunk column. */
public record BiomeColumnPosition(int x, int z) {
    public ChunkPosition chunkPosition() {
        return ChunkPosition.fromBlock(x, z);
    }
}
