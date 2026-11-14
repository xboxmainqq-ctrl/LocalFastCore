package ru.nexora.core.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

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
    void rejectsOperationsAfterClose() throws IOException {
        SqlitePlayerProfileRepository repository =
                new SqlitePlayerProfileRepository(temporaryDirectory.resolve("profiles.db"));
        repository.close();
        assertThrows(IOException.class, () -> repository.find(UUID.randomUUID()));
    }
}
