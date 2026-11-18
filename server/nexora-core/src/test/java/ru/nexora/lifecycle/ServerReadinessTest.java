package ru.nexora.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerReadinessTest {
    @Test
    void acceptsConnectionsOnlyAfterReady() {
        ServerReadiness readiness = new ServerReadiness();
        assertEquals(ServerReadiness.State.STARTING, readiness.state());
        assertFalse(readiness.isAcceptingConnections());
        readiness.markReady();
        assertEquals(ServerReadiness.State.READY, readiness.state());
        assertTrue(readiness.isAcceptingConnections());
    }

    @Test
    void shutdownImmediatelyClosesAdmissionAndIsIdempotent() {
        ServerReadiness readiness = new ServerReadiness();
        readiness.markReady();
        readiness.beginShutdown();
        readiness.beginShutdown();
        assertEquals(ServerReadiness.State.STOPPING, readiness.state());
        assertFalse(readiness.isAcceptingConnections());
        readiness.markStopped();
        readiness.markStopped();
        assertEquals(ServerReadiness.State.STOPPED, readiness.state());
    }

    @Test
    void startupRollbackCanStopBeforeReady() {
        ServerReadiness readiness = new ServerReadiness();
        readiness.beginShutdown();
        readiness.markStopped();
        assertEquals(ServerReadiness.State.STOPPED, readiness.state());
        assertThrows(IllegalStateException.class, readiness::markReady);
    }

    @Test
    void rejectsInvalidReadyTransition() {
        ServerReadiness readiness = new ServerReadiness();
        readiness.markReady();
        assertThrows(IllegalStateException.class, readiness::markReady);
    }
}
