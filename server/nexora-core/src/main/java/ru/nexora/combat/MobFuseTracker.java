package ru.nexora.combat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks short-lived mob fuse timers. Leaving the trigger range, unloading the mob,
 * or observing a clock rollback resets the fuse instead of preserving stale progress.
 */
public final class MobFuseTracker {
    public static final int MAX_TRACKED_FUSES = 4_096;
    private final Map<Long, Long> startedAtMillis = new LinkedHashMap<>(16, 0.75f, true);

    public synchronized boolean isReadyToDetonate(long runtimeId, boolean withinRange,
                                                   long nowMillis, long fuseMillis) {
        if (runtimeId <= 0 || nowMillis < 0 || fuseMillis <= 0) {
            throw new IllegalArgumentException("Invalid mob fuse observation");
        }
        if (!withinRange) {
            startedAtMillis.remove(runtimeId);
            return false;
        }

        Long startedAt = startedAtMillis.get(runtimeId);
        if (startedAt == null) {
            startedAtMillis.put(runtimeId, nowMillis);
            while (startedAtMillis.size() > MAX_TRACKED_FUSES) {
                Long eldest = startedAtMillis.keySet().iterator().next();
                startedAtMillis.remove(eldest);
            }
            return false;
        }
        if (nowMillis < startedAt) {
            startedAtMillis.put(runtimeId, nowMillis);
            return false;
        }
        if (nowMillis - startedAt < fuseMillis) return false;
        startedAtMillis.remove(runtimeId);
        return true;
    }

    public synchronized void clear(long runtimeId) {
        startedAtMillis.remove(runtimeId);
    }

    public synchronized int trackedFuses() {
        return startedAtMillis.size();
    }
}
