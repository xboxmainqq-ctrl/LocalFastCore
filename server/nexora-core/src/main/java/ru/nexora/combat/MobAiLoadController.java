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
    private volatile boolean adaptiveBudget = true;
    private volatile long targetTickNanos = 50_000_000L;
    private volatile long minBudgetNanos = 500_000L;
    private volatile long maxBudgetNanos = 5_000_000L;
    private volatile double smoothedTickLoad = 0.0;
    private final AtomicLong budgetDeferrals = new AtomicLong();
    private final AtomicLong budgetNanosConsumed = new AtomicLong();
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> starvationTicks = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int starvationPromotionTicks = 8;

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

    public synchronized void configureAdaptiveBudget(boolean enabled, long targetTickNanos, long minBudgetNanos, long maxBudgetNanos) {
        if (targetTickNanos <= 0 || minBudgetNanos < 0 || maxBudgetNanos < minBudgetNanos) throw new IllegalArgumentException("Invalid adaptive AI budget configuration");
        this.adaptiveBudget = enabled; this.targetTickNanos = targetTickNanos; this.minBudgetNanos = minBudgetNanos; this.maxBudgetNanos = maxBudgetNanos;
        this.aiBudgetNanos = Math.max(minBudgetNanos, Math.min(maxBudgetNanos, aiBudgetNanos));
    }

    public synchronized long updateAdaptiveBudget(long worldTickNanos) {
        if (worldTickNanos < 0) throw new IllegalArgumentException("worldTickNanos must be non-negative");
        if (!adaptiveBudget) return aiBudgetNanos;
        double load = (double) worldTickNanos / targetTickNanos;
        smoothedTickLoad = smoothedTickLoad == 0.0 ? load : smoothedTickLoad * 0.8 + load * 0.2;
        if (smoothedTickLoad > 1.05) aiBudgetNanos = Math.max(minBudgetNanos, (long)(aiBudgetNanos * 0.90));
        else if (smoothedTickLoad < 0.85) aiBudgetNanos = Math.min(maxBudgetNanos, (long)(aiBudgetNanos * 1.05));
        return aiBudgetNanos;
    }

    public double smoothedTickLoad() { return smoothedTickLoad; }

    public void setStarvationPromotionTicks(int ticks) {
        if (ticks < 1 || ticks > 1000) throw new IllegalArgumentException("ticks must be between 1 and 1000");
        starvationPromotionTicks = ticks;
    }

    public int starvationTicks(long runtimeId) {
        return starvationTicks.getOrDefault(runtimeId, 0);
    }

    public Priority effectivePriority(long runtimeId, Priority base) {
        if (runtimeId < 0 || base == null) throw new IllegalArgumentException("Invalid starvation priority input");
        int waited = starvationTicks.getOrDefault(runtimeId, 0);
        if (waited >= starvationPromotionTicks) return Priority.HIGH;
        if (waited >= Math.max(1, starvationPromotionTicks / 2) && base == Priority.LOW) return Priority.NORMAL;
        return base;
    }

    public void recordDeferred(long runtimeId) {
        if (runtimeId < 0) throw new IllegalArgumentException("runtimeId must be non-negative");
        starvationTicks.merge(runtimeId, 1, Integer::sum);
    }

    public void recordScheduled(long runtimeId) {
        starvationTicks.remove(runtimeId);
    }

    public void remove(long runtimeId) {
        starvationTicks.remove(runtimeId);
    }

    public long aiBudgetNanos() {
        return aiBudgetNanos;
    }

    public void setAiBudgetNanos(long budgetNanos) {
        if (budgetNanos < 1 || budgetNanos > 50_000_000L) {
            throw new IllegalArgumentException("AI budget must be between 1 ns and 50 ms");
        }
        aiBudgetNanos = budgetNanos;
    }

    public boolean admit(
            long runtimeId,
            long tick,
            double distanceSquared,
            boolean activeTarget,
            Priority priority,
            long consumedNanos
    ) {
        if (runtimeId < 0 || tick < 0 || Double.isNaN(distanceSquared)
                || distanceSquared < 0 || consumedNanos < 0) {
            throw new IllegalArgumentException("Invalid AI budget admission observation");
        }
        java.util.Objects.requireNonNull(priority, "priority");
        if (activeTarget || priority == Priority.HIGH || consumedNanos < aiBudgetNanos) {
            return true;
        }
        budgetDeferrals.incrementAndGet();
        return false;
    }

    public void recordBudgetNanos(long elapsedNanos) {
        if (elapsedNanos < 0) throw new IllegalArgumentException("elapsedNanos must be non-negative");
        budgetNanosConsumed.updateAndGet(current ->
                current > Long.MAX_VALUE - elapsedNanos ? Long.MAX_VALUE : current + elapsedNanos);
    }

    public long budgetDeferrals() {
        return budgetDeferrals.get();
    }

    public long budgetNanosConsumed() {
        return budgetNanosConsumed.get();
    }

    public BudgetStats budgetStats() {
        return new BudgetStats(aiBudgetNanos, budgetNanosConsumed.get(), budgetDeferrals.get(), adaptiveBudget, smoothedTickLoad);
    }

    public record BudgetStats(
            long budgetNanos,
            long consumedNanos,
            long deferredTasks,
            boolean adaptive,
            double smoothedTickLoad
    ) {
        public BudgetStats {
            if (budgetNanos < 0 || consumedNanos < 0 || deferredTasks < 0
                    || !Double.isFinite(smoothedTickLoad) || smoothedTickLoad < 0) {
                throw new IllegalArgumentException("Invalid Mob AI budget statistics");
            }
        }

        public String summary() {
            return "budget=" + budgetNanos + " ns, consumed=" + consumedNanos
                    + " ns, deferred=" + deferredTasks + ", adaptive=" + adaptive
                    + ", smoothedTickLoad=" + String.format(java.util.Locale.ROOT, "%.3f", smoothedTickLoad);
        }
    }

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
