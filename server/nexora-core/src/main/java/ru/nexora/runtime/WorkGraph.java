package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Dependency-aware ordering graph for NEXORA runtime work. */
public final class WorkGraph {
    public enum State { PENDING, READY, RUNNING, COMPLETED, DEFERRED, FAILED }

    private final Map<String, Node> nodes = new HashMap<>();

    public synchronized WorkGraph add(String id, TaskContract contract) {
        requireId(id);
        Objects.requireNonNull(contract, "contract");
        if (nodes.containsKey(id)) throw new IllegalArgumentException("Duplicate work node: " + id);
        nodes.put(id, new Node(id, contract));
        return this;
    }

    /** Compatibility overload for low-level callers. */
    public synchronized WorkGraph add(String id, AdaptiveRuntime.Lane lane, boolean critical) {
        return add(id, new TaskContract(lane, critical, !critical, critical, 0L));
    }

    public synchronized WorkGraph dependsOn(String id, String dependencyId) {
        Node node = requireNode(id), dep = requireNode(dependencyId);
        if (id.equals(dependencyId)) throw new IllegalArgumentException("Self dependency: " + id);
        node.dependencies.add(dep.id);
        return this;
    }

    public synchronized List<NodeView> ready() {
        List<NodeView> result = new ArrayList<>();
        for (Node n : nodes.values()) {
            if (n.state == State.PENDING && dependenciesCompleted(n)) {
                n.state = State.READY;
                result.add(view(n));
            }
        }
        return List.copyOf(result);
    }

    public synchronized List<NodeView> readyFor(AdaptiveRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        List<NodeView> result = new ArrayList<>();
        for (Node n : nodes.values()) {
            if (n.state != State.PENDING || !dependenciesCompleted(n)) continue;
            long estimate = n.observedP95Nanos > 0 ? n.observedP95Nanos : Math.max(1L, n.contract.estimatedNanos());
            if (runtime.admit(n.contract.lane(), estimate, n.contract.critical()) == AdaptiveRuntime.Admission.ALLOW) {
                n.state = State.READY;
                result.add(view(n));
            }
        }
        return List.copyOf(result);
    }

    public synchronized WorkGraph observe(String id, long elapsedNanos) {
        Node n = requireNode(id);
        if (elapsedNanos < 0) throw new IllegalArgumentException("elapsedNanos < 0");
        n.observedP95Nanos = n.observedP95Nanos == 0L
                ? elapsedNanos
                : Math.max(elapsedNanos, (n.observedP95Nanos * 9L) / 10L);
        return this;
    }

    public synchronized void start(String id) { transition(id, State.READY, State.RUNNING); }
    public synchronized void complete(String id) { transition(id, State.RUNNING, State.COMPLETED); }
    public synchronized void fail(String id) { transition(id, State.RUNNING, State.FAILED); }

    public synchronized void defer(String id) {
        Node n = requireNode(id);
        if (!n.contract.deferrable()) throw new IllegalStateException("Task is not deferrable: " + id);
        if (n.state != State.READY && n.state != State.PENDING) throw new IllegalStateException("Node cannot be deferred: " + id);
        n.state = State.DEFERRED;
    }

    public synchronized void reopenDeferred() {
        for (Node n : nodes.values()) if (n.state == State.DEFERRED) n.state = State.PENDING;
    }

    public synchronized List<NodeView> snapshot() { return nodes.values().stream().map(this::view).toList(); }

    public synchronized void validate() {
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (Node n : nodes.values()) detectCycle(n.id, visiting, visited);
    }

    private void detectCycle(String id, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalStateException("Work graph cycle at: " + id);
        for (String d : requireNode(id).dependencies) detectCycle(d, visiting, visited);
        visiting.remove(id); visited.add(id);
    }

    private boolean dependenciesCompleted(Node n) {
        for (String d : n.dependencies) if (requireNode(d).state != State.COMPLETED) return false;
        return true;
    }

    private void transition(String id, State expected, State next) {
        Node n = requireNode(id);
        if (n.state != expected) throw new IllegalStateException("Invalid state for " + id + ": " + n.state);
        n.state = next;
    }

    private Node requireNode(String id) {
        requireId(id);
        Node n = nodes.get(id);
        if (n == null) throw new IllegalArgumentException("Unknown work node: " + id);
        return n;
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Work node id is blank");
    }

    private NodeView view(Node n) {
        return new NodeView(n.id, n.contract, n.state, n.observedP95Nanos, List.copyOf(n.dependencies));
    }

    private static final class Node {
        final String id;
        final TaskContract contract;
        final Set<String> dependencies = new HashSet<>();
        long observedP95Nanos;
        State state = State.PENDING;
        Node(String id, TaskContract contract) { this.id = id; this.contract = contract; }
    }

    public record NodeView(String id, TaskContract contract, State state, long observedP95Nanos, List<String> dependencies) {}
}
