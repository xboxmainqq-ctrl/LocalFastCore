package ru.nexora.combat;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded observer for hostile mobs that remain beyond the player despawn radius. */
public final class MobDespawnTracker {
    public static final double DESPAWN_RADIUS = 128.0;
    public static final long DESPAWN_AFTER_MILLIS = 30_000L;
    public static final int DEFAULT_MAX_TRACKED = 5_000;

    private final int maxTracked;
    private final Map<Long, Long> farSince = new LinkedHashMap<>(16, 0.75f, true);
    private long evictions;

    public MobDespawnTracker() {
        this(DEFAULT_MAX_TRACKED);
    }

    public MobDespawnTracker(int maxTracked) {
        if (maxTracked < 1) throw new IllegalArgumentException("maxTracked must be positive");
        this.maxTracked = maxTracked;
    }

    /**
     * Returns true only for hostile mobs continuously beyond 128 blocks for 30 seconds.
     * Passive and neutral mobs are never removed by this tracker.
     */
    public synchronized boolean shouldDespawn(
            long runtimeId, boolean hostile, double nearestPlayerDistanceSquared, long nowMillis
    ) {
        if (runtimeId <= 0 || Double.isNaN(nearestPlayerDistanceSquared)
                || nearestPlayerDistanceSquared < 0 || nowMillis < 0) {
            throw new IllegalArgumentException("Invalid mob despawn observation");
        }
        if (!hostile || nearestPlayerDistanceSquared <= DESPAWN_RADIUS * DESPAWN_RADIUS) {
            farSince.remove(runtimeId);
            return false;
        }
        Long started = farSince.get(runtimeId);
        if (started == null || nowMillis < started) {
            farSince.put(runtimeId, nowMillis);
            trim();
            return false;
        }
        return nowMillis - started >= DESPAWN_AFTER_MILLIS;
    }

    public synchronized void clear(long runtimeId) {
        farSince.remove(runtimeId);
    }

    public synchronized Stats stats() {
        return new Stats(farSince.size(), evictions);
    }

    private void trim() {
        while (farSince.size() > maxTracked) {
            Long eldest = farSince.keySet().iterator().next();
            farSince.remove(eldest);
            evictions++;
        }
    }

    public record Stats(int tracked, long evictions) { }
}
