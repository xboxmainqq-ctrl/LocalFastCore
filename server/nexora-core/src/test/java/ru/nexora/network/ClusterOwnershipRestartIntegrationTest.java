package ru.nexora.network;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.PlayerEquipment;
import ru.nexora.inventory.PlayerInventory;
import ru.nexora.persistence.JdbcPlayerStateStore;
import ru.nexora.persistence.PlayerStateBundle;
import ru.nexora.persistence.PlayerStateBundleCodec;
import ru.nexora.persistence.PlayerStateCheckpointService;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerVitals;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClusterOwnershipRestartIntegrationTest {
    @Test
    void committedTransferSurvivesNodeRegistryRestartAndFencesFormerOwner() throws Exception {
        String url = "jdbc:h2:mem:cluster-restart-committed;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(100L);
        JdbcPlayerTransferLedger ledgerBeforeRestart = new JdbcPlayerTransferLedger(dataSource(url));
        JdbcPlayerOnlineLeaseRegistry leasesBeforeRestart = new JdbcPlayerOnlineLeaseRegistry(dataSource(url), clock::get);
        JdbcPlayerStateStore stateBeforeRestart = new JdbcPlayerStateStore(dataSource(url));
        ledgerBeforeRestart.initializeSchema();
        leasesBeforeRestart.initializeSchema();
        stateBeforeRestart.initializeSchema();
        PlayerStateCheckpointService checkpointsBeforeRestart =
                new PlayerStateCheckpointService(stateBeforeRestart, new PlayerStateBundleCodec());

        String xuid = "restart-committed-xuid";
        long revision = checkpointsBeforeRestart.checkpointForTransfer(bundle(xuid), 100L);
        assertTrue(leasesBeforeRestart.acquire(xuid, "node-a", "session-a", 100L, 1_000L));
        assertTrue(leasesBeforeRestart.release(xuid, "node-a", "session-a"));

        UUID transferId = UUID.randomUUID();
        DurableHandoffCoordinator sourceLedger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(ledgerBeforeRestart));
        sourceLedger.prepare(new PlayerHandoff(transferId, 1L, "node-a", "node-b", new byte[0]),
                xuid, revision, 101L);
        PlayerTransferRecord claim = sourceLedger.claim(transferId, "node-b", 102L, 1_000L).orElseThrow();
        sourceLedger.commit(transferId, claim.fencingToken(), 103L).orElseThrow();

        // New DataSource instances model both server processes restarting against durable shared SQL.
        JdbcPlayerTransferLedger ledgerAfterRestart = new JdbcPlayerTransferLedger(dataSource(url));
        JdbcPlayerOnlineLeaseRegistry leasesAfterRestart = new JdbcPlayerOnlineLeaseRegistry(dataSource(url), clock::get);
        JdbcPlayerStateStore stateAfterRestart = new JdbcPlayerStateStore(dataSource(url));
        DurableHandoffCoordinator recoveredLedger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(ledgerAfterRestart));
        PlayerTransferRecord committed = recoveredLedger.get(transferId).orElseThrow();

        assertThrows(SecurityException.class,
                () -> PlayerTransferOwnershipGuard.loginDecision(Optional.of(committed), "node-a"));
        assertEquals(PlayerTransferOwnershipGuard.LoginDecision.RESTORE_CANONICAL,
                PlayerTransferOwnershipGuard.loginDecision(Optional.of(committed), "node-b"));
        PlayerStateCheckpointService recoveredCheckpoints =
                new PlayerStateCheckpointService(stateAfterRestart, new PlayerStateBundleCodec());
        assertEquals(revision, recoveredCheckpoints.loadAtRevision(xuid, revision).orElseThrow().revision());

        clock.set(104L);
        assertTrue(leasesAfterRestart.acquire(xuid, "node-b", "session-b", 104L, 1_000L));
        clock.set(105L);
        assertFalse(leasesAfterRestart.acquire(xuid, "node-a", "session-a2", 105L, 1_000L));
        assertFalse(leasesAfterRestart.renew(xuid, "node-a", "session-a", 105L, 1_000L));
        clock.set(1_105L);
        assertFalse(leasesAfterRestart.renew(xuid, "node-b", "session-b", 1_105L, 1_000L),
                "A crashed destination cannot resurrect an expired session lease");
        assertThrows(SecurityException.class,
                () -> PlayerTransferOwnershipGuard.loginDecision(Optional.of(committed), "node-a"));
        assertTrue(leasesAfterRestart.acquire(xuid, "node-b", "session-b-restarted", 1_105L, 1_000L));
    }

    @Test
    void abortedTransferRestoresSourceOwnershipAfterRestart() throws Exception {
        String url = "jdbc:h2:mem:cluster-restart-aborted;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        JdbcPlayerTransferLedger first = new JdbcPlayerTransferLedger(dataSource(url));
        first.initializeSchema();
        UUID transferId = UUID.randomUUID();
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(new PlayerTransferCoordinator(first));
        ledger.prepare(new PlayerHandoff(transferId, 1L, "node-a", "node-b", new byte[0]),
                "restart-aborted-xuid", 1L, 100L);
        PlayerTransferRecord claim = ledger.claim(transferId, "node-b", 101L, 1_000L).orElseThrow();
        ledger.abort(transferId, claim.fencingToken(), 102L).orElseThrow();

        PlayerTransferRecord recovered = new JdbcPlayerTransferLedger(dataSource(url))
                .get(transferId).orElseThrow();
        assertEquals(PlayerTransferOwnershipGuard.LoginDecision.RESTORE_CANONICAL,
                PlayerTransferOwnershipGuard.loginDecision(Optional.of(recovered), "node-a"));
        assertThrows(SecurityException.class,
                () -> PlayerTransferOwnershipGuard.loginDecision(Optional.of(recovered), "node-b"));
    }

    private static JdbcDataSource dataSource(String url) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(url);
        return source;
    }

    private static PlayerStateBundle bundle(String xuid) {
        PlayerData profile = new PlayerData(xuid, "RestartPlayer", "world",
                12.0f, 65.0f, -8.0f, 90.0f, 0.0f, 100L, PlayerVitals.DEFAULT, 37, 200);
        return PlayerStateBundle.capture(profile, new PlayerInventory(),
                new ContainerInventory(PlayerStateBundle.CRAFTING_UI_SLOT_COUNT), new PlayerEquipment());
    }
}
