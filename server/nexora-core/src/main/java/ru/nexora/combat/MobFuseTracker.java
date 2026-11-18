package ru.nexora.combat;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks short-lived mob fuse timers. Leaving the trigger range, unloading the mob,
 * or observing a clock rollback resets the fuse instead of preserving stale progress.
 */
public final class MobFuseTracker {
    private final Map<Long, Long> startedAtMillis = new HashMap<>();

    public synchronized boolean isReadyToDetonate(long runtimeId, boolean withinRange,
                                                   long nowMillis, long fuseMillis) {
        if (runtimeId <= 0 || nowMillis < 0 || fuseMillis <= 0) {
            throw new IllegalArgumentException("Invalid mob fuse observation");
        }
        if (!withinRange) {
            startedAtMillis.remove(runtimeId);
            return false;
        }

        Long startedAt = startedAtMillis.putIfAbsent(runtimeId, nowMillis);
        if (startedAt == null) return false;
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
