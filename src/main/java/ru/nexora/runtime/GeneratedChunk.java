package ru.nexora.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of chunk generation.
 *
 * <p>Only non-air blocks are stored. Every block must belong to the supplied
 * chunk; vertical bounds are checked by the generation request and again when
 * the generated data is committed to a world.</p>
 */
public record GeneratedChunk(ChunkPosition position, Map<BlockPosition, BlockState> blocks) {
    public GeneratedChunk {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(blocks, "blocks");
        Map<BlockPosition, BlockState> copy = new HashMap<>();
        blocks.forEach((blockPosition, state) -> {
            Objects.requireNonNull(blockPosition, "block position");
            Objects.requireNonNull(state, "block state");
            if (!blockPosition.chunkPosition().equals(position)) {
                throw new IllegalArgumentException(
                        "Generated block does not belong to chunk " + position + ": " + blockPosition
                );
            }
            if (state.isAir()) {
                throw new IllegalArgumentException("Generated chunk snapshots must omit air blocks");
            }
            copy.put(blockPosition, state);
        });
        blocks = Map.copyOf(copy);
    }
}
