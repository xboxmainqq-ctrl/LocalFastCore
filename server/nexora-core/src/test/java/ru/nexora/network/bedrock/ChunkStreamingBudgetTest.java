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
        assertFalse(budget.tryAcquire("active-player", 190));
        assertTrue(budget.tryAcquire("active-player", 210),
                "Idle queue entries expire while recently active sessions remain registered");
    }

    @Test
    void waitingSessionYieldsItsTurnToReadySessions() {
        ChunkStreamingBudget budget = new ChunkStreamingBudget(100, 8, 2, 500);

        assertTrue(budget.tryAcquire("waiting-player", 0));
        assertFalse(budget.tryAcquire("ready-player", 1));
        budget.yield("waiting-player", 2);
        assertTrue(budget.tryAcquire("ready-player", 3));
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
    void pausingCompletedViewDoesNotResetTheSessionWindowQuota() {
        ChunkStreamingBudget budget = new ChunkStreamingBudget(100, 8, 1, 500);

        assertTrue(budget.tryAcquire("player-a", 0));
        budget.pause("player-a");
        assertTrue(budget.tryAcquire("player-b", 1));
        budget.pause("player-b");
        assertFalse(budget.tryAcquire("player-a", 2),
                "A quick view restart cannot reset a player's per-window quota");
    }

    @Test
    void rotatesGlobalAdmissionAcross128PlayersOverSuccessiveWindows() {
        int players = 128;
        ChunkStreamingBudget budget = new ChunkStreamingBudget(100, 64, 4, 500);

        // Register every active player before measuring a full budget window.
        assertTrue(budget.tryAcquire("player-0", 0));
        for (int player = 1; player < players; player++) {
            assertFalse(budget.tryAcquire("player-" + player, 0));
        }
        budget.yield("player-0", 0);

        int[] admitted = new int[players];
        for (int window = 1; window <= 2; window++) {
            int before = budget.globalUsage();
            for (int pass = 0; pass < 2; pass++) {
                for (int player = 0; player < players; player++) {
                    String session = "player-" + player;
                    for (int chunk = 0; chunk < 4; chunk++) {
                        if (budget.tryAcquire(session, window * 100L)) admitted[player]++;
                    }
                }
            }
            assertEquals(64, budget.globalUsage() - before,
                    "Global packet encoding must never exceed 64 chunks in one window");
        }
        assertTrue(java.util.Arrays.stream(admitted).allMatch(count -> count >= 1),
                "Round-robin rotation must serve every player across two budget windows");
    }

    @Test
    void rejectsInvalidBudgets() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkStreamingBudget(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkStreamingBudget(10, 1, 1, 9));
    }
}
