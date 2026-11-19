package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkStreamingBudgetTest {
    @Test
    void capsGlobalAndPerSessionChunkEncodingWithinEachWindow() {
        ChunkStreamingBudget budget = new ChunkStreamingBudget(100, 3, 2, 500);

        assertTrue(budget.tryAcquire("player-a", 0));
        assertTrue(budget.tryAcquire("player-a", 1));
        assertFalse(budget.tryAcquire("player-a", 2), "One player cannot exceed its window quota");
        assertEquals(2, budget.globalUsage());

        assertTrue(budget.tryAcquire("player-a", 100), "A new time window resets both counters");
        assertEquals(1, budget.globalUsage());
    }

    @Test
    void rotatesAdmissionBetweenSessionsInsteadOfLettingFirstPlayerMonopolizeBudget() {
        ChunkStreamingBudget budget = new ChunkStreamingBudget(100, 8, 2, 500);

        assertTrue(budget.tryAcquire("player-a", 0));
        assertTrue(budget.tryAcquire("player-a", 1));
        assertFalse(budget.tryAcquire("player-b", 2), "New session joins behind the current turn");
        assertFalse(budget.tryAcquire("player-a", 3), "Quota exhaustion yields the turn");
        assertTrue(budget.tryAcquire("player-b", 4), "Next session receives the next admission slot");
        assertFalse(budget.tryAcquire("player-a", 5));
        assertTrue(budget.tryAcquire("player-b", 6));
        assertEquals(4, budget.globalUsage());
    }

    @Test
    void idleSessionIsEvictedSoItCannotBlockActivePlayers() {
        ChunkStreamingBudget budget = new ChunkStreamingBudget(100, 8, 2, 200);

        assertTrue(budget.tryAcquire("idle-player", 0));
        assertFalse(budget.tryAcquire("active-player", 10));
        assertTrue(budget.tryAcquire("active-player", 250),
                "Idle queue entries expire and active sessions can continue");
    }

    @Test
    void unregisterRemovesSessionFromFairRotation() {
        ChunkStreamingBudget budget = new ChunkStreamingBudget(100, 8, 2, 500);

        assertTrue(budget.tryAcquire("player-a", 0));
        assertFalse(budget.tryAcquire("player-b", 1));
        budget.unregister("player-a");
        assertTrue(budget.tryAcquire("player-b", 2));
        assertEquals(1, budget.queuedSessions());
    }

    @Test
    void rejectsInvalidBudgets() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkStreamingBudget(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkStreamingBudget(10, 1, 1, 9));
    }
}
