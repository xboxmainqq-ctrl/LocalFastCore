package ru.nexora.core.profile;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerProfileTest {
    @Test
    void createsDefaultProfileAndUpdatesWithoutMutation() {
        UUID id = UUID.randomUUID();
        PlayerProfile initial = PlayerProfile.create(id, "NexoraPlayer");

        PlayerProfile updated = initial
                .withClass("mage")
                .withProgress(4, 1250)
                .withAttribute("intelligence", 12);

        assertEquals(id, updated.uniqueId());
        assertEquals("NexoraPlayer", updated.lastKnownName());
        assertEquals("mage", updated.classId().orElseThrow());
        assertEquals(4, updated.level());
        assertEquals(1250L, updated.experience());
        assertEquals(12, updated.attributes().get("intelligence"));
        assertTrue(initial.classId().isEmpty());
        assertEquals(1, initial.level());
        assertTrue(initial.attributes().isEmpty());
    }

    @Test
    void attributesAreDefensivelyCopiedAndImmutable() {
        Map<String, Integer> source = new HashMap<>();
        source.put("strength", 5);
        PlayerProfile profile = new PlayerProfile(
                UUID.randomUUID(), "Player", java.util.Optional.empty(), 1, 0, source);

        source.put("strength", 99);
        assertEquals(5, profile.attributes().get("strength"));
        assertThrows(UnsupportedOperationException.class,
                () -> profile.attributes().put("agility", 7));
    }

    @Test
    void rejectsInvalidProgressAndNames() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> PlayerProfile.create(id, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerProfile(id, "Player", java.util.Optional.empty(), 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerProfile(id, "Player", java.util.Optional.empty(), 1, -1, Map.of()));
    }
}
