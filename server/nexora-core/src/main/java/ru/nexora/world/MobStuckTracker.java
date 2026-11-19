package ru.nexora.world;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Detects repeated lack of positional progress between AI movement decisions.
 * The tracker is bounded and rate-limits recovery requests to avoid path churn.
 */
public final class MobStuckTracker {
    private static final int DEFAULT_MAX_ENTRIES = 5_000;
    private static final int STALLS_BEFORE_RECOVERY = 3;
    private static final long RECOVERY_COOLDOWN_MILLIS = 1_000L;
    private static final double PROGRESS_EPSILON = 0.05;

    private final int maxEntries;
    private final Map<Long, State> states = new LinkedHashMap<>(16, 0.75f, true);

    public MobStuckTracker() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public MobStuckTracker(int maxEntries) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        this.maxEntries = maxEntries;
    }

    /**
     * Returns zero while progressing, otherwise a positive recovery attempt number.
     * A recovery attempt is emitted every second after three consecutive stationary samples.
     */
    public synchronized int observe(long runtimeId, double x, double y, double z, long nowMillis) {
        if (runtimeId <= 0 || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z) || nowMillis < 0) {
            throw new IllegalArgumentException("Invalid mob progress sample");
        }
        State previous = states.get(runtimeId);
        if (previous == null) {
            put(runtimeId, new State(x, y, z, 0, -1, 0));
            return 0;
        }

        boolean progressed = Math.sqrt(square(x - previous.x())
                + square(y - previous.y()) + square(z - previous.z())) > PROGRESS_EPSILON;
        if (progressed) {
            states.put(runtimeId, new State(x, y, z, 0, -1, nowMillis));
            return 0;
        }

        int stalls = previous.stalls() + 1;
        int attempt = previous.recoveryAttempt();
        long lastRecovery = previous.lastRecoveryMillis();
        if (stalls >= STALLS_BEFORE_RECOVERY
                && (lastRecovery < 0 || nowMillis - lastRecovery >= RECOVERY_COOLDOWN_MILLIS)) {
            attempt++;
            lastRecovery = nowMillis;
        }
        states.put(runtimeId, new State(x, y, z, stalls, lastRecovery, nowMillis));
        return attempt;
    }

    public synchronized void reset(long runtimeId) {
        states.remove(runtimeId);
    }

    public synchronized void clear() {
        states.clear();
    }

    public synchronized int size() {
        return states.size();
    }

    private void put(long runtimeId, State state) {
        states.put(runtimeId, state);
        while (states.size() > maxEntries) {
            states.remove(states.keySet().iterator().next());
        }
    }

    private static double square(double value) {
        return value * value;
    }

    private record State(double x, double y, double z, int stalls,
                         long lastRecoveryMillis, long lastSampleMillis) {
        private int recoveryAttempt() {
            return lastRecoveryMillis < 0 ? 0 : 1;
        }
    }
}
