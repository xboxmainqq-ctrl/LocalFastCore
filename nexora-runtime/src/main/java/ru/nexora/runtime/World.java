package ru.nexora.runtime;

/** Minimal world contract, independent of any legacy server API. */
public interface World {
    String id();

    WorldHeight height();

    BlockState blockAt(BlockPosition position);

    void setBlock(BlockPosition position, BlockState state);

    boolean isChunkLoaded(ChunkPosition position);

    int loadedChunkCount();
}
