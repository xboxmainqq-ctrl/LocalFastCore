package ru.nexora.network;

import org.junit.jupiter.api.Test;
import ru.nexora.runtime.LoadSnapshot;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ServerNodeRegistryTest {
    @Test
    void staleNodesAreNotReturnedAndCanBeExpired() {
        ServerNodeRegistry registry = new ServerNodeRegistry(Duration.ofSeconds(5));
        ServerNode node = new ServerNode("a", "127.0.0.1:19132", "survival", true,
                new LoadSnapshot(1, 2, 3, 4, 5, 0.1));
        registry.heartbeat(node, 1_000_000_000L);

        assertTrue(registry.getHealthy("a", 2_000_000_000L).isPresent());
        assertTrue(registry.getHealthy("a", 7_000_001_000L).isEmpty());
        assertEquals(1, registry.expireStale(7_000_001_000L));
    }
}
