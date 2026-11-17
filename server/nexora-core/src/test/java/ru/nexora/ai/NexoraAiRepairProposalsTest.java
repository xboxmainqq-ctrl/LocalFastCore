package ru.nexora.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NexoraAiRepairProposalsTest {
    @Test
    void createsPendingProposalAndFindsItCaseInsensitively() {
        NexoraAiRepairProposals store = new NexoraAiRepairProposals();

        NexoraAiRepairProposals.Proposal created =
                store.create("operator-xuid", "Furnace tick is slow", "Inspect furnace scheduling and add a focused test.");

        assertEquals("R-000001", created.id());
        assertEquals(NexoraAiRepairProposals.Status.PENDING_REVIEW, created.status());
        assertEquals(created, store.find("r-000001").orElseThrow());
    }

    @Test
    void approvalOnlyChangesReviewStatusAndCannotBeRepeated() {
        NexoraAiRepairProposals store = new NexoraAiRepairProposals();
        NexoraAiRepairProposals.Proposal created =
                store.create("operator-xuid", "Slow autosave", "Proposed investigation.");

        NexoraAiRepairProposals.Proposal approved =
                store.approveForManualReview(created.id(), "reviewer-xuid").orElseThrow();

        assertEquals(NexoraAiRepairProposals.Status.APPROVED_FOR_MANUAL_REVIEW, approved.status());
        assertEquals(created.plan(), approved.plan());
        assertEquals("reviewer-xuid", approved.reviewedByXuid());
        assertNotNull(approved.reviewedAt());
        assertTrue(store.approveForManualReview(created.id()).isEmpty());
    }

    @Test
    void rejectsPendingProposalAndCannotApproveItAfterward() {
        NexoraAiRepairProposals store = new NexoraAiRepairProposals();
        NexoraAiRepairProposals.Proposal created =
                store.create("operator-xuid", "Mob AI stall", "Proposed investigation.");

        assertEquals(NexoraAiRepairProposals.Status.REJECTED,
                store.reject(created.id(), "reviewer-xuid").orElseThrow().status());
        assertTrue(store.approveForManualReview(created.id()).isEmpty());
    }

    @Test
    void capsProposalCountAndInputSizes() {
        NexoraAiRepairProposals store = new NexoraAiRepairProposals();
        for (int i = 0; i < 101; i++) {
            store.create("operator", "issue " + i, "plan " + i);
        }

        assertEquals(100, store.list().size());
        assertTrue(store.find("R-000001").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> store.create("operator", "x".repeat(601), "plan"));
        assertThrows(IllegalArgumentException.class,
                () -> store.create("operator", "issue", "x".repeat(6001)));
    }

    @Test
    void rejectsBlankIdentityAndContent() {
        NexoraAiRepairProposals store = new NexoraAiRepairProposals();

        assertThrows(IllegalArgumentException.class, () -> store.create(" ", "issue", "plan"));
        assertThrows(IllegalArgumentException.class, () -> store.create("operator", " ", "plan"));
        assertThrows(IllegalArgumentException.class, () -> store.create("operator", "issue", " "));
    }
}
