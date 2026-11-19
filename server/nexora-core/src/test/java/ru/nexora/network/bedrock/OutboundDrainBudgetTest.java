package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OutboundDrainBudgetTest {
    @Test
    void enforcesGlobalFrameAndByteCapsPerWindow() {
        OutboundDrainBudget budget = new OutboundDrainBudget(100, 2, 10, 4, 10, 500);

        assertTrue(budget.tryAcquire("player-a", 6, 0));
        assertFalse(budget.tryAcquire("player-a", 5, 1),
                "The global byte budget must not be exceeded");
        assertEquals(1, budget.globalFrames(1));
        assertEquals(6, budget.globalBytes(1));

        assertTrue(budget.tryAcquire("player-a", 4, 100), "A new window resets byte and frame usage");
        assertEquals(1, budget.globalFrames(100));
        assertEquals(4, budget.globalBytes(100));
    }

    @Test
    void rotatesOutboundAdmissionBetweenSessions() {
        OutboundDrainBudget budget = new OutboundDrainBudget(100, 8, 100, 8, 100, 500);

        assertTrue(budget.tryAcquire("player-a", 10, 0));
        assertFalse(budget.tryAcquire("player-b", 10, 1));
        assertTrue(budget.tryAcquire("player-a", 10, 2));
        assertTrue(budget.tryAcquire("player-b", 10, 3),
                "The next session receives the next outbound frame slot");
        assertEquals(3, budget.globalFrames(3));
    }

    @Test
    void perSessionFrameQuotaYieldsToTheNextSession() {
        OutboundDrainBudget budget = new OutboundDrainBudget(100, 8, 100, 1, 100, 500);

        assertTrue(budget.tryAcquire("player-a", 10, 0));
        assertFalse(budget.tryAcquire("player-b", 10, 1));
        assertFalse(budget.tryAcquire("player-a", 10, 2));
        assertTrue(budget.tryAcquire("player-b", 10, 3));
    }

    @Test
    void pausePreservesWindowUsageAndUnregisterRemovesIt() {
        OutboundDrainBudget budget = new OutboundDrainBudget(100, 8, 100, 1, 100, 500);

        assertTrue(budget.tryAcquire("player-a", 10, 0));
        budget.pause("player-a");
        assertFalse(budget.tryAcquire("player-a", 10, 1),
                "Draining and refilling a queue cannot reset the session quota");
        budget.unregister("player-a");
        assertTrue(budget.tryAcquire("player-a", 10, 2),
                "Disconnect removes the previous session's quota state");
    }

    @Test
    void idleSessionDoesNotBlockActiveOutboundQueues() {
        OutboundDrainBudget budget = new OutboundDrainBudget(100, 8, 100, 8, 100, 200);

        assertTrue(budget.tryAcquire("idle-player", 10, 0));
        assertFalse(budget.tryAcquire("active-player", 10, 10));
        assertFalse(budget.tryAcquire("active-player", 10, 190));
        assertTrue(budget.tryAcquire("active-player", 10, 210));
    }

    @Test
    void distributesGlobalCapacityAcrossOneHundredTwentyEightSessionsAcrossWindows() {
        int sessionCount = 128;
        OutboundDrainBudget budget = new OutboundDrainBudget(
                50, 64, 64 * 1024, 1, 1024, 500);
        int[] admitted = new int[sessionCount];

        for (int window = 0; window < 2; window++) {
            long now = window * 50L;
            for (int session = 0; session < sessionCount; session++) {
                String key = "player-" + session;
                if (budget.tryAcquire(key, 128, now)) {
                    admitted[session]++;
                }
            }
            assertEquals(64, budget.globalFrames(now + 49),
                    "Each window must stop exactly at the aggregate frame cap");
            assertEquals(64 * 128L, budget.globalBytes(now + 49));
        }

        for (int session = 0; session < sessionCount; session++) {
            assertEquals(1, admitted[session],
                    "Every session must receive one frame before any session receives another");
        }
    }

    @Test
    void rejectsInvalidBudgetsAndFrameSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> new OutboundDrainBudget(0, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboundDrainBudget(10, 1, 1, 1, 1, 9));
        OutboundDrainBudget budget = new OutboundDrainBudget(10, 1, 1, 1, 1, 10);
        assertThrows(IllegalArgumentException.class, () -> budget.tryAcquire("player", 0, 0));
    }
}
