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
    void resourceSnapshotIsAvailableThroughDiagnostics() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        JvmResourceSnapshot resources = diagnostics.resourceSnapshot();

        assertTrue(resources.heapUsedBytes() >= 0);
        assertTrue(resources.heapCommittedBytes() >= resources.heapUsedBytes());
        assertTrue(resources.liveThreadCount() > 0);
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
    void guardSignalsAreIncludedAsBoundedIdentifierFreeEvidence() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();

        diagnostics.recordGuardSignal("frame-rate threshold exceeded: 301 frames, 12000 bytes");

        String evidence = diagnostics.recentEvidence();
        assertTrue(evidence.contains("[GUARD]"));
        assertTrue(evidence.contains("frame-rate threshold exceeded"));
        assertFalse(evidence.contains("peer-xuid"));
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

    @Test
    void recentEvidenceIncludesTimestampedFailuresAndSlowOperationsNewestFirst() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();

        diagnostics.recordFailure(new IllegalStateException("furnace tick failed"));
        diagnostics.recordSlowOperation("mob-ai", 75_000_000L, 50_000_000L);

        String evidence = diagnostics.recentEvidence();
        assertTrue(evidence.contains("[SLOW] mob-ai took 75 ms"));
        assertTrue(evidence.contains("[FAILURE] IllegalStateException: furnace tick failed"));
        assertTrue(evidence.indexOf("[SLOW]") < evidence.indexOf("[FAILURE]"));
        assertTrue(evidence.length() <= 1_900);
    }

    @Test
    void recentEvidenceIsBoundedToTwelveEvents() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        for (int i = 0; i < 20; i++) {
            diagnostics.recordFailure(new IllegalStateException("failure-" + i));
        }

        String evidence = diagnostics.recentEvidence();
        assertTrue(evidence.contains("failure-19"));
        assertTrue(evidence.contains("failure-8"));
        assertFalse(evidence.contains("failure-7"));
    }

    @Test
    void recentEvidenceDoesNotExposeStackTraces() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        diagnostics.recordFailure(new IllegalStateException("bounded summary"));

        String evidence = diagnostics.recentEvidence();
        assertFalse(evidence.contains("at ru.nexora"));
        assertFalse(evidence.contains("java.lang.Thread"));
    }

    @Test
    void diagnosticEvidenceRedactsCredentialLikeValues() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        diagnostics.recordFailure(new IllegalStateException(
                "request failed Bearer abc.def.ghi api_key=sk-secret access_token=private-token"));

        String evidence = diagnostics.recentEvidence();
        assertFalse(evidence.contains("abc.def.ghi"));
        assertFalse(evidence.contains("sk-secret"));
        assertFalse(evidence.contains("private-token"));
        assertTrue(evidence.contains("[REDACTED]"));
    }

    @Test
    void repeatedFailuresAreGroupedBySubsystemAndExceptionType() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        diagnostics.recordFailure("world-autosave", new IllegalStateException("disk busy"));
        diagnostics.recordFailure("world-autosave", new IllegalStateException("disk full"));
        diagnostics.recordFailure("furnace-tick", new IllegalStateException("tick failed"));

        String evidence = diagnostics.recentEvidence();
        assertTrue(evidence.contains("Повторяющиеся события с момента запуска:"));
        assertTrue(evidence.contains("FAILURE:world-autosave:IllegalStateException — 2 раз"));
        assertFalse(evidence.contains("FAILURE:furnace-tick:IllegalStateException — 2 раз"));
    }

    @Test
    void repeatedSlowOperationsAreGroupedBySubsystem() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        diagnostics.recordSlowOperation("mob-ai", 80_000_000L, 50_000_000L);
        diagnostics.recordSlowOperation("mob-ai", 90_000_000L, 50_000_000L);
        diagnostics.recordSlowOperation("furnace-tick", 70_000_000L, 50_000_000L);

        String evidence = diagnostics.recentEvidence();
        assertTrue(evidence.contains("SLOW:mob-ai — 2 раз"));
        assertFalse(evidence.contains("SLOW:furnace-tick — 2 раз"));
    }

    @Test
    void repeatedSignatureStorageIsBounded() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        for (int i = 0; i < 24; i++) {
            diagnostics.recordFailure("subsystem-" + i, new IllegalStateException("failed"));
        }
        diagnostics.recordFailure("subsystem-0", new IllegalStateException("failed again"));

        String evidence = diagnostics.recentEvidence();
        assertTrue(evidence.length() <= 1_900);
        assertTrue(evidence.contains("FAILURE:subsystem-0:IllegalStateException — 2 раз"));
    }


    @Test
    void rollingLatencySummaryIncludesFastAndSlowSamples() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        diagnostics.recordSlowOperation("mob-ai", 10, 100);
        diagnostics.recordSlowOperation("mob-ai", 20, 100);
        diagnostics.recordSlowOperation("mob-ai", 30, 100);
        diagnostics.recordSlowOperation("mob-ai", 40, 100);

        var summary = diagnostics.latencySummaries().getFirst();
        assertEquals("mob-ai", summary.subsystem());
        assertEquals(4, summary.sampleCount());
        assertEquals(0, summary.slowOperationCount());
        assertTrue(summary.windowMillis() <= 60_000);
        assertEquals(25, summary.averageNanos());
        assertEquals(40, summary.p95Nanos());
        assertEquals(40, summary.maxNanos());
        assertEquals(0, diagnostics.inspect().slowOperationCount());
    }

    @Test
    void rollingLatencySummaryCountsOnlyRecentBudgetBreaches() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        diagnostics.recordSlowOperation("mob-ai", 10, 5);
        diagnostics.recordSlowOperation("mob-ai", 20, 25);
        diagnostics.recordSlowOperation("mob-ai", 30, 25);

        var summary = diagnostics.latencySummaries().getFirst();
        assertEquals(3, summary.sampleCount());
        assertEquals(2, summary.slowOperationCount());
        assertTrue(summary.windowMillis() >= 1 && summary.windowMillis() <= 60_000);
        assertEquals(2, diagnostics.inspect().slowOperationCount());
    }

    @Test
    void rollingLatencyWindowRetainsOnlyTheMostRecentSixtySamples() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        for (int i = 1; i <= 61; i++) {
            diagnostics.recordSlowOperation("bedrock-inbound", i, 100);
        }

        var summary = diagnostics.latencySummaries().getFirst();
        assertEquals(60, summary.sampleCount());
        assertEquals(0, summary.slowOperationCount());
        assertEquals(31, summary.averageNanos());
        assertEquals(58, summary.p95Nanos());
        assertEquals(61, summary.maxNanos());
    }

    @Test
    void latencyEvidenceIsExplicitlyNotServerTps() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        diagnostics.recordSlowOperation("world-autosave", 75_000_000L, 50_000_000L);

        assertTrue(diagnostics.recentEvidence().contains("Последние замеры длительности подсистем (не TPS):"));
        assertTrue(diagnostics.recentEvidence().contains("world-autosave"));
        assertTrue(diagnostics.recentEvidence().contains("window="));
        assertTrue(diagnostics.recentEvidence().contains("overBudget=1"));
    }

}
