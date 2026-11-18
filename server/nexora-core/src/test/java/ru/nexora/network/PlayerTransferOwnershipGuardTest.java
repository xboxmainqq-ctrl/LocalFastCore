package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTransferOwnershipGuardTest {
    @Test
    void noTransferRecordAllowsLegacyLoginAndSessionProcessing() {
        assertEquals(PlayerTransferOwnershipGuard.LoginDecision.LEGACY_ALLOWED,
                PlayerTransferOwnershipGuard.loginDecision(Optional.empty(), "node-a"));
        assertTrue(PlayerTransferOwnershipGuard.mayProcessSession(Optional.empty(), "node-a"));
    }

    @Test
    void committedTransferAllowsOnlyDestinationAndRequiresCanonicalRestore() {
        PlayerTransferRecord committed = record(PlayerTransferRecord.State.COMMITTED);
        assertEquals(PlayerTransferOwnershipGuard.LoginDecision.RESTORE_CANONICAL,
                PlayerTransferOwnershipGuard.loginDecision(Optional.of(committed), "node-b"));
        assertTrue(PlayerTransferOwnershipGuard.mayProcessSession(Optional.of(committed), "node-b"));
        assertThrows(SecurityException.class,
                () -> PlayerTransferOwnershipGuard.loginDecision(Optional.of(committed), "node-a"));
        assertFalse(PlayerTransferOwnershipGuard.mayProcessSession(Optional.of(committed), "node-a"));
    }

    @Test
    void claimedTransferRequiresDestinationRestoreAndBlocksGameplayUntilCommit() {
        PlayerTransferRecord claimed = record(PlayerTransferRecord.State.CLAIMED);
        assertThrows(SecurityException.class,
                () -> PlayerTransferOwnershipGuard.loginDecision(Optional.of(claimed), "node-b"));
        assertFalse(PlayerTransferOwnershipGuard.mayProcessSession(Optional.of(claimed), "node-b"));
        assertThrows(SecurityException.class,
                () -> PlayerTransferOwnershipGuard.loginDecision(Optional.of(claimed), "node-a"));
    }

    @Test
    void preparedTransferBlocksAdmissionAndAbortedTransferReturnsToSource() {
        PlayerTransferRecord prepared = record(PlayerTransferRecord.State.PREPARED);
        assertThrows(SecurityException.class,
                () -> PlayerTransferOwnershipGuard.loginDecision(Optional.of(prepared), "node-a"));
        assertFalse(PlayerTransferOwnershipGuard.mayProcessSession(Optional.of(prepared), "node-a"));

        PlayerTransferRecord aborted = record(PlayerTransferRecord.State.ABORTED);
        assertEquals(PlayerTransferOwnershipGuard.LoginDecision.RESTORE_CANONICAL,
                PlayerTransferOwnershipGuard.loginDecision(Optional.of(aborted), "node-a"));
        assertTrue(PlayerTransferOwnershipGuard.mayProcessSession(Optional.of(aborted), "node-a"));
        assertThrows(SecurityException.class,
                () -> PlayerTransferOwnershipGuard.loginDecision(Optional.of(aborted), "node-b"));
    }

    private static PlayerTransferRecord record(PlayerTransferRecord.State state) {
        return new PlayerTransferRecord(UUID.randomUUID(), "xuid-guard", "node-a", "node-b",
                4L, state, state == PlayerTransferRecord.State.PREPARED ? 0L : 1L,
                state == PlayerTransferRecord.State.CLAIMED ? 10_000L : 0L,
                2L, 100L);
    }
}
