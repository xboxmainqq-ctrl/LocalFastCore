package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TickMetricsTest {
    @Test
    void tracksCountTotalLastMaximumAndBudgetOverruns() {
        TickMetrics metrics = new TickMetrics();
        metrics.record(10, 20);
        metrics.record(30, 20);
        metrics.record(15, 20);

        TickMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(3, snapshot.tickCount());
        assertEquals(55, snapshot.totalDurationNanos());
        assertEquals(15, snapshot.lastDurationNanos());
        assertEquals(30, snapshot.maxDurationNanos());
        assertEquals(1, snapshot.overBudgetTicks());
        assertEquals(18, snapshot.averageDurationNanos());
    }

    @Test
    void emptySnapshotHasZeroAverageAndNoHistory() {
        TickMetrics.Snapshot snapshot = new TickMetrics().snapshot();
        assertEquals(0, snapshot.tickCount());
        assertEquals(0, snapshot.averageDurationNanos());
        assertEquals(0, snapshot.maxDurationNanos());
    }

    @Test
    void rejectsImpossibleSnapshots() {
        assertThrows(IllegalArgumentException.class,
                () -> new TickMetrics.Snapshot(1, 0, 0, 0, 2));
    }
}
