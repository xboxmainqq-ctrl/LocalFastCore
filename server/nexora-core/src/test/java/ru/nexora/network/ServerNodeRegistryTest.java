package ru.nexora.network;

import org.junit.jupiter.api.Test;
import ru.nexora.runtime.LoadSnapshot;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerNodeRegistryTest {
    @Test
    void staleNodesAreNotReturnedAndCanBeExpired() {
        ServerNodeRegistry registry = new ServerNodeRegistry(Duration.ofSeconds(5));
        ServerNode node = node("a", true, 1);
        registry.heartbeat(node, 1_000_000_000L);

        assertTrue(registry.getHealthy("a", 2_000_000_000L).isPresent());
        assertTrue(registry.getHealthy("a", 7_000_001_000L).isEmpty());
        assertEquals(1, registry.expireStale(7_000_001_000L));
    }

    @Test
    void delayedHeartbeatCannotOverwriteNewerNodeState() {
        ServerNodeRegistry registry = new ServerNodeRegistry(Duration.ofSeconds(5));
        ServerNode newer = node("game-1", true, 20);
        ServerNode delayed = node("game-1", false, 1);
        registry.heartbeat(newer, 20_000L);
        registry.heartbeat(delayed, 10_000L);

        assertEquals(newer, registry.getHealthy("game-1", 21_000L).orElseThrow());
        assertEquals(newer, registry.statuses(21_000L).getFirst().node());
    }

    @Test
    void monitoringSnapshotIncludesUnhealthyAndStaleNodesInStableOrder() {
        ServerNodeRegistry registry = new ServerNodeRegistry(Duration.ofNanos(5));
        registry.heartbeat(node("z-survival", true, 1), 10L);
        registry.heartbeat(node("a-lobby", false, 2), 10L);

        List<ServerNodeRegistry.NodeStatus> statuses = registry.statuses(20L);
        assertEquals(List.of("a-lobby", "z-survival"),
                statuses.stream().map(status -> status.node().id()).toList());
        assertFalse(statuses.getFirst().availableForRouting());
        assertTrue(statuses.getFirst().node().healthy() == false);
        assertTrue(statuses.getLast().stale());
        assertFalse(statuses.getLast().availableForRouting());
        assertEquals(10L, statuses.getLast().ageNanos());
    }

    private static ServerNode node(String id, boolean healthy, long players) {
        return new ServerNode(id, "127.0.0.1:19132", "survival", healthy,
                new LoadSnapshot(players, 2, 3, 4, 5, 0.1));
    }
}
