package ru.nexora.world;

import ru.nexora.runtime.AdaptiveRuntime;
import ru.nexora.runtime.TaskContract;
import ru.nexora.runtime.WorkGraph;

import java.util.Objects;

/** Creates explicit runtime work for chunk lifecycle transitions. */
public final class ChunkWorkScheduler {
    private final WorkGraph graph;

    public ChunkWorkScheduler(WorkGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    public void scheduleLoad(ChunkKey key) {
        String id = "chunk-load-" + key.x() + "-" + key.z();
        if (exists(id)) return;
        graph.add(id, TaskContract.ordered(AdaptiveRuntime.Lane.WORLD, 250_000L));
    }

    public void scheduleUnload(ChunkKey key) {
        String id = "chunk-unload-" + key.x() + "-" + key.z();
        if (exists(id)) return;
        graph.add(id, TaskContract.deferrable(AdaptiveRuntime.Lane.WORLD, 150_000L));
    }

    private boolean exists(String id) {
        return graph.snapshot().stream().anyMatch(n -> n.id().equals(id));
    }
}
