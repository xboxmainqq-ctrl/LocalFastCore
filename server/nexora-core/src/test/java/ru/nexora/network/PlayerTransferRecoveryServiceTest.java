package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTransferRecoveryServiceTest {
    @Test
    void rehydratesActiveClaimAndCommittedOwnershipAfterRestart() throws Exception {
        InMemoryPlayerTransferLedger storage = new InMemoryPlayerTransferLedger();
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(storage));
        UUID claimedId = UUID.randomUUID();
        UUID committedId = UUID.randomUUID();
        ledger.prepare(new PlayerHandoff(claimedId, 1L, "source-a", "target", new byte[0]),
                "xuid-claimed", 4, 10);
        var claimed = ledger.claim(claimedId, "target", 20, 10_000).orElseThrow();
        ledger.prepare(new PlayerHandoff(committedId, 2L, "source-b", "target", new byte[0]),
                "xuid-committed", 8, 10);
        var committed = ledger.claim(committedId, "target", 20, 10_000).orElseThrow();
        ledger.commit(committedId, committed.fencingToken(), 30).orElseThrow();

        PendingPlayerTransferRestoreRegistry registry = new PendingPlayerTransferRestoreRegistry();
        PlayerTransferRecoveryService recovery = new PlayerTransferRecoveryService(
                ledger, registry, "target", () -> 40L);

        var report = recovery.recover(100);

        assertEquals(2, report.scanned());
        assertEquals(2, report.registered());
        assertEquals(claimed.playerRevision(),
                registry.find("xuid-claimed").orElseThrow().checkpointRevision());
        assertEquals(committed.playerRevision(),
                registry.find("xuid-committed").orElseThrow().checkpointRevision());
        assertTrue(registry.find("xuid-committed").orElseThrow().fence().isActiveClaim() == false);
        registry.find("xuid-committed").orElseThrow().fence().assertCommitted();
    }

    @Test
    void skipsExpiredClaimsAndRecoveryIsIdempotent() throws Exception {
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        UUID id = UUID.randomUUID();
        ledger.prepare(new PlayerHandoff(id, 1L, "source", "target", new byte[0]),
                "xuid-123", 7, 10);
        ledger.claim(id, "target", 20, 10).orElseThrow();

        PendingPlayerTransferRestoreRegistry registry = new PendingPlayerTransferRestoreRegistry();
        PlayerTransferRecoveryService recovery = new PlayerTransferRecoveryService(
                ledger, registry, "target", () -> 40L);

        var expired = recovery.recover(10);
        assertEquals(1, expired.expiredClaims());
        assertTrue(registry.find("xuid-123").isEmpty());

        // A later active claim can be recovered, and repeated recovery is harmless.
        ledger.claim(id, "target", 41, 10_000).orElseThrow();
        PlayerTransferRecoveryService activeRecovery = new PlayerTransferRecoveryService(
                ledger, registry, "target", () -> 42L);
        assertEquals(1, activeRecovery.recover(10).registered());
        assertEquals(1, activeRecovery.recover(10).alreadyRegistered());
    }

    @Test
    void newestTransferWinsWhenLedgerContainsPlayerHistory() throws Exception {
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        UUID oldId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        ledger.prepare(new PlayerHandoff(oldId, 1L, "source-a", "target", new byte[0]),
                "xuid-repeat", 7, 10);
        var oldClaim = ledger.claim(oldId, "target", 20, 10_000).orElseThrow();
        ledger.commit(oldId, oldClaim.fencingToken(), 30).orElseThrow();

        ledger.prepare(new PlayerHandoff(newId, 2L, "source-b", "target", new byte[0]),
                "xuid-repeat", 9, 10);
        var newestClaim = ledger.claim(newId, "target", 40, 10_000).orElseThrow();

        PendingPlayerTransferRestoreRegistry registry = new PendingPlayerTransferRestoreRegistry();
        PlayerTransferRecoveryService recovery = new PlayerTransferRecoveryService(
                ledger, registry, "target", () -> 41L);
        var report = recovery.recover(10);

        assertEquals(2, report.scanned());
        assertEquals(1, report.registered());
        assertEquals(1, report.supersededRecords());
        var restored = registry.find("xuid-repeat").orElseThrow();
        assertEquals(newId, restored.fence().transferId());
        assertEquals(newestClaim.fencingToken(), restored.fence().fencingToken());
    }

}
