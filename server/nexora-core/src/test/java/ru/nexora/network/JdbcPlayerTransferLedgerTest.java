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
