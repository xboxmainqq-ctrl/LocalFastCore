package ru.nexora.runtime;

import java.util.Objects;

/** Result produced by a background chunk-generation worker. */
public record ChunkGenerationResult(
        ChunkPosition position,
        GeneratedChunk chunk,
        RuntimeException failure
) {
    public ChunkGenerationResult {
        Objects.requireNonNull(position, "position");
        if ((chunk == null) == (failure == null)) {
            throw new IllegalArgumentException("Exactly one of chunk or failure must be present");
        }
        if (chunk != null && !chunk.position().equals(position)) {
            throw new IllegalArgumentException("Generated chunk position does not match result position");
        }
    }

    public boolean succeeded() {
        return chunk != null;
    }
}
