package ru.nexora.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded observer for mobs that repeatedly attempt movement without making progress.
 * Detection is advisory only: this class never teleports, despawns, or changes an entity.
 */
public final class MobStuckTracker {
    public static final long STUCK_AFTER_MILLIS = 2_000L;
    public static final double MIN_PROGRESS_DISTANCE = 0.15;
    public static final int DEFAULT_MAX_TRACKED = 5_000;

    private final int maxTracked;
    private final Map<Long, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long evictions;

    public MobStuckTracker() {
        this(DEFAULT_MAX_TRACKED);
    }

    public MobStuckTracker(int maxTracked) {
        if (maxTracked < 1) throw new IllegalArgumentException("maxTracked must be positive");
        this.maxTracked = maxTracked;
    }

    /**
     * Records one blocked movement attempt. Returns a stuck snapshot once the mob
     * has remained within the progress radius for the configured duration.
     */
    public synchronized StuckMob recordBlockedAttempt(
            long runtimeId, double x, double y, double z, long nowMillis
    ) {
        validate(runtimeId, x, y, z, nowMillis);
        Entry entry = entries.get(runtimeId);
        if (entry == null) {
            put(runtimeId, new Entry(x, y, z, nowMillis, 1));
            return null;
        }

        double dx = x - entry.anchorX();
        double dy = y - entry.anchorY();
        double dz = z - entry.anchorZ();
        if (nowMillis < entry.startedAtMillis()
                || dx * dx + dy * dy + dz * dz >= MIN_PROGRESS_DISTANCE * MIN_PROGRESS_DISTANCE) {
            Entry reset = new Entry(x, y, z, nowMillis, 1);
            entries.put(runtimeId, reset);
            return null;
        }

        Entry updated = new Entry(entry.anchorX(), entry.anchorY(), entry.anchorZ(),
                entry.startedAtMillis(), entry.attempts() + 1);
        entries.put(runtimeId, updated);
        long duration = nowMillis - updated.startedAtMillis();
        return duration >= STUCK_AFTER_MILLIS
                ? new StuckMob(runtimeId, duration, updated.attempts()) : null;
    }

    /** Clears movement history after successful movement or when the mob becomes idle. */
    public synchronized void clear(long runtimeId) {
        entries.remove(runtimeId);
    }

    /** Returns a bounded list of the longest-running stuck mobs. */
    public synchronized List<StuckMob> stuck(long nowMillis, int limit) {
        if (nowMillis < 0 || limit < 1 || limit > 20) {
            throw new IllegalArgumentException("Invalid stuck snapshot bounds");
        }
        List<StuckMob> result = new ArrayList<>();
        for (Map.Entry<Long, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            long duration = nowMillis - entry.startedAtMillis();
            if (duration >= STUCK_AFTER_MILLIS) {
                result.add(new StuckMob(item.getKey(), duration, entry.attempts()));
            }
        }
        result.sort(Comparator.comparingLong(StuckMob::durationMillis).reversed());
        return List.copyOf(result.subList(0, Math.min(limit, result.size())));
    }

    public synchronized Stats stats(long nowMillis) {
        if (nowMillis < 0) throw new IllegalArgumentException("nowMillis must be non-negative");
        int stuck = 0;
        for (Entry entry : entries.values()) {
            if (nowMillis >= entry.startedAtMillis()
                    && nowMillis - entry.startedAtMillis() >= STUCK_AFTER_MILLIS) stuck++;
        }
        return new Stats(entries.size(), stuck, evictions);
    }

    private void put(long runtimeId, Entry entry) {
        entries.put(runtimeId, entry);
        while (entries.size() > maxTracked) {
            Long eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
            evictions++;
        }
    }

    private static void validate(long runtimeId, double x, double y, double z, long nowMillis) {
        if (runtimeId <= 0 || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z) || nowMillis < 0) {
            throw new IllegalArgumentException("Invalid mob movement observation");
        }
    }

    private record Entry(double anchorX, double anchorY, double anchorZ,
                         long startedAtMillis, long attempts) {
    }

    public record StuckMob(long runtimeId, long durationMillis, long attempts) {
        public StuckMob {
            if (runtimeId <= 0 || durationMillis < STUCK_AFTER_MILLIS || attempts < 2) {
                throw new IllegalArgumentException("Invalid stuck mob snapshot");
            }
        }
    }

    public record Stats(int tracked, int stuck, long evictions) {
        public Stats {
            if (tracked < 0 || stuck < 0 || stuck > tracked || evictions < 0) {
                throw new IllegalArgumentException("Invalid stuck tracker stats");
            }
        }
    }
}
