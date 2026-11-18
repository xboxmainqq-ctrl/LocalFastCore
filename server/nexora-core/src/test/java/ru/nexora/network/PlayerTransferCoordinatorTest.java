package ru.nexora.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTransferCoordinatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void expiredClaimCanBeReclaimedOnlyWithHigherFenceAndOldOwnerCannotCommit() throws IOException {
        InMemoryPlayerTransferLedger ledger = new InMemoryPlayerTransferLedger();
        PlayerTransferCoordinator coordinator = new PlayerTransferCoordinator(ledger);
        UUID id = UUID.randomUUID();
        assertTrue(coordinator.prepare(PlayerTransferRecord.prepared(
                id, "xuid-transfer", "node-a", "node-b", 12, 1_000)));

        PlayerTransferRecord first = coordinator.claim(id, "node-b", 1_100, 500).orElseThrow();
        assertEquals(1, first.fencingToken());
        assertTrue(coordinator.claim(id, "node-b", 1_599, 500).isEmpty());

        PlayerTransferRecord second = coordinator.claim(id, "node-b", 1_600, 500).orElseThrow();
        assertEquals(2, second.fencingToken());
        assertTrue(coordinator.commit(id, first.fencingToken(), 1_700).isEmpty());

        PlayerTransferRecord committed = coordinator.commit(id, second.fencingToken(), 1_700).orElseThrow();
        assertEquals(PlayerTransferRecord.State.COMMITTED, committed.state());
        assertTrue(coordinator.claim(id, "node-b", 2_500, 500).isEmpty());
    }

    @Test
    void preparedTransferCanBeCancelledButCannotAbortAfterDestinationClaimWins() throws IOException {
        InMemoryPlayerTransferLedger store = new InMemoryPlayerTransferLedger();
        PlayerTransferCoordinator coordinator = new PlayerTransferCoordinator(store);
        UUID id = UUID.randomUUID();
        assertTrue(coordinator.prepare(PlayerTransferRecord.prepared(
                id, "xuid-cancel", "node-a", "node-b", 4L, 100L)));

        PlayerTransferRecord aborted = coordinator.abortPrepared(id, 101L).orElseThrow();
        assertEquals(PlayerTransferRecord.State.ABORTED, aborted.state());
        assertEquals(0L, aborted.fencingToken());
        assertTrue(coordinator.claim(id, "node-b", 102L, 5_000L).isEmpty());

        UUID racedId = UUID.randomUUID();
        coordinator.prepare(PlayerTransferRecord.prepared(
                racedId, "xuid-race-cancel", "node-a", "node-b", 5L, 100L));
        PlayerTransferRecord claim = coordinator.claim(racedId, "node-b", 101L, 5_000L).orElseThrow();
        assertTrue(coordinator.abortPrepared(racedId, 102L).isEmpty());
        assertEquals(PlayerTransferRecord.State.CLAIMED, coordinator.get(racedId).orElseThrow().state());
        assertTrue(claim.fencingToken() > 0);
    }

    @Test
    void expiredClaimCanBeAbortedByItsCurrentFenceButNotBySupersededFence() throws IOException {
        InMemoryPlayerTransferLedger store = new InMemoryPlayerTransferLedger();
        PlayerTransferCoordinator coordinator = new PlayerTransferCoordinator(store);
        UUID id = UUID.randomUUID();
        coordinator.prepare(PlayerTransferRecord.prepared(
                id, "xuid-expired-abort", "node-a", "node-b", 1L, 100L));
        PlayerTransferRecord claim = coordinator.claim(id, "node-b", 101L, 10L).orElseThrow();

        PlayerTransferRecord aborted = coordinator.abort(id, claim.fencingToken(), 112L).orElseThrow();
        assertEquals(PlayerTransferRecord.State.ABORTED, aborted.state());
        assertTrue(coordinator.commit(id, claim.fencingToken(), 113L).isEmpty());

        UUID reclaimedId = UUID.randomUUID();
        coordinator.prepare(PlayerTransferRecord.prepared(
                reclaimedId, "xuid-reclaim-abort", "node-a", "node-b", 1L, 100L));
        PlayerTransferRecord oldClaim = coordinator.claim(reclaimedId, "node-b", 101L, 10L).orElseThrow();
        PlayerTransferRecord newClaim = coordinator.claim(reclaimedId, "node-b", 112L, 10_000L).orElseThrow();
        assertTrue(coordinator.abort(reclaimedId, oldClaim.fencingToken(), 113L).isEmpty());
        assertEquals(PlayerTransferRecord.State.CLAIMED,
                coordinator.get(reclaimedId).orElseThrow().state());
        assertTrue(newClaim.fencingToken() > oldClaim.fencingToken());
    }

    @Test
    void fileLedgerPersistsRecordsAndEnforcesCompareAndSetAcrossInstances() throws IOException {
        UUID id = UUID.randomUUID();
        FilePlayerTransferLedger firstStore = new FilePlayerTransferLedger(temporaryDirectory);
        PlayerTransferRecord prepared = PlayerTransferRecord.prepared(
                id, "xuid-durable", "gateway-a", "world-b", 7, 5_000);
        assertTrue(firstStore.create(prepared));
        assertFalse(firstStore.create(prepared));

        PlayerTransferRecord claimed = prepared.claimed(1, 6_000, 5_100);
        assertTrue(firstStore.compareAndSet(id, 0, claimed));
        assertFalse(firstStore.compareAndSet(id, 0, claimed));

        FilePlayerTransferLedger afterRestart = new FilePlayerTransferLedger(temporaryDirectory);
        assertEquals(claimed, afterRestart.get(id).orElseThrow());
        PlayerTransferRecord committed = claimed.committed(5_500);
        assertTrue(afterRestart.compareAndSet(id, 1, committed));
        assertEquals(PlayerTransferRecord.State.COMMITTED,
                new FilePlayerTransferLedger(temporaryDirectory).get(id).orElseThrow().state());
    }

    @Test
    void fileLedgerRejectsCorruptedRecord() throws IOException {
        UUID id = UUID.randomUUID();
        FilePlayerTransferLedger store = new FilePlayerTransferLedger(temporaryDirectory);
        store.create(PlayerTransferRecord.prepared(id, "xuid-corrupt", "node-a", "node-b", 0, 10));
        Path file = temporaryDirectory.resolve(id + ".nxtl");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> store.get(id));
    }
    @Test
    void durableHandoffFacadePersistsEnvelopeOwnershipMetadata() throws IOException {
        DurableHandoffCoordinator handoffs = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new FilePlayerTransferLedger(temporaryDirectory)));
        UUID id = UUID.randomUUID();
        PlayerHandoff envelope = new PlayerHandoff(id, 91L, "source-1", "target-2", new byte[] {1, 2, 3});

        handoffs.prepare(envelope, "xuid-handoff", 18, 10_000);
        PlayerTransferRecord claim = handoffs.claim(id, "target-2", 10_100, 2_000).orElseThrow();
        assertEquals("xuid-handoff", claim.playerXuid());
        assertEquals(18, claim.playerRevision());
        assertEquals(PlayerTransferRecord.State.CLAIMED, claim.state());
        assertEquals(PlayerTransferRecord.State.COMMITTED,
                handoffs.commit(id, claim.fencingToken(), 10_200).orElseThrow().state());
    }

}
