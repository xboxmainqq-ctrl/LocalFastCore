package ru.nexora.gameplay;

import ru.nexora.runtime.TaskContract;
import ru.nexora.runtime.WorkGraph;

import java.util.List;
import java.util.Objects;

/**
 * High-level gameplay composition graph.
 *
 * <p>Gameplay systems can compose RPG, magic, combat, effects and other
 * mechanics without coupling them to the runtime scheduler.</p>
 */
public final class GameplayGraph {
    private final WorkGraph workGraph = new WorkGraph();

    public GameplayGraph action(String id, TaskContract contract) {
        Objects.requireNonNull(contract, "contract");
        workGraph.add(id, contract.lane(), contract.critical());
        workGraph.estimate(id, contract.estimatedNanos());
        return this;
    }

    public GameplayGraph after(String action, String dependency) {
        workGraph.dependsOn(action, dependency);
        return this;
    }

    public GameplayGraph validate() {
        workGraph.validate();
        return this;
    }

    public List<WorkGraph.NodeView> ready() {
        return workGraph.ready();
    }

    public WorkGraph graph() {
        return workGraph;
    }
}
