package ru.nexora.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JvmResourceSnapshotTest {
    @Test
    void capturesConsistentHeapAndThreadCounters() {
        JvmResourceSnapshot snapshot = JvmResourceSnapshot.capture();
        assertTrue(snapshot.heapUsedBytes() >= 0);
        assertTrue(snapshot.heapCommittedBytes() >= snapshot.heapUsedBytes());
        assertTrue(snapshot.nonHeapUsedBytes() >= 0);
        assertTrue(snapshot.nonHeapCommittedBytes() >= snapshot.nonHeapUsedBytes());
        assertTrue(snapshot.liveThreadCount() > 0);
        assertTrue(snapshot.peakThreadCount() >= snapshot.liveThreadCount());
        assertTrue(snapshot.daemonThreadCount() <= snapshot.liveThreadCount());
        assertTrue(snapshot.availableProcessors() >= 1);
        assertTrue(snapshot.heapUsageRatio() == -1.0
                || Double.isFinite(snapshot.heapUsageRatio()));
    }

    @Test
    void rejectsImpossibleThreadAndMemoryValues() {
        assertThrows(IllegalArgumentException.class, () -> new JvmResourceSnapshot(
                1, 1, 1, 0, 0, 2, 1, 0, 1, -1.0));
        assertThrows(IllegalArgumentException.class, () -> new JvmResourceSnapshot(
                2, 1, 10, 0, 0, 1, 1, 0, 1, -1.0));
        assertThrows(IllegalArgumentException.class, () -> new JvmResourceSnapshot(
                1, 11, 10, 0, 0, 1, 1, 0, 1, -1.0));
        assertThrows(IllegalArgumentException.class, () -> new JvmResourceSnapshot(
                0, 0, 10, 2, 1, 1, 1, 0, 1, -1.0));
    }
}
