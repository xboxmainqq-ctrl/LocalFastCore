package ru.nexora.core.profile;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerProgressionServiceTest {
    @Test
    void createsProfileAndUpdatesNameByUuid() throws IOException {
        InMemoryProfiles repository = new InMemoryProfiles();
        PlayerProgressionService service = new PlayerProgressionService(repository);
        UUID id = UUID.randomUUID();

        PlayerProfile first = service.loadOrCreate(id, "OldName");
        PlayerProfile renamed = service.loadOrCreate(id, "NewName");

        assertEquals(1, first.level());
        assertEquals("NewName", renamed.lastKnownName());
        assertEquals(1, repository.entries.size());
    }

    @Test
    void awardsLevelsAndFivePointsPerLevel() throws IOException {
        InMemoryProfiles repository = new InMemoryProfiles();
        PlayerProgressionService service = new PlayerProgressionService(repository);
        UUID id = UUID.randomUUID();
        service.loadOrCreate(id, "Player");

        PlayerProgressionService.ProgressResult result = service.addExperience(id, 300);

        assertEquals(3, result.profile().level());
        assertEquals(2, result.gainedLevels());
        assertEquals(10, result.unspentAttributePoints());
        assertEquals(300, result.profile().experience());
    }

    @Test
    void spendsOnlyAvailablePointsOnKnownAttributes() throws IOException {
        InMemoryProfiles repository = new InMemoryProfiles();
        PlayerProgressionService service = new PlayerProgressionService(repository);
        UUID id = UUID.randomUUID();
        service.loadOrCreate(id, "Player");
        service.addExperience(id, 100);

        PlayerProfile updated = service.spendAttributePoint(id, "strength");

        assertEquals(1, updated.attributes().get("strength"));
        assertEquals(4, updated.attributes().get(PlayerProgressionService.UNSPENT_POINTS));
        assertThrows(IllegalArgumentException.class,
                () -> service.spendAttributePoint(id, "admin"));
    }

    @Test
    void capsAtMaximumLevelAndRejectsInvalidExperience() throws IOException {
        InMemoryProfiles repository = new InMemoryProfiles();
        PlayerProgressionService service = new PlayerProgressionService(repository);
        UUID id = UUID.randomUUID();
        service.loadOrCreate(id, "Player");

        PlayerProgressionService.ProgressResult result = service.addExperience(id, Long.MAX_VALUE);

        assertEquals(PlayerProgressionService.MAX_LEVEL, result.profile().level());
        assertEquals(PlayerProgressionService.experienceRequiredForLevel(100),
                result.profile().experience());
        assertThrows(IllegalArgumentException.class, () -> service.addExperience(id, 0));
    }

    private static final class InMemoryProfiles implements PlayerProfileRepository {
        private final Map<UUID, PlayerProfile> entries = new HashMap<>();

        @Override public Optional<PlayerProfile> find(UUID id) {
            return Optional.ofNullable(entries.get(id));
        }
        @Override public void save(PlayerProfile profile) {
            entries.put(profile.uniqueId(), profile);
        }
        @Override public boolean delete(UUID id) {
            return entries.remove(id) != null;
        }
        @Override public void close() { }
    }
}
