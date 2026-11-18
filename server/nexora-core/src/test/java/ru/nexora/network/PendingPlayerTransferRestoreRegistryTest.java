package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PendingPlayerTransferRestoreRegistryTest {
    @Test
    void registrationIsFoundConsumedExactlyOnceAndBoundToExactObject() throws Exception {
        UUID id = UUID.randomUUID();
        DurableHandoffCoordinator ledger = ledgerWithClaim(id);
        PlayerTransferRecord claim = ledger.get(id).orElseThrow();
        PlayerTransferFence fence = new PlayerTransferFence(ledger, claim, () -> 100L);
        PendingPlayerTransferRestoreRegistry registry = new PendingPlayerTransferRestoreRegistry();
        var restore = new PendingPlayerTransferRestoreRegistry.PendingRestore("xuid-123", 7, fence);

        registry.register(restore);
        assertEquals(restore, registry.find("xuid-123").orElseThrow());
        assertFalse(registry.consume(new PendingPlayerTransferRestoreRegistry.PendingRestore(
                "xuid-123", 7, fence)));
        assertTrue(registry.consume(restore));
        assertTrue(registry.find("xuid-123").isEmpty());
        assertEquals(0, registry.size());
    }

    @Test
    void staleFenceCannotBeRegisteredAndCancellationRequiresMatchingToken() throws Exception {
        UUID id = UUID.randomUUID();
        DurableHandoffCoordinator ledger = ledgerWithClaim(id);
        PlayerTransferRecord claim = ledger.get(id).orElseThrow();
        PlayerTransferFence fence = new PlayerTransferFence(ledger, claim, () -> 100L);
        PendingPlayerTransferRestoreRegistry registry = new PendingPlayerTransferRestoreRegistry();
        var restore = new PendingPlayerTransferRestoreRegistry.PendingRestore("xuid-123", 7, fence);
        assertFalse(registry.cancel("xuid-123", id, fence.fencingToken()));
        registry.register(restore);
        assertFalse(registry.cancel("xuid-123", id, fence.fencingToken() + 1));
        assertTrue(registry.cancel("xuid-123", id, fence.fencingToken()));
        assertTrue(registry.find("xuid-123").isEmpty());

        ledger.abort(id, fence.fencingToken(), 200L);
        assertThrows(IllegalStateException.class, () -> registry.register(restore));
    }

    @Test
    void newTransferPrunesOnlyRevokedPendingRegistrationForSameXuid() throws Exception {
        UUID oldId = UUID.randomUUID();
        DurableHandoffCoordinator ledger = ledgerWithClaim(oldId);
        PlayerTransferRecord oldClaim = ledger.get(oldId).orElseThrow();
        PlayerTransferFence oldFence = new PlayerTransferFence(ledger, oldClaim, () -> 100L);
        PendingPlayerTransferRestoreRegistry registry = new PendingPlayerTransferRestoreRegistry();
        var oldRestore = new PendingPlayerTransferRestoreRegistry.PendingRestore("xuid-123", 7, oldFence);
        registry.register(oldRestore);
        ledger.abort(oldId, oldFence.fencingToken(), 200L);

        UUID newId = UUID.randomUUID();
        ledger.prepare(new PlayerHandoff(newId, 2L, "source", "target", new byte[0]),
                "xuid-123", 8L, 201L);
        PlayerTransferRecord newClaim = ledger.claim(newId, "target", 202L, 10_000L).orElseThrow();
        PlayerTransferFence newFence = new PlayerTransferFence(ledger, newClaim, () -> 203L);
        var newRestore = new PendingPlayerTransferRestoreRegistry.PendingRestore("xuid-123", 8, newFence);

        registry.register(newRestore);

        assertEquals(newRestore, registry.find("xuid-123").orElseThrow());
        assertEquals(1, registry.size());
    }

    private static DurableHandoffCoordinator ledgerWithClaim(UUID id) throws Exception {
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        ledger.prepare(new PlayerHandoff(id, 1L, "source", "target", new byte[0]), "xuid-123", 7, 1);
        ledger.claim(id, "target", 2, 10_000).orElseThrow();
        return ledger;
    }
}
