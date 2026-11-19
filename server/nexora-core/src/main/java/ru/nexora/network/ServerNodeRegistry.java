package ru.nexora.network;

import java.time.Duration;
import java.util.*;

/** Tracks server heartbeats and exposes only currently healthy nodes to routing. */
public final class ServerNodeRegistry {
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
