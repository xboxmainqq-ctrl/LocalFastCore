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
    @Test
    void slowSubsystemOperationRaisesWarningAndIsReported() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();

        diagnostics.recordSlowOperation("mob-ai", 75_000_000L, 50_000_000L);

        NexoraAiDiagnostics.Snapshot snapshot = diagnostics.inspect();
        assertEquals(NexoraAiDiagnostics.Health.WARNING, snapshot.health());
        assertEquals(1, snapshot.slowOperationCount());
        assertEquals("mob-ai took 75 ms", snapshot.lastSlowOperation());
    }

    @Test
    void operationWithinBudgetIsNotReported() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();

        diagnostics.recordSlowOperation("mob-ai", 50_000_000L, 50_000_000L);

        assertEquals(0, diagnostics.inspect().slowOperationCount());
    }

    @Test
    void rejectsInvalidSlowOperationMeasurement() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        assertThrows(IllegalArgumentException.class,
                () -> diagnostics.recordSlowOperation(" ", 10, 1));
        assertThrows(IllegalArgumentException.class,
                () -> diagnostics.recordSlowOperation("mob-ai", -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> diagnostics.recordSlowOperation("mob-ai", 10, 0));
    }

}
