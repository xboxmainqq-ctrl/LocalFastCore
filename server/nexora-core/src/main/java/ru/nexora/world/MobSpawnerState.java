package ru.nexora.world;

import java.util.Objects;

/** Persisted configuration and timer for one mob spawner block. */
public record MobSpawnerState(int x, int y, int z, String entityIdentifier,
                              int delayTicks, long nextSpawnTick) {
    public static final int DEFAULT_DELAY_TICKS = 200;
    public static final int MIN_DELAY_TICKS = 20;
    public static final int MAX_DELAY_TICKS = 800;

    public MobSpawnerState {
        Objects.requireNonNull(entityIdentifier, "entityIdentifier");
        if (!entityIdentifier.matches("minecraft:[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid spawner entity identifier");
        }
        if (delayTicks < MIN_DELAY_TICKS || delayTicks > MAX_DELAY_TICKS || nextSpawnTick < 0
                || Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000
                || y <= WorldChunk.MIN_Y || y >= WorldChunk.MAX_Y) {
            throw new IllegalArgumentException("Invalid spawner position or timing");
        }
    }

    public MobSpawnerState withNextSpawnTick(long tick) {
        return new MobSpawnerState(x, y, z, entityIdentifier, delayTicks, tick);
    }

    public MobSpawnerState withConfiguration(String identifier, int delay, long nextTick) {
        return new MobSpawnerState(x, y, z, identifier, delay, nextTick);
    }
}
