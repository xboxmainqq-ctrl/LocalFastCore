package ru.nexora.world;

import org.junit.jupiter.api.Test;
import ru.nexora.runtime.WorkGraph;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkWorkSchedulerTest {
    @Test
    void repeatedSchedulingDoesNotCreateDuplicateNodes() {
        WorkGraph graph = new WorkGraph();
        ChunkWorkScheduler scheduler = new ChunkWorkScheduler(graph);
        ChunkKey key = new ChunkKey(12, -7);

        scheduler.scheduleLoad(key);
        scheduler.scheduleLoad(key);
        scheduler.scheduleUnload(key);
        scheduler.scheduleUnload(key);

        assertEquals(2, graph.snapshot().size());
    }
}
