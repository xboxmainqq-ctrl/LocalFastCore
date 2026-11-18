package ru.nexora.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlayerStateStoreFactoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void opensSharedJdbcCheckpointStoreFromEnvironment() throws Exception {
        String url = "jdbc:h2:mem:nexora-checkpoint-factory;DB_CLOSE_DELAY=-1";
        PlayerStateStoreFactory.ConfiguredStore configured = PlayerStateStoreFactory.open(
                temporaryDirectory,
                Map.of("NEXORA_PLAYER_STATE_JDBC_URL", url,
                        "NEXORA_PLAYER_STATE_JDBC_INITIALIZE_SCHEMA", "true"));

        assertTrue(configured.shared());
        assertFalse(configured.description().contains(url));
        assertEquals(PlayerStateStore.SaveResult.SAVED,
                configured.store().save(new PlayerStateSnapshot(1, "factory-state-xuid",
                        0L, 100L, new byte[] {4, 5, 6})));
        assertArrayEquals(new byte[] {4, 5, 6},
                configured.store().loadLatest("factory-state-xuid").orElseThrow().payload());
    }

    @Test
    void defaultsToLocalFileStoreAndRejectsInvalidSchemaFlag() throws Exception {
        PlayerStateStoreFactory.ConfiguredStore local =
                PlayerStateStoreFactory.open(temporaryDirectory, Map.of());
        assertFalse(local.shared());
        assertTrue(local.description().contains("single-host only"));

        assertThrows(IllegalArgumentException.class, () -> PlayerStateStoreFactory.open(
                temporaryDirectory,
                Map.of("NEXORA_PLAYER_STATE_JDBC_URL", "jdbc:h2:mem:invalid",
                        "NEXORA_PLAYER_STATE_JDBC_INITIALIZE_SCHEMA", "enabled")));
    }
}
