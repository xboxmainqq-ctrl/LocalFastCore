package ru.nexora.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    void recordsIndependentPathsConcurrentlyWithoutLosingSamples() throws Exception {
        HotPathProfiler profiler = new HotPathProfiler(512);
        ExecutorService executor = Executors.newFixedThreadPool(HotPathProfiler.Path.values().length);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (HotPathProfiler.Path path : HotPathProfiler.Path.values()) {
                tasks.add(executor.submit(() -> {
                    for (int i = 0; i < 300; i++) profiler.record(path, i + 1L);
                }));
            }
            for (Future<?> task : tasks) task.get();
        } finally {
            executor.shutdownNow();
        }

        for (HotPathProfiler.Path path : HotPathProfiler.Path.values()) {
            assertEquals(300, profiler.snapshot(path).sampleCount());
        }
    }

    @Test
    void unusedPathStartsEmpty() {
        HotPathProfiler profiler = new HotPathProfiler(8);
        assertEquals(0, profiler.snapshot(HotPathProfiler.Path.BEDROCK_INBOUND).sampleCount());
    }
}
