package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import ru.nexora.ai.HotPathProfiler;

/** Measures and feeds actual task execution cost back into a WorkGraph. */
public final class TaskExecution {
    private final WorkGraph graph;
    private final HotPathProfiler profiler;

    public TaskExecution(WorkGraph graph, HotPathProfiler profiler) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.profiler = Objects.requireNonNull(profiler, "profiler");
    }

    public long run(String id, HotPathProfiler.Path path, Runnable action) {
        Objects.requireNonNull(action, "action");
        graph.start(id);
        long started = System.nanoTime();
        try {
            action.run();
            long elapsed = Math.max(0L, System.nanoTime() - started);
            graph.observe(id, elapsed);
            if (path != null) profiler.record(path, elapsed);
            graph.complete(id);
            return elapsed;
        } catch (Throwable error) {
            long elapsed = Math.max(0L, System.nanoTime() - started);
            graph.observe(id, elapsed);
            if (path != null) profiler.record(path, elapsed);
            graph.fail(id);
            throw error;
        }
    }

    public List<String> runReady(List<WorkGraph.NodeView> ready, java.util.function.Function<WorkGraph.NodeView, Runnable> actions) {
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(actions, "actions");
        List<String> completed = new ArrayList<>();
        for (WorkGraph.NodeView node : ready) {
            Runnable action = Objects.requireNonNull(actions.apply(node), "action");
            run(node.id(), null, action);
            completed.add(node.id());
        }
        return List.copyOf(completed);
    }
}
