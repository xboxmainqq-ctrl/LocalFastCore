package ru.nexora.runtime;

import java.util.EnumMap;
import java.util.Map;

/**
 * Dynamic per-lane budget controller.
 *
 * <p>Budgets are adjusted gradually from observed pressure; critical lanes
 * retain a protected floor and background work absorbs most reductions.</p>
 */
public final class AdaptiveBudget {
    private final Map<AdaptiveRuntime.Lane, Long> budgets = new EnumMap<>(AdaptiveRuntime.Lane.class);
    private final Map<AdaptiveRuntime.Lane, Long> minimums = new EnumMap<>(AdaptiveRuntime.Lane.class);

    public AdaptiveBudget(long defaultBudgetNanos) {
        if (defaultBudgetNanos <= 0) throw new IllegalArgumentException("defaultBudgetNanos <= 0");
        for (AdaptiveRuntime.Lane lane : AdaptiveRuntime.Lane.values()) {
            budgets.put(lane, defaultBudgetNanos);
            minimums.put(lane, Math.max(1_000L, defaultBudgetNanos / 20L));
        }
    }

    public synchronized long budget(AdaptiveRuntime.Lane lane) {
        return budgets.get(lane);
    }

    public synchronized void rebalance(double pressure) {
        if (pressure < 0.0 || pressure > 1.0) throw new IllegalArgumentException("pressure must be 0..1");
        for (AdaptiveRuntime.Lane lane : AdaptiveRuntime.Lane.values()) {
            long current = budgets.get(lane);
            long min = minimums.get(lane);
            double targetFactor;
            if (lane == AdaptiveRuntime.Lane.PLAYER || lane == AdaptiveRuntime.Lane.WORLD) {
                targetFactor = pressure > 0.85 ? 0.98 : 1.01;
            } else if (lane == AdaptiveRuntime.Lane.BACKGROUND) {
                targetFactor = pressure > 0.70 ? 0.70 : 1.03;
            } else {
                targetFactor = pressure > 0.90 ? 0.85 : 1.02;
            }
            long next = Math.max(min, Math.min(current + current / 20L, (long) (current * targetFactor)));
            budgets.put(lane, next);
        }
    }
}
