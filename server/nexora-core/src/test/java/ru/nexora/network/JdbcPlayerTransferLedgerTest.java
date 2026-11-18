package ru.nexora.network;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class JdbcPlayerTransferLedgerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsVersionedClaimsAcrossIndependentDataSourcesAndCommit() throws Exception {
        String url = databaseUrl("restart-ledger");
        JdbcPlayerTransferLedger first = new JdbcPlayerTransferLedger(dataSource(url));
        first.initializeSchema();

        UUID transferId = UUID.randomUUID();
        PlayerTransferRecord prepared = PlayerTransferRecord.prepared(
                transferId, "jdbc-player-xuid", "source-node", "target-node", 17L, 100L);
        assertTrue(first.create(prepared));
        assertFalse(first.create(prepared), "Duplicate transfer IDs must be rejected");

        PlayerTransferRecord claim = new PlayerTransferCoordinator(first)
                .claim(transferId, "target-node", 101L, 5_000L).orElseThrow();
        assertEquals(PlayerTransferRecord.State.CLAIMED, claim.state());
        assertEquals(1L, claim.fencingToken());
        assertEquals(1L, claim.version());

        // A new DataSource represents a fresh node/process reconnecting to the shared database.
        JdbcPlayerTransferLedger afterRestart = new JdbcPlayerTransferLedger(dataSource(url));
        PlayerTransferRecord recovered = afterRestart.get(transferId).orElseThrow();
        assertEquals(claim, recovered);
        assertFalse(afterRestart.compareAndSet(
                transferId, 0L, recovered),
                "A stale expected version must never advance the durable record");
        PlayerTransferRecord tamperedIdentity = new PlayerTransferRecord(
                transferId, "different-player-xuid", recovered.sourceNode(), recovered.targetNode(),
                recovered.playerRevision(), PlayerTransferRecord.State.COMMITTED,
                recovered.fencingToken(), 102L, recovered.version() + 1, 102L);
        assertFalse(afterRestart.compareAndSet(
                transferId, recovered.version(), tamperedIdentity),
                "A CAS transition must not rewrite the authenticated player or transfer endpoints");
        PlayerTransferRecord regressedState = new PlayerTransferRecord(
                transferId, recovered.playerXuid(), recovered.sourceNode(), recovered.targetNode(),
                recovered.playerRevision(), PlayerTransferRecord.State.PREPARED,
                0L, 0L, recovered.version() + 1, 102L);
        assertFalse(afterRestart.compareAndSet(
                transferId, recovered.version(), regressedState),
                "A terminal or claimed transfer must never regress to PREPARED and reset its fence");

        PlayerTransferRecord committed = new PlayerTransferCoordinator(afterRestart)
                .commit(transferId, claim.fencingToken(), 102L).orElseThrow();
        assertEquals(PlayerTransferRecord.State.COMMITTED, committed.state());
        assertEquals(committed, first.get(transferId).orElseThrow());
    }

    @Test
    void expiredClaimIsReclaimedAfterLedgerReconnectWithHigherFence() throws Exception {
        String url = databaseUrl("reclaimed-ledger");
        JdbcPlayerTransferLedger first = new JdbcPlayerTransferLedger(dataSource(url));
        first.initializeSchema();
        UUID transferId = UUID.randomUUID();
        assertTrue(first.create(PlayerTransferRecord.prepared(
                transferId, "reclaim-player", "source-node", "target-node", 9L, 100L)));

        PlayerTransferRecord oldClaim = new PlayerTransferCoordinator(first)
                .claim(transferId, "target-node", 101L, 10L).orElseThrow();
        JdbcPlayerTransferLedger afterRestart = new JdbcPlayerTransferLedger(dataSource(url));
        PlayerTransferCoordinator recoveredNode = new PlayerTransferCoordinator(afterRestart);
        PlayerTransferRecord newClaim = recoveredNode.claim(
                transferId, "target-node", 111L, 1_000L).orElseThrow();

        assertEquals(oldClaim.fencingToken() + 1, newClaim.fencingToken());
        assertTrue(recoveredNode.commit(transferId, oldClaim.fencingToken(), 112L).isEmpty(),
                "A process that lost its lease cannot commit after another node reclaimed it");
        assertEquals(PlayerTransferRecord.State.COMMITTED,
                recoveredNode.commit(transferId, newClaim.fencingToken(), 112L).orElseThrow().state());
    }

    @Test
    void concurrentNodesCannotBothClaimTheSamePreparedTransfer() throws Exception {
        String url = databaseUrl("concurrent-ledger");
        JdbcPlayerTransferLedger first = new JdbcPlayerTransferLedger(dataSource(url));
        first.initializeSchema();
        JdbcPlayerTransferLedger second = new JdbcPlayerTransferLedger(dataSource(url));
        UUID transferId = UUID.randomUUID();
        assertTrue(first.create(PlayerTransferRecord.prepared(
                transferId, "concurrent-player", "source-node", "target-node", 3L, 200L)));

        PlayerTransferCoordinator nodeA = new PlayerTransferCoordinator(first);
        PlayerTransferCoordinator nodeB = new PlayerTransferCoordinator(second);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<java.util.Optional<PlayerTransferRecord>> a = workers.submit(() -> {
                start.await();
                return nodeA.claim(transferId, "target-node", 201L, 5_000L);
            });
            Future<java.util.Optional<PlayerTransferRecord>> b = workers.submit(() -> {
                start.await();
                return nodeB.claim(transferId, "target-node", 201L, 5_000L);
            });
            start.countDown();
            var claimA = a.get();
            var claimB = b.get();
            assertNotEquals(claimA.isPresent(), claimB.isPresent(),
                    "Exactly one concurrent claimant should win");
            PlayerTransferRecord winner = claimA.orElseGet(claimB::orElseThrow);
            assertEquals(PlayerTransferRecord.State.CLAIMED, winner.state());
            assertEquals(1L, winner.fencingToken());
            assertEquals(1L, first.get(transferId).orElseThrow().version());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void listsOnlyBoundedRecoverableRecordsForTheRequestedDestinationAfterRestart() throws Exception {
        String url = databaseUrl("recoverable-list");
        JdbcPlayerTransferLedger ledger = new JdbcPlayerTransferLedger(dataSource(url));
        ledger.initializeSchema();
        PlayerTransferCoordinator coordinator = new PlayerTransferCoordinator(ledger);

        UUID claimedId = UUID.randomUUID();
        UUID committedId = UUID.randomUUID();
        UUID preparedId = UUID.randomUUID();
        UUID abortedId = UUID.randomUUID();
        assertTrue(ledger.create(PlayerTransferRecord.prepared(
                claimedId, "claimed-player", "source-a", "target-a", 1L, 100L)));
        assertTrue(ledger.create(PlayerTransferRecord.prepared(
                committedId, "committed-player", "source-b", "target-a", 2L, 101L)));
        assertTrue(ledger.create(PlayerTransferRecord.prepared(
                preparedId, "prepared-player", "source-c", "target-a", 3L, 102L)));
        assertTrue(ledger.create(PlayerTransferRecord.prepared(
                abortedId, "aborted-player", "source-d", "target-a", 4L, 103L)));

        PlayerTransferRecord claim = coordinator.claim(claimedId, "target-a", 110L, 10_000L).orElseThrow();
        PlayerTransferRecord commitClaim = coordinator.claim(committedId, "target-a", 111L, 10_000L).orElseThrow();
        coordinator.commit(committedId, commitClaim.fencingToken(), 112L).orElseThrow();
        PlayerTransferRecord abortClaim = coordinator.claim(abortedId, "target-a", 113L, 10_000L).orElseThrow();
        coordinator.abort(abortedId, abortClaim.fencingToken(), 114L).orElseThrow();

        JdbcPlayerTransferLedger afterRestart = new JdbcPlayerTransferLedger(dataSource(url));
        var recovered = afterRestart.listRecoverableForTarget("target-a", 10);
        assertEquals(2, recovered.size());
        assertEquals(java.util.Set.of(claimedId, committedId),
                recovered.stream().map(PlayerTransferRecord::transferId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, afterRestart.listRecoverableForTarget("target-a", 1).size());
        assertTrue(afterRestart.listRecoverableForTarget("target-b", 10).isEmpty());
    }

    private String databaseUrl(String name) {
        String file = temporaryDirectory.resolve(name).toAbsolutePath().toString().replace('\\', '/');
        return "jdbc:h2:file:" + file + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000";
    }

    private static JdbcDataSource dataSource(String url) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
