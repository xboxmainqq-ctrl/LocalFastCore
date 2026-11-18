package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PendingPlayerTransferRestoreRegistryTest {
    @Test
    void registrationRequiresCurrentMatchingFenceAndIsConsumedExactlyOnce() throws Exception {
        UUID id = UUID.randomUUID();
        StubLedger ledger = new StubLedger();
        PlayerTransferRecord claim = PlayerTransferRecord.prepared(
                id, "xuid-123", "source", "target", 7, 1).claim("target", 2, 10_000).orElseThrow();
        ledger.record = claim;
        PlayerTransferFence fence = new PlayerTransferFence(ledger, claim, () -> 100L);
        PendingPlayerTransferRestoreRegistry registry = new PendingPlayerTransferRestoreRegistry();
        var restore = new PendingPlayerTransferRestoreRegistry.PendingRestore("xuid-123", 7, fence);

        registry.register(restore);
        assertEquals(Optional.of(restore), registry.find("xuid-123"));
        assertFalse(registry.consume(new PendingPlayerTransferRestoreRegistry.PendingRestore(
                "xuid-123", 7, fence) == restore ? null : restore));
        assertTrue(registry.consume(restore));
        assertTrue(registry.find("xuid-123").isEmpty());
        assertEquals(0, registry.size());
    }

    @Test
    void staleFenceCannotBeRegisteredAndCancellationIsBoundToToken() throws Exception {
        UUID id = UUID.randomUUID();
        StubLedger ledger = new StubLedger();
        PlayerTransferRecord claim = PlayerTransferRecord.prepared(
                id, "xuid-123", "source", "target", 7, 1).claim("target", 2, 10_000).orElseThrow();
        ledger.record = claim;
        PlayerTransferFence fence = new PlayerTransferFence(ledger, claim, () -> 100L);
        PendingPlayerTransferRestoreRegistry registry = new PendingPlayerTransferRestoreRegistry();
        var restore = new PendingPlayerTransferRestoreRegistry.PendingRestore("xuid-123", 7, fence);
        assertFalse(registry.cancel("xuid-123", id, fence.fencingToken()));
        registry.register(restore);
        assertFalse(registry.cancel("xuid-123", id, fence.fencingToken() + 1));
        assertTrue(registry.cancel("xuid-123", id, fence.fencingToken()));
        assertTrue(registry.find("xuid-123").isEmpty());

        ledger.record = claim.abort(3).orElseThrow();
        assertThrows(IllegalStateException.class, () -> registry.register(restore));
    }

    private static final class StubLedger extends DurableHandoffCoordinator {
        private PlayerTransferRecord record;

        private StubLedger() {
            super(new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger(), 1_000));
        }
        @Override
        public Optional<PlayerTransferRecord> get(UUID transferId) {
            return Optional.ofNullable(record).filter(value -> value.transferId().equals(transferId));
        }
    }
}
