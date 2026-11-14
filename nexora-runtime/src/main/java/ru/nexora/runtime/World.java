package ru.nexora.runtime;

import java.util.List;
import java.util.Map;

/** Minimal world contract, independent of any legacy server API. */
public interface World {
    String id();

    WorldHeight height();

    BlockState blockAt(BlockPosition position);

    void setBlock(BlockPosition position, BlockState state);

    boolean isChunkLoaded(ChunkPosition position);

    int loadedChunkCount();

    /** Returns an immutable snapshot of all currently loaded chunk positions. */
    List<ChunkPosition> loadedChunks();

    /** Returns an immutable snapshot of non-air blocks in the requested loaded chunk. */
    Map<BlockPosition, BlockState> blocksInChunk(ChunkPosition position);
}
