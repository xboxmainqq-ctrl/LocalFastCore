package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HandoffCoordinatorTest {
    @Test
    void transferMustBePreparedClaimedThenCommitted() {
        HandoffCoordinator coordinator = new HandoffCoordinator();
        UUID id = UUID.randomUUID();
        coordinator.prepare(new PlayerHandoff(id, 7L, "node-a", "node-b", new byte[] {1}));

        assertTrue(coordinator.claim(id, "node-b"));
        assertFalse(coordinator.claim(id, "node-b"));
        coordinator.commit(id);
        assertEquals(HandoffCoordinator.State.COMMITTED, coordinator.state(id));
    }

    @Test
    void abortPreventsLaterCommit() {
        HandoffCoordinator coordinator = new HandoffCoordinator();
        UUID id = UUID.randomUUID();
        coordinator.prepare(new PlayerHandoff(id, 7L, "node-a", "node-b", new byte[0]));
        coordinator.abort(id);

        assertEquals(HandoffCoordinator.State.ABORTED, coordinator.state(id));
        assertThrows(IllegalStateException.class, () -> coordinator.commit(id));
    }
}
