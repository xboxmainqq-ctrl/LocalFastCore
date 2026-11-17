package ru.nexora.combat;

import java.util.concurrent.atomic.LongAdder;

/**
 * Conservative scheduler for generic mob AI. Mobs close to players and mobs with an
 * explicit aggro target always run at full rate; only distant, unprovoked mobs are staggered.
 */
public final class MobAiLoadController {
    public static final double FULL_RATE_DISTANCE = 24.0;
    public static final double FULL_RATE_DISTANCE_SQUARED =
            FULL_RATE_DISTANCE * FULL_RATE_DISTANCE;
    public static final int DEFAULT_FAR_UPDATE_INTERVAL = 2;

    private final int farUpdateInterval;
    private final LongAdder fullRateUpdates = new LongAdder();
    private final LongAdder deferredUpdates = new LongAdder();

    public MobAiLoadController() {
        this(DEFAULT_FAR_UPDATE_INTERVAL);
    }

    public MobAiLoadController(int farUpdateInterval) {
        if (farUpdateInterval < 1 || farUpdateInterval > 20) {
            throw new IllegalArgumentException("Far update interval must be between 1 and 20");
        }
        this.farUpdateInterval = farUpdateInterval;
    }

    /**
     * Returns whether generic AI should run on this tick. Special mob behavior is deliberately
     * handled by the caller before consulting this controller.
     */
    public boolean shouldUpdate(long runtimeId, long tick, double nearestPlayerDistanceSquared,
                                boolean hasActiveTarget) {
        if (runtimeId < 0 || tick < 0 || Double.isNaN(nearestPlayerDistanceSquared)
                || nearestPlayerDistanceSquared < 0) {
            throw new IllegalArgumentException("Invalid mob scheduling observation");
        }
        if (hasActiveTarget || nearestPlayerDistanceSquared <= FULL_RATE_DISTANCE_SQUARED
                || farUpdateInterval == 1) {
            fullRateUpdates.increment();
            return true;
        }
        boolean scheduled = Math.floorMod(runtimeId, farUpdateInterval)
                == Math.floorMod(tick, farUpdateInterval);
        if (scheduled) fullRateUpdates.increment();
        else deferredUpdates.increment();
        return scheduled;
    }

    public Stats stats() {
        return new Stats(fullRateUpdates.sum(), deferredUpdates.sum(), farUpdateInterval);
    }

    public record Stats(long fullRateUpdates, long deferredUpdates, int farUpdateInterval) {
        public long totalDecisions() {
            return fullRateUpdates + deferredUpdates;
        }

        public String summary() {
            return "fullRate=" + fullRateUpdates + ", deferred=" + deferredUpdates
                    + ", farInterval=" + farUpdateInterval + " ticks";
        }
    }
}
