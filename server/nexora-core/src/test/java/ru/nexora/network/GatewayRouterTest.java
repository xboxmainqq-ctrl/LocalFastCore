package ru.nexora.network;

import org.junit.jupiter.api.Test;
import ru.nexora.runtime.LoadSnapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GatewayRouterTest {
    @Test
    void choosesLeastLoadedHealthyNodeForRequestedProfile() {
        GatewayRouter router = new GatewayRouter(new ServerLoadPolicy(80, 100));
        ServerNode busy = new ServerNode("busy", "127.0.0.1:19133", "survival", true,
                new LoadSnapshot(75, 300, 900, 1000, 100_000, 0.80));
        ServerNode free = new ServerNode("free", "127.0.0.1:19134", "survival", true,
                new LoadSnapshot(20, 100, 200, 100, 10_000, 0.20));
        ServerNode wrongProfile = new ServerNode("rpg", "127.0.0.1:19135", "rpg", true,
                new LoadSnapshot(1, 1, 1, 1, 1, 0.01));
        ServerNode unhealthy = new ServerNode("down", "127.0.0.1:19136", "survival", false,
                new LoadSnapshot(0, 0, 0, 0, 0, 0.0));

        assertEquals("free", router.select(List.of(busy, free, wrongProfile, unhealthy), "survival")
                .orElseThrow().id());
    }

    @Test
    void returnsEmptyWhenNoNodeCanAcceptPlayers() {
        GatewayRouter router = new GatewayRouter(new ServerLoadPolicy(80, 100));
        ServerNode full = new ServerNode("full", "127.0.0.1:19133", "survival", true,
                new LoadSnapshot(100, 500, 1000, 5000, 500_000, 0.99));

        assertTrue(router.select(List.of(full), "survival").isEmpty());
    }
}
