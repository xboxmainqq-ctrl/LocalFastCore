package ru.nexora.runtime;

/**
 * Produces protocol-independent chunk data.
 *
 * <p>Implementations should be deterministic for the same position, world
 * height, and seed. Generation must not mutate a World or perform network or
 * disk I/O; callers decide how and when to commit the immutable result.</p>
 */
@FunctionalInterface
public interface ChunkGenerator {
    GeneratedChunk generate(ChunkPosition position, WorldHeight height, long seed);
}
