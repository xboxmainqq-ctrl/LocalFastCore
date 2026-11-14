package ru.nexora.runtime;

import java.util.Objects;

/** Immutable world-entry metadata sent by the private NEXORA protocol. */
public record NexoraWorldInfo(
        String worldId,
        int minY,
        int maxYExclusive,
        int spawnX,
        int spawnY,
        int spawnZ,
        int tickRate,
        int loadedChunks
) {
    public NexoraWorldInfo {
        Objects.requireNonNull(worldId, "worldId");
        if (worldId.isBlank() || worldId.length() > 64
                || maxYExclusive <= minY || spawnY < minY || spawnY >= maxYExclusive
                || tickRate < 1 || tickRate > 120 || loadedChunks < 0) {
            throw new IllegalArgumentException("Invalid NEXORA world information");
        }
    }

    public static NexoraWorldInfo from(World world, int spawnX, int spawnY, int spawnZ,
                                       int tickRate) {
        Objects.requireNonNull(world, "world");
        WorldHeight height = world.height();
        return new NexoraWorldInfo(world.id(), height.minY(), height.maxYExclusive(),
                spawnX, spawnY, spawnZ, tickRate, world.loadedChunkCount());
    }
}
