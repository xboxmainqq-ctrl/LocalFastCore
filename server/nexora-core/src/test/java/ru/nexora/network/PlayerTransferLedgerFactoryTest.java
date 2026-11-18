package ru.nexora.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTransferLedgerFactoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void opensAndInitializesSharedJdbcLedgerWithoutLoggingConnectionSecrets() throws Exception {
        String url = "jdbc:h2:mem:nexora-shared-ledger;DB_CLOSE_DELAY=-1";
        PlayerTransferLedgerFactory.ConfiguredLedger configured = PlayerTransferLedgerFactory.open(
                temporaryDirectory,
                Map.of(
                        "NEXORA_TRANSFER_JDBC_URL", url,
                        "NEXORA_TRANSFER_JDBC_USER", "sa",
                        "NEXORA_TRANSFER_JDBC_PASSWORD", "private-secret",
                        "NEXORA_TRANSFER_JDBC_INITIALIZE_SCHEMA", "true"));

        assertTrue(configured.shared());
        assertFalse(configured.description().contains("private-secret"));
        assertFalse(configured.description().contains(url));

        PlayerHandoff handoff = new PlayerHandoff(UUID.randomUUID(), 1L,
                "source-node", "target-node", new byte[0]);
        configured.coordinator().prepare(handoff, "factory-xuid", 7L, 100L);
        assertEquals(PlayerTransferRecord.State.PREPARED,
                configured.coordinator().get(handoff.transferId()).orElseThrow().state());
    }

    @Test
    void defaultsToSingleHostFileLedgerWhenJdbcUrlIsAbsent() throws Exception {
        PlayerTransferLedgerFactory.ConfiguredLedger configured =
                PlayerTransferLedgerFactory.open(temporaryDirectory, Map.of());

        assertFalse(configured.shared());
        assertTrue(configured.description().contains("single-host only"));
    }

    @Test
    void rejectsPartialJdbcCredentialsAndInvalidSchemaFlag() {
        assertThrows(IllegalArgumentException.class, () -> PlayerTransferLedgerFactory.open(
                temporaryDirectory,
                Map.of("NEXORA_TRANSFER_JDBC_URL", "jdbc:h2:mem:bad",
                        "NEXORA_TRANSFER_JDBC_USER", "sa")));
        assertThrows(IllegalArgumentException.class, () -> PlayerTransferLedgerFactory.open(
                temporaryDirectory,
                Map.of("NEXORA_TRANSFER_JDBC_URL", "jdbc:h2:mem:bad",
                        "NEXORA_TRANSFER_JDBC_INITIALIZE_SCHEMA", "sometimes")));
    }
}
