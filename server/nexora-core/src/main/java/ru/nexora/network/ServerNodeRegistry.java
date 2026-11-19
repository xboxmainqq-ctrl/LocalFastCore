package ru.nexora.network;

import java.time.Duration;
import java.util.*;

/** Tracks server heartbeats and exposes only currently healthy nodes to routing and monitoring. */
public final class ServerNodeRegistry {
    public record NodeStatus(ServerNode node, long lastHeartbeatNanos, long ageNanos, boolean stale) {
        public NodeStatus {
            Objects.requireNonNull(node, "node");
            if (ageNanos < 0) throw new IllegalArgumentException("ageNanos must not be negative");
        }

        public boolean availableForRouting() {
            return node.healthy() && !stale;
        }
    }

    private final Map<String, Entry> nodes = new HashMap<>();
    private final long staleAfterNanos;

    public ServerNodeRegistry(Duration staleAfter) {
        Objects.requireNonNull(staleAfter, "staleAfter");
        if (staleAfter.isZero() || staleAfter.isNegative())
            throw new IllegalArgumentException("staleAfter must be positive");
        this.staleAfterNanos = staleAfter.toNanos();
    }

    public synchronized void heartbeat(ServerNode node, long nowNanos) {
        Objects.requireNonNull(node, "node");
        Entry previous = nodes.get(node.id());
        if (previous != null && nowNanos - previous.lastHeartbeatNanos() < 0) {
            // A delayed heartbeat must never overwrite a newer health/load snapshot.
            return;
        }
        nodes.put(node.id(), new Entry(node, nowNanos));
    }

    public synchronized Optional<ServerNode> getHealthy(String id, long nowNanos) {
        Entry entry = nodes.get(id);
        if (entry == null || !entry.node().healthy() || isStale(entry, nowNanos)) return Optional.empty();
        return Optional.of(entry.node());
    }

    public synchronized List<ServerNode> healthyNodes(long nowNanos) {
        List<ServerNode> result = new ArrayList<>();
        for (Entry entry : nodes.values()) {
            if (entry.node().healthy() && !isStale(entry, nowNanos)) result.add(entry.node());
        }
        result.sort(Comparator.comparing(ServerNode::id));
        return List.copyOf(result);
    }

    /** Returns a stable, ID-sorted monitoring snapshot, including unhealthy and stale nodes. */
    public synchronized List<NodeStatus> statuses(long nowNanos) {
        List<NodeStatus> result = new ArrayList<>(nodes.size());
        for (Entry entry : nodes.values()) {
            long elapsed = nowNanos - entry.lastHeartbeatNanos();
            long age = Math.max(0L, elapsed);
            result.add(new NodeStatus(entry.node(), entry.lastHeartbeatNanos(), age,
                    elapsed > staleAfterNanos));
        }
        result.sort(Comparator.comparing(status -> status.node().id()));
        return List.copyOf(result);
    }

    public synchronized int expireStale(long nowNanos) {
        int before = nodes.size();
        nodes.entrySet().removeIf(e -> isStale(e.getValue(), nowNanos));
        return before - nodes.size();
    }

    private boolean isStale(Entry entry, long nowNanos) {
        return nowNanos - entry.lastHeartbeatNanos() > staleAfterNanos;
    }

    private record Entry(ServerNode node, long lastHeartbeatNanos) {}
}
