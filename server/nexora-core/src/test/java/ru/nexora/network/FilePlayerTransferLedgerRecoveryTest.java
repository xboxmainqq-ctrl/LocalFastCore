package ru.nexora.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FilePlayerTransferLedgerRecoveryTest {
    @TempDir
    Path directory;

    @Test
    void listsOnlyRecoverableTransfersForTheRequestedTarget() throws Exception {
        FilePlayerTransferLedger ledger = new FilePlayerTransferLedger(directory);
        PlayerTransferCoordinator coordinator = new PlayerTransferCoordinator(ledger);
        UUID claimedId = UUID.randomUUID();
        UUID committedId = UUID.randomUUID();
        UUID preparedId = UUID.randomUUID();
        UUID abortedId = UUID.randomUUID();

        assertTrue(ledger.create(PlayerTransferRecord.prepared(
                claimedId, "file-claimed", "source-a", "target-a", 1L, 10L)));
        assertTrue(ledger.create(PlayerTransferRecord.prepared(
                committedId, "file-committed", "source-b", "target-a", 2L, 11L)));
        assertTrue(ledger.create(PlayerTransferRecord.prepared(
                preparedId, "file-prepared", "source-c", "target-a", 3L, 12L)));
        assertTrue(ledger.create(PlayerTransferRecord.prepared(
                abortedId, "file-aborted", "source-d", "target-a", 4L, 13L)));

        coordinator.claim(claimedId, "target-a", 20L, 10_000L).orElseThrow();
        PlayerTransferRecord commitClaim = coordinator.claim(committedId, "target-a", 21L, 10_000L)
                .orElseThrow();
        coordinator.commit(committedId, commitClaim.fencingToken(), 22L).orElseThrow();
        PlayerTransferRecord abortClaim = coordinator.claim(abortedId, "target-a", 23L, 10_000L)
                .orElseThrow();
        coordinator.abort(abortedId, abortClaim.fencingToken(), 24L).orElseThrow();

        FilePlayerTransferLedger afterRestart = new FilePlayerTransferLedger(directory);
        var recovered = afterRestart.listRecoverableForTarget("target-a", 10);
        assertEquals(2, recovered.size());
        assertEquals(java.util.Set.of(claimedId, committedId),
                recovered.stream().map(PlayerTransferRecord::transferId)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, afterRestart.listRecoverableForTarget("target-a", 1).size());
        assertTrue(afterRestart.listRecoverableForTarget("target-b", 10).isEmpty());
    }

    @Test
    void rejectsUnboundedOrInvalidRecoveryQueries() throws Exception {
        FilePlayerTransferLedger ledger = new FilePlayerTransferLedger(directory);
        assertThrows(IllegalArgumentException.class, () -> ledger.listRecoverableForTarget("../target", 1));
        assertThrows(IllegalArgumentException.class, () -> ledger.listRecoverableForTarget("target", 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.listRecoverableForTarget("target", 10_001));
    }
}
