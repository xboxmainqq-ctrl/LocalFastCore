package ru.nexora.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void concurrentStopCallsRemainIdempotent() throws InterruptedException {
        ServerReadiness readiness = new ServerReadiness();
        readiness.markReady();
        readiness.beginShutdown();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] callers = new Thread[32];

        for (int i = 0; i < callers.length; i++) {
            callers[i] = new Thread(() -> {
                try {
                    readiness.markStopped();
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });
            callers[i].start();
        }
        for (Thread caller : callers) caller.join();

        assertNull(failure.get());
        assertEquals(ServerReadiness.State.STOPPED, readiness.state());
    }
}
