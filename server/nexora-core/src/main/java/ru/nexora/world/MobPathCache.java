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
    private long hits;
    private long misses;
    private long recomputations;
    private long blockedWaypoints;
    private long evictions;

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
        return findNextStep(mobRuntimeId, world, startX, feetY, startZ,
                targetX, targetZ, nowMillis, "minecraft:zombie");
    }

    /** Species-aware cached navigation; cache keys include the mob identity and destination cells. */
    public Optional<WorldPathfinder.Step> findNextStep(
            long mobRuntimeId, World world, double startX, double feetY, double startZ,
            double targetX, double targetZ, long nowMillis, String identifier
    ) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Mob identifier must not be blank");
        }
        if (world == null || !Double.isFinite(startX) || !Double.isFinite(feetY)
                || !Double.isFinite(startZ) || !Double.isFinite(targetX)
                || !Double.isFinite(targetZ)) {
            return WorldPathfinder.findNextStep(
                    world, startX, feetY, startZ, targetX, targetZ, identifier);
        }

        Key key = new Key(cell(startX), cell(feetY), cell(startZ),
                cell(targetX), cell(targetZ));
        Entry cached = get(mobRuntimeId, key, nowMillis);
        if (cached != null) {
            WorldPathfinder.Step step = cached.step();
            if (WorldPathfinder.isMobFootprintLoaded(world, startX, startZ, identifier)
                    && WorldPathfinder.isMobFootprintLoaded(world, step.x(), step.z(), identifier)
                    && WorldCollision.canStepMob(world, identifier, startX, feetY, startZ,
                    step.x(), step.y(), step.z())) {
                recordHit();
                return Optional.of(step);
            }
            recordBlockedWaypoint();
        }

        Optional<WorldPathfinder.Step> computed = WorldPathfinder.findNextStep(
                world, startX, feetY, startZ, targetX, targetZ, identifier);
        recordRecomputation();
        // Do not cache "no path": terrain can open at any time and should be retried immediately.
        if (computed.isPresent()) {
            put(mobRuntimeId, new Entry(key, computed.orElseThrow(), nowMillis + ttlMillis));
        }
        return computed;
    }

    private synchronized Entry get(long mobRuntimeId, Key key, long nowMillis) {
        Entry entry = entries.get(mobRuntimeId);
        if (entry == null) {
            misses++;
            return null;
        }
        if (nowMillis >= entry.expiresAtMillis() || !entry.key().equals(key)) {
            entries.remove(mobRuntimeId);
            misses++;
            return null;
        }
        return entry;
    }

    private synchronized void put(long mobRuntimeId, Entry entry) {
        entries.put(mobRuntimeId, entry);
        while (entries.size() > maxEntries) {
            Long eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
            evictions++;
        }
    }

    private synchronized void recordHit() {
        hits++;
    }

    private synchronized void recordBlockedWaypoint() {
        blockedWaypoints++;
    }

    private synchronized void recordRecomputation() {
        recomputations++;
    }

    /** Returns cumulative cache counters; counters are local to this cache instance. */
    public synchronized Stats stats() {
        return new Stats(hits, misses, recomputations, blockedWaypoints, evictions, entries.size());
    }

    /** Counts expired entries without mutating the cache. */
    public synchronized int expiredEntries(long nowMillis) {
        if (nowMillis < 0) throw new IllegalArgumentException("nowMillis must be non-negative");
        int expired = 0;
        for (Entry entry : entries.values()) {
            if (nowMillis >= entry.expiresAtMillis()) expired++;
        }
        return expired;
    }

    /** Removes expired transient navigation entries while preserving cumulative counters. */
    public synchronized int pruneExpired(long nowMillis) {
        if (nowMillis < 0) throw new IllegalArgumentException("nowMillis must be non-negative");
        int before = entries.size();
        entries.values().removeIf(entry -> nowMillis >= entry.expiresAtMillis());
        return before - entries.size();
    }

    public synchronized void clear() {
        entries.clear();
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

    private record Entry(Key key, WorldPathfinder.Step step, long expiresAtMillis) {
    }

    public record Stats(
            long hits,
            long misses,
            long recomputations,
            long blockedWaypoints,
            long evictions,
            int entries
    ) {
        public long requests() {
            return hits + misses;
        }

        public double hitRate() {
            long total = requests();
            return total == 0 ? 0.0 : (double) hits / total;
        }

        /** Compact operator-safe representation; contains aggregate counters only. */
        public String summary() {
            return "hits=" + hits
                    + ", misses=" + misses
                    + ", hitRate=" + (Math.round(hitRate() * 1000.0) / 10.0) + "%"
                    + ", recomputations=" + recomputations
                    + ", blocked=" + blockedWaypoints
                    + ", evictions=" + evictions
                    + ", entries=" + entries;
        }
    }
}
