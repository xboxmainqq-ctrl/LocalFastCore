package ru.nexora.runtime;

import java.util.List;
import java.util.Map;

/** Minimal world contract, independent of any legacy server API. */
public interface World {
    String id();

    WorldHeight height();

    BlockState blockAt(BlockPosition position);

    void setBlock(BlockPosition position, BlockState state);

    /** Returns the Bedrock biome ID at block resolution; unset cells use plains (ID 1). */
    int biomeAt(BlockPosition position);

    /** Sets the Bedrock biome ID at block resolution. IDs must be non-negative. */
    void setBiome(BlockPosition position, int biomeId);

    /** Returns a detached 4096-entry biome array in Bedrock XZY order for a loaded chunk section. */
    int[] biomesInSection(ChunkPosition position, int sectionY);

    /** Returns sorted section Y coordinates that contain non-default biome data. */
    List<Integer> biomeSectionsInChunk(ChunkPosition position);

    /** Atomically adds a generated chunk if it is not already loaded. */
    boolean loadChunk(GeneratedChunk generatedChunk);

    boolean isChunkLoaded(ChunkPosition position);

    int loadedChunkCount();

    /** Returns an immutable snapshot of all currently loaded chunk positions. */
    List<ChunkPosition> loadedChunks();

    /** Returns an immutable snapshot of non-air blocks in the requested loaded chunk. */
    Map<BlockPosition, BlockState> blocksInChunk(ChunkPosition position);
}
