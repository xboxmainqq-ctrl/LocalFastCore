package ru.nexora.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NexoraAiGuardTest {
    @Test
    void flagsFrameRateOncePerWindowWithoutPunishingPeer() {
        List<NexoraAiGuard.GuardEvent> events = new ArrayList<>();
        NexoraAiGuard guard = new NexoraAiGuard(1_000, 2, 10_000, 8, events::add);

        guard.recordInboundFrame("peer-secret", 20, 100);
        guard.recordInboundFrame("peer-secret", 20, 200);
        guard.recordInboundFrame("peer-secret", 20, 300);
        guard.recordInboundFrame("peer-secret", 20, 400);

        assertEquals(1, events.size());
        assertEquals("frame-rate threshold exceeded", events.getFirst().reason());
        assertEquals(3, events.getFirst().framesInWindow());
        assertEquals(1, guard.snapshot().signalledPeers());
        assertFalse(guard.snapshot().summary().contains("peer-secret"));
    }

    @Test
    void flagsByteVolumeAndResetsAtWindowBoundary() {
        List<NexoraAiGuard.GuardEvent> events = new ArrayList<>();
        NexoraAiGuard guard = new NexoraAiGuard(1_000, 100, 100, 8, events::add);

        guard.recordInboundFrame("peer-a", 60, 0);
        guard.recordInboundFrame("peer-a", 60, 1);
        assertEquals("inbound-byte threshold exceeded", events.getFirst().reason());

        guard.recordInboundFrame("peer-a", 10, 1_000);
        assertEquals(0, guard.snapshot().signalledPeers());
        assertEquals(1, events.size());
        assertEquals(10, guard.snapshot().bytesInCurrentWindows());
    }

    @Test
    void removesPeerStateOnDisconnect() {
        NexoraAiGuard guard = new NexoraAiGuard(1_000, 1, 100, 8, ignored -> { });
        guard.recordInboundFrame("peer-a", 10, 0);
        guard.recordInboundFrame("peer-a", 10, 1);

        guard.onDisconnected("peer-a");

        assertEquals(0, guard.snapshot().trackedPeers());
        assertEquals(0, guard.snapshot().signalledPeers());
    }

    @Test
    void boundsTrackedPeerStateByEvictingOldestPeer() {
        NexoraAiGuard guard = new NexoraAiGuard(1_000, 100, 10_000, 2, ignored -> { });
        guard.recordInboundFrame("peer-a", 10, 0);
        guard.recordInboundFrame("peer-b", 10, 0);
        guard.recordInboundFrame("peer-c", 10, 0);

        assertEquals(2, guard.snapshot().trackedPeers());
        assertEquals(20, guard.snapshot().bytesInCurrentWindows());
    }

    @Test
    void repeatedHorizontalMovementCorrectionsProduceSpeedReviewSignal() {
        List<NexoraAiGuard.GuardEvent> events = new ArrayList<>();
        NexoraAiGuard guard = new NexoraAiGuard(1_000, 100, 10_000, 8, events::add);

        for (int i = 0; i < 4; i++) {
            guard.recordMovementDiscrepancy("player-xuid", 0.9, 0.1, i * 1_000_000_000L);
        }

        assertEquals(1, events.size());
        assertTrue(events.getFirst().reason().contains("possible speed"));
        assertEquals(1, guard.snapshot().totalSignalsSinceStart());
    }

    @Test
    void repeatedVerticalMovementCorrectionsProduceFlyReviewSignal() {
        List<NexoraAiGuard.GuardEvent> events = new ArrayList<>();
        NexoraAiGuard guard = new NexoraAiGuard(1_000, 100, 10_000, 8, events::add);

        for (int i = 0; i < 4; i++) {
            guard.recordMovementDiscrepancy("player-xuid", 0.1, 1.0, i * 1_000_000_000L);
        }

        assertEquals(1, events.size());
        assertTrue(events.getFirst().reason().contains("possible fly"));
    }

    @Test
    void repeatedOutOfReachAttacksProduceReviewSignalOnly() {
        List<NexoraAiGuard.GuardEvent> events = new ArrayList<>();
        NexoraAiGuard guard = new NexoraAiGuard(1_000, 100, 10_000, 8, events::add);

        for (int i = 0; i < 4; i++) {
            guard.recordOutOfReachAttack("player-xuid", 4.0, 3.5, i * 1_000_000_000L);
        }
        assertTrue(events.isEmpty());

        for (int i = 4; i < 9; i++) {
            guard.recordOutOfReachAttack("player-xuid", 4.5, 3.5, i * 1_000_000_000L);
        }

        assertEquals(1, events.size());
        assertTrue(events.getFirst().reason().contains("possible reach"));
        assertEquals(1, guard.snapshot().flaggedPlayers());
    }

    @Test
    void oneOffMovementCorrectionsDoNotFlagAndEvidenceExpires() {
        List<NexoraAiGuard.GuardEvent> events = new ArrayList<>();
        NexoraAiGuard guard = new NexoraAiGuard(1_000, 100, 10_000, 8, events::add);

        guard.recordMovementDiscrepancy("player-xuid", 1.0, 0.0, 0);
        guard.recordMovementDiscrepancy("player-xuid", 0.0, 1.0, 1_000_000_000L);
        guard.recordMovementDiscrepancy("player-xuid", 1.0, 0.0, 2_000_000_000L);
        guard.recordMovementDiscrepancy("player-xuid", 0.0, 1.0, 3_000_000_000L);
        guard.recordMovementDiscrepancy("player-xuid", 1.0, 0.0, 11_000_000_000L);

        assertTrue(events.isEmpty());
        assertEquals(0, guard.snapshot().totalSignalsSinceStart());
    }

    @Test
    void movementEvidenceIsBoundedAndRemovedWhenPlayerDisconnects() {
        NexoraAiGuard guard = new NexoraAiGuard(1_000, 100, 10_000, 2, ignored -> { });
        guard.recordMovementDiscrepancy("player-a", 1.0, 0.0, 0);
        guard.recordMovementDiscrepancy("player-b", 1.0, 0.0, 0);
        guard.recordMovementDiscrepancy("player-c", 1.0, 0.0, 0);
        assertEquals(2, guard.snapshot().trackedPlayers());
        guard.onPlayerDisconnected("player-c");

        assertEquals(1, guard.snapshot().trackedPlayers());
        assertEquals(0, guard.snapshot().flaggedPlayers());
    }

    @Test
    void flagsOnlyHighRateMultiTargetValidatedAttacksAndKeepsPrivateCaseSummary() {
        List<NexoraAiGuard.GuardEvent> events = new ArrayList<>();
        NexoraAiGuard guard = new NexoraAiGuard(1_000, 100, 10_000, 8, events::add);
        for (int i = 0; i < 64; i++) {
            guard.recordSuccessfulAttack("private-xuid", "PlayerOne",
                    "target-" + (i % 4), i * 50_000_000L);
        }
        assertTrue(events.isEmpty());
        guard.recordSuccessfulAttack("private-xuid", "PlayerOne",
                "target-3", 64 * 50_000_000L);
        assertEquals(1, events.size());
        assertTrue(events.getFirst().reason().contains("possible KillAura"));
        String summary = guard.combatReviewSummary(64 * 50_000_000L);
        assertTrue(summary.contains("PlayerOne"));
        assertTrue(summary.contains("65 hits"));
        assertFalse(summary.contains("private-xuid"));
        assertFalse(summary.contains("target-"));
        assertTrue(summary.contains("No automatic punishment"));
    }

    @Test
    void killAuraWindowResetsAndCaseExpiresAndDisconnectClearsActiveWindow() {
        NexoraAiGuard guard = new NexoraAiGuard(1_000, 100, 10_000, 8, ignored -> { });
        for (int i = 0; i < 30; i++) {
            guard.recordSuccessfulAttack("xuid-a", "A", "target-" + (i % 3), i * 50_000_000L);
        }
        guard.recordSuccessfulAttack("xuid-a", "A", "target-1", 5_000_000_000L);
        assertTrue(guard.combatReviewSummary(5_000_000_000L).contains("no confirmed"));
        for (int i = 0; i < 65; i++) {
            guard.recordSuccessfulAttack("xuid-a", "A", "target-" + (i % 4),
                    5_000_000_000L + i * 50_000_000L);
        }
        assertTrue(guard.combatReviewSummary(8_300_000_000L).contains("A"));
        assertTrue(guard.combatReviewSummary(1_900_000_000_000L).contains("no confirmed"));
        guard.recordSuccessfulAttack("xuid-b", "B", "target-1", 2_000_000_000_000L);
        guard.onPlayerDisconnected("xuid-b");
    }

    @Test
    void rejectsInvalidAttackEvidence() {
        NexoraAiGuard guard = new NexoraAiGuard();
        assertThrows(IllegalArgumentException.class,
                () -> guard.recordSuccessfulAttack(" ", "name", "target", 0));
        assertThrows(IllegalArgumentException.class,
                () -> guard.recordSuccessfulAttack("xuid", "name", " ", 0));
    }

    @Test
    void rejectsInvalidLimitsAndFrames() {
        assertThrows(IllegalArgumentException.class,
                () -> new NexoraAiGuard(0, 1, 1, 1, ignored -> { }));
        NexoraAiGuard guard = new NexoraAiGuard();
        assertThrows(IllegalArgumentException.class,
                () -> guard.recordInboundFrame("peer-a", -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> guard.recordInboundFrame(" ", 1, 0));
    }
}
