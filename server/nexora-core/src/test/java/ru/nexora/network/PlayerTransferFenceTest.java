package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTransferFenceTest {
    @Test
    void gameplayFenceRequiresCommittedOwnership() throws Exception {
        UUID id = UUID.randomUUID();
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        ledger.prepare(new PlayerHandoff(id, 1L, "source", "target", new byte[0]),
                "xuid-456", 12, 1);
        PlayerTransferRecord claim = ledger.claim(id, "target", 2, 10_000).orElseThrow();
        PlayerTransferFence fence = new PlayerTransferFence(ledger, claim, () -> 100L);

        assertDoesNotThrow(fence::assertCurrent);
        assertThrows(IOException.class, fence::assertCommitted);

        assertTrue(ledger.commit(id, claim.fencingToken(), 101L).isPresent());
        assertDoesNotThrow(fence::assertCurrent);
        assertDoesNotThrow(fence::assertCommitted);
        PlayerTransferFence rehydrated = new PlayerTransferFence(ledger,
                ledger.get(id).orElseThrow(), () -> 102L);
        assertDoesNotThrow(rehydrated::assertCurrent);
        assertDoesNotThrow(rehydrated::assertCommitted);
    }

    @Test
    void expiredClaimCannotPassEitherOwnershipCheck() throws Exception {
        UUID id = UUID.randomUUID();
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        ledger.prepare(new PlayerHandoff(id, 1L, "source", "target", new byte[0]),
                "xuid-456", 12, 1);
        PlayerTransferRecord claim = ledger.claim(id, "target", 2, 10).orElseThrow();
        PlayerTransferFence fence = new PlayerTransferFence(ledger, claim, () -> 12L);

        assertThrows(IOException.class, fence::assertCurrent);
        assertThrows(IOException.class, fence::assertCommitted);
    }
}
