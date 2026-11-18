package ru.nexora.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HotPathProfilerTest {
    @Test
    void keepsOnlyTheBoundedRecentWindow() {
        HotPathProfiler profiler = new HotPathProfiler(3);
        profiler.record(HotPathProfiler.Path.MOB_TARGETING, 10);
        profiler.record(HotPathProfiler.Path.MOB_TARGETING, 20);
        profiler.record(HotPathProfiler.Path.MOB_TARGETING, 30);
        profiler.record(HotPathProfiler.Path.MOB_TARGETING, 40);

        HotPathProfiler.Snapshot snapshot =
                profiler.snapshot(HotPathProfiler.Path.MOB_TARGETING);

        assertEquals(3, snapshot.sampleCount());
        assertEquals(30, snapshot.averageNanos());
        assertEquals(30, snapshot.p50Nanos());
        assertEquals(40, snapshot.p95Nanos());
        assertEquals(40, snapshot.maxNanos());
    }

    @Test
    void unusedPathStartsEmpty() {
        HotPathProfiler profiler = new HotPathProfiler(8);
        assertEquals(0, profiler.snapshot(HotPathProfiler.Path.BEDROCK_INBOUND).sampleCount());
    }
}
