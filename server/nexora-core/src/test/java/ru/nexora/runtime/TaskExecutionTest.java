package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import ru.nexora.ai.HotPathProfiler;

import static org.junit.jupiter.api.Assertions.*;

class TaskExecutionTest {
    @Test
    void executionFeedsObservedCostBackIntoGraph() {
        WorkGraph graph = new WorkGraph()
                .add("x", TaskContract.critical(AdaptiveRuntime.Lane.PLAYER, 1_000L));
        HotPathProfiler profiler = new HotPathProfiler(8);
        TaskExecution execution = new TaskExecution(graph, profiler);

        graph.ready();
        long elapsed = execution.run("x", HotPathProfiler.Path.WORLD_ENTITY_TICK, () -> {});

        assertTrue(elapsed >= 0);
        WorkGraph.NodeView node = graph.snapshot().get(0);
        assertTrue(node.observedP95Nanos() >= 0);
        assertEquals(1, profiler.snapshot(HotPathProfiler.Path.WORLD_ENTITY_TICK).sampleCount());
        assertEquals(WorkGraph.State.COMPLETED, node.state());
    }

    @Test
    void failedExecutionIsRecordedAndMarkedFailed() {
        WorkGraph graph = new WorkGraph()
                .add("x", TaskContract.critical(AdaptiveRuntime.Lane.PLAYER, 1_000L));
        TaskExecution execution = new TaskExecution(graph, new HotPathProfiler());

        graph.ready();
        assertThrows(IllegalStateException.class,
                () -> execution.run("x", null, () -> { throw new IllegalStateException("boom"); }));
        assertEquals(WorkGraph.State.FAILED, graph.snapshot().get(0).state());
    }
}
