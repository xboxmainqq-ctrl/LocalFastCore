package ru.nexora.server;

import org.junit.jupiter.api.Test;
import ru.nexora.runtime.*;
import ru.nexora.world.WorldState;

import static org.junit.jupiter.api.Assertions.*;

class ServerLoopTest {
    @Test
    void tickHasDeterministicWorldThenAdmissionBoundary() {
        AdaptiveRuntime runtime = new AdaptiveRuntime(10_000_000L, 1_000_000L);
        WorldState world = new WorldState(new StateOwnership());
        ServerLoop loop = new ServerLoop(runtime, world);
        WorkGraph graph = new WorkGraph()
                .add("player", TaskContract.critical(AdaptiveRuntime.Lane.PLAYER, 100_000L));

        ServerLoop.TickResult result = loop.tick(1L, graph);

        assertEquals(1L, world.worldTime());
        assertEquals(1, result.admittedTasks());
        assertTrue(result.runtime().lastTickNanos() >= 0);
    }
}
