package ru.nexora.world;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Small per-world cache for repeated mob navigation requests.
 *
 * Cached first steps are revalidated against the current world before reuse,
 * so a newly placed block cannot make a mob walk directly into it.
 */
public final class MobPathCache {
    private static final int DEFAULT_MAX_ENTRIES = 1_024;
    private static final long DEFAULT_TTL_MILLIS = 150L;

    private final int maxEntries;
    private final long ttlMillis;
    private final Map<Long, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public MobPathCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_MILLIS);
    }

    public MobPathCache(int maxEntries, long ttlMillis) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        if (ttlMillis < 1) throw new IllegalArgumentException("ttlMillis must be positive");
        this.maxEntries = maxEntries;
        this.ttlMillis = ttlMillis;
    }

    public Optional<WorldPathfinder.Step> findNextStep(
            long mobRuntimeId,
            World world,
            double startX,
            double feetY,
            double startZ,
            double targetX,
            double targetZ,
            long nowMillis
    ) {
        if (world == null || !Double.isFinite(startX) || !Double.isFinite(feetY)
                || !Double.isFinite(startZ) || !Double.isFinite(targetX)
                || !Double.isFinite(targetZ)) {
            return WorldPathfinder.findNextStep(
                    world, startX, feetY, startZ, targetX, targetZ);
        }

        Key key = new Key(cell(startX), cell(feetY), cell(startZ),
                cell(targetX), cell(targetZ));
        Entry cached = get(mobRuntimeId, key, nowMillis);
        if (cached != null) {
            if (cached.step().isEmpty()) return Optional.empty();
            WorldPathfinder.Step step = cached.step().orElseThrow();
            if (WorldCollision.canMove(world, startX, feetY, startZ, step.x(), feetY, step.z())) {
                return cached.step();
            }
        }

        Optional<WorldPathfinder.Step> computed = WorldPathfinder.findNextStep(
                world, startX, feetY, startZ, targetX, targetZ);
        // Do not cache "no path": terrain can open at any time and should be retried immediately.
        if (computed.isPresent()) {
            put(mobRuntimeId, new Entry(key, computed, nowMillis + ttlMillis));
        }
        return computed;
    }

    private synchronized Entry get(long mobRuntimeId, Key key, long nowMillis) {
        Entry entry = entries.get(mobRuntimeId);
        if (entry == null) return null;
        if (nowMillis >= entry.expiresAtMillis() || !entry.key().equals(key)) {
            entries.remove(mobRuntimeId);
            return null;
        }
        return entry;
    }

    private synchronized void put(long mobRuntimeId, Entry entry) {
        entries.put(mobRuntimeId, entry);
        while (entries.size() > maxEntries) {
            Long eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
    }

    public synchronized void remove(long mobRuntimeId) {
        entries.remove(mobRuntimeId);
    }

    public synchronized int size() {
        return entries.size();
    }

    private static int cell(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    private record Key(int startX, int feetY, int startZ, int targetX, int targetZ) {
    }

    private record Entry(Key key, Optional<WorldPathfinder.Step> step, long expiresAtMillis) {
    }
}
