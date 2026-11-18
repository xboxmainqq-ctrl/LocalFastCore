package ru.nexora.gameplay;

import ru.nexora.runtime.TaskContract;
import ru.nexora.runtime.WorkGraph;

import java.util.Objects;

/** Converts gameplay events into explicit, dependency-aware work without coupling modules to the runtime. */
public final class GameplayWorkBridge {
    private final WorkGraph graph;

    public GameplayWorkBridge(WorkGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    public String enqueue(String id, TaskContract contract, String dependency) {
        graph.add(id, contract);
        if (dependency != null) graph.dependsOn(id, dependency);
        return id;
    }

    public void bind(GameplayEventBus bus, GameplayEventBus.EventType type,
                     java.util.function.Function<GameplayEventBus.Event, WorkItem> factory) {
        Objects.requireNonNull(bus, "bus");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        bus.subscribe(type, event -> {
            WorkItem item = Objects.requireNonNull(factory.apply(event), "work item");
            enqueue(item.id(), item.contract(), item.dependency());
        });
    }

    public record WorkItem(String id, TaskContract contract, String dependency) {
        public WorkItem {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(contract, "contract");
        }
    }
}
