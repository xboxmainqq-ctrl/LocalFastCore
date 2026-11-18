package ru.nexora.combat;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conservative scheduler for generic mob AI. Mobs close to players and mobs with an
 * explicit aggro target always run at full rate; only distant, unprovoked mobs are staggered.
 */
public final class MobAiLoadController {
    public static final double FULL_RATE_DISTANCE = 24.0;
    public static final double FULL_RATE_DISTANCE_SQUARED =
            FULL_RATE_DISTANCE * FULL_RATE_DISTANCE;
    public static final int DEFAULT_FAR_UPDATE_INTERVAL = 4;
    public static final double NEAR_DISTANCE = 24.0;
    public static final double MID_DISTANCE = 48.0;
    public static final double FAR_DISTANCE = 96.0;

    private final int farUpdateInterval;
    private final LongAdder fullRateUpdates = new LongAdder();
    private final LongAdder deferredUpdates = new LongAdder();
    private final LongAdder decisionNanos = new LongAdder();
    private final AtomicLong lastDecisionNanos = new AtomicLong();
    private final AtomicLong maxDecisionNanos = new AtomicLong();
    private volatile long aiBudgetNanos = 2_000_000L;
    private final AtomicLong budgetDeferrals = new AtomicLong();
    private final AtomicLong budgetNanosConsumed = new AtomicLong();

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
        long started = System.nanoTime();
        if (runtimeId < 0 || tick < 0 || Double.isNaN(nearestPlayerDistanceSquared)
                || nearestPlayerDistanceSquared < 0) {
            throw new IllegalArgumentException("Invalid mob scheduling observation");
        }
        int interval = intervalForDistance(nearestPlayerDistanceSquared);
        if (hasActiveTarget || nearestPlayerDistanceSquared <= FULL_RATE_DISTANCE_SQUARED
                || interval == 1 || farUpdateInterval == 1) {
            fullRateUpdates.increment();
            recordDecisionDuration(started);
            return true;
        }
        boolean scheduled = Math.floorMod(runtimeId, interval)
                == Math.floorMod(tick, interval);
        if (scheduled) fullRateUpdates.increment();
        else deferredUpdates.increment();
        recordDecisionDuration(started);
        return scheduled;
    }

    /** Adaptive interval: nearby mobs stay responsive, distant idle mobs are heavily staggered. */
    public int intervalForDistance(double nearestPlayerDistanceSquared) {
        if (Double.isNaN(nearestPlayerDistanceSquared) || nearestPlayerDistanceSquared < 0) {
            throw new IllegalArgumentException("Invalid distance");
        }
        if (nearestPlayerDistanceSquared <= NEAR_DISTANCE * NEAR_DISTANCE) return 1;
        if (nearestPlayerDistanceSquared <= MID_DISTANCE * MID_DISTANCE) return 2;
        if (nearestPlayerDistanceSquared <= FAR_DISTANCE * FAR_DISTANCE) return 3;
        return farUpdateInterval;
    }

    public Priority priority(double distanceSquared, boolean activeTarget, boolean specialBehavior) {
        if (activeTarget || specialBehavior || distanceSquared <= NEAR_DISTANCE * NEAR_DISTANCE) {
            return Priority.HIGH;
        }
        if (distanceSquared <= FAR_DISTANCE * FAR_DISTANCE) return Priority.NORMAL;
        return Priority.LOW;
    }

    public enum Priority {
        HIGH,
        NORMAL,
        LOW
    }

    private void recordDecisionDuration(long started) {
        long elapsed = Math.max(0L, System.nanoTime() - started);
        decisionNanos.add(elapsed);
        lastDecisionNanos.set(elapsed);
        maxDecisionNanos.accumulateAndGet(elapsed, Math::max);
    }

    public BudgetStats budgetStats() {
        return new BudgetStats(aiBudgetNanos, budgetNanosConsumed.get(), budgetDeferrals.get());
    }

    public record BudgetStats(long budgetNanos, long consumedNanos, long deferredTasks) {}

    public Stats stats() {
        return new Stats(fullRateUpdates.sum(), deferredUpdates.sum(), farUpdateInterval,
                decisionNanos.sum(), lastDecisionNanos.get(), maxDecisionNanos.get());
    }

    public record Stats(long fullRateUpdates, long deferredUpdates, int farUpdateInterval,
                        long totalDecisionNanos, long lastDecisionNanos, long maxDecisionNanos) {
        public long totalDecisions() {
            return fullRateUpdates + deferredUpdates;
        }

        public String summary() {
            return "fullRate=" + fullRateUpdates + ", deferred=" + deferredUpdates
                    + ", farInterval=" + farUpdateInterval + " ticks"
                    + ", schedulerDecisionAvg="
                    + (totalDecisions() == 0 ? 0 : totalDecisionNanos / totalDecisions()) + " ns"
                    + ", last=" + lastDecisionNanos + " ns, max=" + maxDecisionNanos + " ns";
        }
    }
}
