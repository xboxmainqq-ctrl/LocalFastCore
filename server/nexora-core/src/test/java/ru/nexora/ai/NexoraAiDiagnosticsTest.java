package ru.nexora.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NexoraAiDiagnosticsTest {
    @Test
    void inspectReturnsHealthyRuntimeSnapshotOnFreshInstance() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();

        NexoraAiDiagnostics.Snapshot snapshot = diagnostics.inspect();

        assertNotNull(snapshot.health());
        assertTrue(snapshot.uptimeMillis() >= 0);
        assertTrue(snapshot.heapUsedBytes() >= 0);
        assertTrue(snapshot.availableProcessors() >= 1);
        assertEquals(0, snapshot.recordedFailures());
        assertEquals("none", snapshot.lastFailure());
    }

    @Test
    void reportedFailureIsCountedAndChangesHealthToWarning() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();

        diagnostics.recordFailure(new IllegalStateException("world tick failed"));

        NexoraAiDiagnostics.Snapshot snapshot = diagnostics.inspect();
        assertEquals(NexoraAiDiagnostics.Health.WARNING, snapshot.health());
        assertEquals(1, snapshot.recordedFailures());
        assertEquals("IllegalStateException: world tick failed", snapshot.lastFailure());
    }

    @Test
    void failureDescriptionIsBoundedAndThrowableIsNotRetained() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        diagnostics.recordFailure(new RuntimeException("x".repeat(1000)));

        assertTrue(diagnostics.inspect().lastFailure().length() <= 240);
    }

    @Test
    void rejectsNullFailure() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        assertThrows(NullPointerException.class, () -> diagnostics.recordFailure(null));
    }
}
