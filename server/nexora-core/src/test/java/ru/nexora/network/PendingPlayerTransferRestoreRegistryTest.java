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

    private static DurableHandoffCoordinator ledgerWithClaim(UUID id) throws Exception {
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        ledger.prepare(new PlayerHandoff(id, 1L, "source", "target", new byte[0]), "xuid-123", 7, 1);
        ledger.claim(id, "target", 2, 10_000).orElseThrow();
        return ledger;
    }
}
