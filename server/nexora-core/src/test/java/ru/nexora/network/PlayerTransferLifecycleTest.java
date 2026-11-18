package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTransferLifecycleTest {
    @Test
    void preparesFrozenCheckpointAndCommitsOnlyAfterDestinationActivation() throws Exception {
        InMemoryPlayerTransferLedger store = new InMemoryPlayerTransferLedger();
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(new PlayerTransferCoordinator(store));
        FakeSource source = new FakeSource();
        FakeDestination destination = new FakeDestination();
        PlayerTransferLifecycle lifecycle = new PlayerTransferLifecycle(ledger, source, destination, 5_000);
        PlayerHandoff handoff = handoff();

        lifecycle.prepare(handoff, "xuid_123", 100);
        assertTrue(source.frozen);
        assertEquals(12L, store.get(handoff.transferId()).orElseThrow().playerRevision());

        assertEquals(PlayerTransferLifecycle.AcceptResult.COMMITTED, lifecycle.accept(handoff, 101));
        assertTrue(destination.activated);
        assertEquals(PlayerTransferRecord.State.COMMITTED,
                store.get(handoff.transferId()).orElseThrow().state());
        assertTrue(source.frozen, "committed source must remain frozen/disconnected");
        assertEquals(1, destination.activationCount);
    }

    @Test
    void failedActivationAbortsBeforeSourceIsUnfrozen() throws Exception {
        InMemoryPlayerTransferLedger store = new InMemoryPlayerTransferLedger();
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(new PlayerTransferCoordinator(store));
        FakeSource source = new FakeSource();
        FakeDestination destination = new FakeDestination();
        destination.activationResult = false;
        PlayerTransferLifecycle lifecycle = new PlayerTransferLifecycle(ledger, source, destination, 5_000);
        PlayerHandoff handoff = handoff();

        lifecycle.prepare(handoff, "xuid_123", 100);
        assertEquals(PlayerTransferLifecycle.AcceptResult.ACTIVATION_FAILED_AND_ABORTED,
                lifecycle.accept(handoff, 101));

        assertFalse(source.frozen);
        assertEquals(PlayerTransferRecord.State.ABORTED,
                store.get(handoff.transferId()).orElseThrow().state());
        assertEquals(1, destination.deactivationCount);
    }

    @Test
    void failedPreparePublicationUnfreezesSource() throws Exception {
        InMemoryPlayerTransferLedger store = new InMemoryPlayerTransferLedger();
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(new PlayerTransferCoordinator(store));
        FakeSource source = new FakeSource();
        PlayerTransferLifecycle lifecycle = new PlayerTransferLifecycle(
                ledger, source, new FakeDestination(), 5_000);
        PlayerHandoff handoff = handoff();
        ledger.prepare(handoff, "xuid_123", 12, 100);

        assertThrows(IllegalStateException.class, () -> lifecycle.prepare(handoff, "xuid_123", 101));
        assertFalse(source.frozen);
    }

    @Test
    void uncertainCommitNeverUnfreezesSource() throws Exception {
        InMemoryPlayerTransferLedger store = new InMemoryPlayerTransferLedger();
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(new PlayerTransferCoordinator(store));
        FakeSource source = new FakeSource();
        FakeDestination destination = new FakeDestination();
        PlayerTransferLifecycle lifecycle = new PlayerTransferLifecycle(
                ledger, source, destination, 5_000);
        PlayerHandoff handoff = handoff();

        lifecycle.prepare(handoff, "xuid_123", 100);
        long fence = ledger.claim(handoff.transferId(), "node-b", 101, 5_000)
                .orElseThrow().fencingToken();
        // A different claim cannot be committed by the stale lifecycle fence.
        assertEquals(PlayerTransferLifecycle.AcceptResult.CLAIM_REJECTED,
                lifecycle.accept(handoff, 102));
        assertTrue(source.frozen);
        assertEquals(PlayerTransferRecord.State.CLAIMED,
                store.get(handoff.transferId()).orElseThrow().state());
        assertTrue(fence > 0);
    }

    private static PlayerHandoff handoff() {
        return new PlayerHandoff(UUID.randomUUID(), 7L, "node-a", "node-b", new byte[] {1, 2});
    }

    private static final class FakeSource implements PlayerTransferLifecycle.Source {
        boolean frozen;
        @Override public long freezeAndCheckpoint(PlayerHandoff handoff) {
            frozen = true;
            return 12L;
        }
        @Override public void unfreeze(PlayerHandoff handoff) { frozen = false; }
    }

    private static final class FakeDestination implements PlayerTransferLifecycle.Destination {
        boolean activationResult = true;
        boolean activated;
        int activationCount;
        int deactivationCount;
        @Override public boolean restoreAndActivate(PlayerHandoff handoff, long fencingToken) {
            assertTrue(fencingToken > 0);
            activationCount++;
            activated = activationResult;
            return activationResult;
        }
        @Override public void deactivateIfOwned(PlayerHandoff handoff, long fencingToken) {
            assertTrue(fencingToken > 0);
            deactivationCount++;
            activated = false;
        }
    }
}
