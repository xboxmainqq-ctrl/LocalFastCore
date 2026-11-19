package ru.nexora.runtime;

import java.util.List;
import java.util.Objects;

/**
 * One bounded admission/execution slice of the NEXORA runtime.
 */
public final class RuntimeSlice {
    private final AdaptiveRuntime runtime;

    public RuntimeSlice(AdaptiveRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public List<WorkGraph.NodeView> admit(WorkGraph graph) {
        Objects.requireNonNull(graph, "graph");
        return graph.readyFor(runtime);
    }

    public AdaptiveRuntime.Snapshot snapshot() {
        return runtime.snapshot();
    }
}
