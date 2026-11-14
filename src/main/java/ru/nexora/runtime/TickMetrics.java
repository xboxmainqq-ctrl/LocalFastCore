package ru.nexora.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-overhead, allocation-free-per-tick timing counters for the game loop.
 *
 * <p>Writers update atomic counters; readers allocate only when requesting a
 * snapshot. Durations are measured with the monotonic {@link System#nanoTime()}
 * clock. This class deliberately keeps no unbounded history.</p>
 */
public final class TickMetrics {
    private final AtomicLong tickCount = new AtomicLong();
    private final AtomicLong totalDurationNanos = new AtomicLong();
    private final AtomicLong lastDurationNanos = new AtomicLong();
    private final AtomicLong maxDurationNanos = new AtomicLong();
    private final AtomicLong overBudgetTicks = new AtomicLong();

    void record(long durationNanos, long budgetNanos) {
        long duration = Math.max(0L, durationNanos);
        long budget = Math.max(1L, budgetNanos);
        tickCount.incrementAndGet();
        totalDurationNanos.addAndGet(duration);
        lastDurationNanos.set(duration);
        updateMaximum(duration);
        if (duration > budget) {
            overBudgetTicks.incrementAndGet();
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                tickCount.get(),
                totalDurationNanos.get(),
                lastDurationNanos.get(),
                maxDurationNanos.get(),
                overBudgetTicks.get()
        );
    }

    private void updateMaximum(long candidate) {
        long current;
        do {
            current = maxDurationNanos.get();
            if (candidate <= current) {
                return;
            }
        } while (!maxDurationNanos.compareAndSet(current, candidate));
    }

    public record Snapshot(
            long tickCount,
            long totalDurationNanos,
            long lastDurationNanos,
            long maxDurationNanos,
            long overBudgetTicks
    ) {
        public Snapshot {
            if (tickCount < 0 || totalDurationNanos < 0 || lastDurationNanos < 0
                    || maxDurationNanos < 0 || overBudgetTicks < 0
                    || overBudgetTicks > tickCount) {
                throw new IllegalArgumentException("Invalid tick metrics snapshot");
            }
        }

        public long averageDurationNanos() {
            return tickCount == 0 ? 0L : totalDurationNanos / tickCount;
        }
    }
}
