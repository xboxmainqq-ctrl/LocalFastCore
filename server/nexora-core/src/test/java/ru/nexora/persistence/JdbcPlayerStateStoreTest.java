package ru.nexora.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class JdbcPlayerStateStoreTest {
    @Test
    void persistsSnapshotsAcrossIndependentDataSourcesAndEnforcesRevisionRules() throws Exception {
        String url = "jdbc:h2:mem:nexora-player-state;DB_CLOSE_DELAY=-1";
        JdbcPlayerStateStore first = new JdbcPlayerStateStore(new DriverManagerDataSource(url, null, null));
        JdbcPlayerStateStore second = new JdbcPlayerStateStore(new DriverManagerDataSource(url, null, null));
        first.initializeSchema();

        assertEquals(PlayerStateStore.SaveResult.SAVED,
                first.save(new PlayerStateSnapshot(1, "jdbc-xuid", 2L, 100L, new byte[] {1, 2, 3})));
        PlayerStateSnapshot loaded = second.loadLatest("jdbc-xuid").orElseThrow();
        assertArrayEquals(new byte[] {1, 2, 3}, loaded.payload());
        assertEquals(PlayerStateStore.SaveResult.STALE,
                second.save(new PlayerStateSnapshot(1, "jdbc-xuid", 1L, 101L, new byte[] {9})));
        assertEquals(PlayerStateStore.SaveResult.ALREADY_CURRENT,
                second.save(new PlayerStateSnapshot(1, "jdbc-xuid", 2L, 999L, new byte[] {1, 2, 3})));
        assertThrows(IllegalStateException.class,
                () -> second.save(new PlayerStateSnapshot(1, "jdbc-xuid", 2L, 100L, new byte[] {9})));
    }

    @Test
    void concurrentNodesCannotReplaceNewerPlayerRevisionWithOlderSnapshot() throws Exception {
        String url = "jdbc:h2:mem:nexora-player-state-race;DB_CLOSE_DELAY=-1";
        JdbcPlayerStateStore first = new JdbcPlayerStateStore(new DriverManagerDataSource(url, null, null));
        JdbcPlayerStateStore second = new JdbcPlayerStateStore(new DriverManagerDataSource(url, null, null));
        first.initializeSchema();
        first.save(new PlayerStateSnapshot(1, "race-xuid", 0L, 100L, new byte[] {0}));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PlayerStateStore.SaveResult> older = executor.submit(() ->
                    first.save(new PlayerStateSnapshot(1, "race-xuid", 1L, 101L, new byte[] {1})));
            Future<PlayerStateStore.SaveResult> newer = executor.submit(() ->
                    second.save(new PlayerStateSnapshot(1, "race-xuid", 2L, 102L, new byte[] {2})));
            older.get();
            newer.get();
            PlayerStateSnapshot latest = first.loadLatest("race-xuid").orElseThrow();
            assertEquals(2L, latest.revision());
            assertArrayEquals(new byte[] {2}, latest.payload());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsCorruptStoredChecksum() throws Exception {
        String url = "jdbc:h2:mem:nexora-player-state-corrupt;DB_CLOSE_DELAY=-1";
        JdbcPlayerStateStore store = new JdbcPlayerStateStore(new DriverManagerDataSource(url, null, null));
        store.initializeSchema();
        store.save(new PlayerStateSnapshot(1, "corrupt-xuid", 0L, 100L, new byte[] {1}));
        try (Connection connection = new DriverManagerDataSource(url, null, null).getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + JdbcPlayerStateStore.TABLE_NAME + " SET checksum = ? WHERE player_xuid = ?")) {
            statement.setString(1, "0".repeat(64));
            statement.setString(2, "corrupt-xuid");
            statement.executeUpdate();
        }
        assertThrows(IOException.class, () -> store.loadLatest("corrupt-xuid"));
    }
}
