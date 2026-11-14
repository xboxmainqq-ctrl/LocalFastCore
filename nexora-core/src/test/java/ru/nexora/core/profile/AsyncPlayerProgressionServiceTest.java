package ru.nexora.core.profile;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AsyncPlayerProgressionServiceTest {
    @Test
    void executesProfileOperationsOffCallerThreadAndPreservesOrder() throws Exception {
        InMemoryProfiles repository = new InMemoryProfiles();
        PlayerProgressionService progression = new PlayerProgressionService(repository);
        try (AsyncPlayerProgressionService async = new AsyncPlayerProgressionService(progression, 32)) {
            UUID id = UUID.randomUUID();
            PlayerProfile created = async.loadOrCreate(id, "MobilePlayer").get();
            assertEquals(1, created.level());

            PlayerProgressionService.ProgressResult result = async.addExperience(id, 100).get();
            assertEquals(2, result.profile().level());

            PlayerProfile allocated = async.spendAttributePoint(id, "strength").get();
            assertEquals(1, allocated.attributes().get("strength"));
        }
    }

    @Test
    void rejectsWorkAfterGracefulClose() throws Exception {
        AsyncPlayerProgressionService async = new AsyncPlayerProgressionService(
                new PlayerProgressionService(new InMemoryProfiles()), 4);
        assertTrue(async.closeGracefully(Duration.ofSeconds(1)));
        assertTrue(async.isClosed());
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> async.loadOrCreate(UUID.randomUUID(), "Player").get());
    }

    @Test
    void rejectsInvalidQueueCapacity() {
        assertThrows(IllegalArgumentException.class, () ->
                new AsyncPlayerProgressionService(
                        new PlayerProgressionService(new InMemoryProfiles()), 0));
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
