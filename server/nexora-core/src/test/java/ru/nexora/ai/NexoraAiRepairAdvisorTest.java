package ru.nexora.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class NexoraAiRepairAdvisorTest {

    @Test
    void createsOneProposalForSustainedFailureAndDoesNotDuplicateIt() {
        AtomicLong clock = new AtomicLong(10_000_000_000L);
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics(clock::get);
        NexoraAiRepairProposals proposals = new NexoraAiRepairProposals();
        NexoraAiRepairAdvisor advisor = new NexoraAiRepairAdvisor(proposals);

        for (int minute = 0; minute < 3; minute++) {
            diagnostics.recordFailure("world-autosave", new IOException("disk unavailable"));
            if (minute < 2) clock.addAndGet(60_000_000_000L);
        }

        assertEquals(1, advisor.analyze(diagnostics).size());
        assertEquals(1, proposals.list().size());
        assertEquals(0, advisor.analyze(diagnostics).size());
        assertEquals(NexoraAiRepairProposals.Status.PENDING_REVIEW,
                proposals.list().getFirst().status());
    }

    @Test
    void createsLatencyAndResourceProposalsWithoutExecutingAnything() {
        AtomicLong clock = new AtomicLong(10_000_000_000L);
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics(clock::get);
        NexoraAiRepairProposals proposals = new NexoraAiRepairProposals();
        NexoraAiRepairAdvisor advisor = new NexoraAiRepairAdvisor(proposals);

        for (int minute = 0; minute < 3; minute++) {
            for (int sample = 0; sample < 5; sample++) {
                diagnostics.recordSlowOperation("mob-ai", 100, 50);
            }
            for (int sample = 0; sample < 2; sample++) {
                diagnostics.recordResourceSnapshot(new JvmResourceSnapshot(
                        95, 100, 100, 10, 10, 100 + minute * 20, 100 + minute * 20, 1, 4, 0.5));
            }
            if (minute < 2) clock.addAndGet(60_000_000_000L);
        }

        var created = advisor.analyze(diagnostics);
        assertEquals(2, created.size());
        assertTrue(created.stream().anyMatch(p -> p.issue().contains("mob-ai")));
        assertTrue(created.stream().anyMatch(p -> p.issue().contains("потоков")));
        assertTrue(created.stream().allMatch(p ->
                p.status() == NexoraAiRepairProposals.Status.PENDING_REVIEW));
    }
}
