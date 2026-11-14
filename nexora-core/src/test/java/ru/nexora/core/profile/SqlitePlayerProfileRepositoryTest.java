package ru.nexora.core.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.abort;

class SqlitePlayerProfileRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndReloadsProfileAcrossRepositoryInstances() throws IOException {
        UUID playerId = UUID.randomUUID();
        Path database = temporaryDirectory.resolve("profiles.db");
        PlayerProfile expected = new PlayerProfile(playerId, "NexoraPlayer",
                Optional.of("mage"), 12, 845L, Map.of("strength", 4, "intelligence", 18));

        try (PlayerProfileRepository repository = new SqlitePlayerProfileRepository(database)) {
            repository.save(expected);
            assertEquals(Optional.of(expected), repository.find(playerId));
        }

        try (PlayerProfileRepository reopened = new SqlitePlayerProfileRepository(database)) {
            assertEquals(Optional.of(expected), reopened.find(playerId));
        }
    }

    @Test
    void updatesAndDeletesProfiles() throws IOException {
        UUID playerId = UUID.randomUUID();
        Path database = temporaryDirectory.resolve("profiles.db");
        try (PlayerProfileRepository repository = new SqlitePlayerProfileRepository(database)) {
            repository.save(PlayerProfile.create(playerId, "OldName"));
            PlayerProfile updated = PlayerProfile.create(playerId, "NewName")
                    .withClass("warrior").withProgress(3, 125L).withAttribute("strength", 7);
            repository.save(updated);

            assertEquals(Optional.of(updated), repository.find(playerId));
            assertTrue(repository.delete(playerId));
            assertFalse(repository.delete(playerId));
            assertTrue(repository.find(playerId).isEmpty());
        }
    }

    @Test
    void quickCheckReportsHealthyDatabase() throws IOException {
        try (SqlitePlayerProfileRepository repository =
                     new SqlitePlayerProfileRepository(temporaryDirectory.resolve("profiles.db"))) {
            assertDoesNotThrow(repository::verifyIntegrity);
        }
    }

    @Test
    void rejectsDatabaseCreatedByNewerSchemaVersion() throws Exception {
        Path database = temporaryDirectory.resolve("future-schema.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 99");
        }

        assertThrows(IOException.class, () -> new SqlitePlayerProfileRepository(database));
    }

    @Test
    void rejectsSymbolicLinkAsDatabasePath() throws IOException {
        Path target = temporaryDirectory.resolve("real.db");
        Files.createFile(target);
        Path link = temporaryDirectory.resolve("linked.db");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | SecurityException exception) {
            abort("Symbolic links are not supported in this test environment");
        } catch (IOException exception) {
            abort("Could not create a symbolic link in this test environment");
        }

        assertThrows(IOException.class, () -> new SqlitePlayerProfileRepository(link));
    }

    @Test
    void rejectsOperationsAfterClose() throws IOException {
        SqlitePlayerProfileRepository repository =
                new SqlitePlayerProfileRepository(temporaryDirectory.resolve("profiles.db"));
        repository.close();
        assertThrows(IOException.class, () -> repository.find(UUID.randomUUID()));
    }
}
