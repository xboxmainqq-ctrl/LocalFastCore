package ru.nexora.core.platform;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSnapshotTest {
    @Test
    void storesPlatformIndependentPlayerIdentity() {
        UUID id = UUID.randomUUID();

        PlayerSnapshot snapshot = new PlayerSnapshot(id, "NexoraPlayer");

        assertEquals(id, snapshot.uniqueId());
        assertEquals("NexoraPlayer", snapshot.name());
    }

    @Test
    void rejectsMissingIdentityAndBlankName() {
        assertThrows(NullPointerException.class, () -> new PlayerSnapshot(null, "Player"));
        assertThrows(NullPointerException.class, () -> new PlayerSnapshot(UUID.randomUUID(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerSnapshot(UUID.randomUUID(), " "));
    }
}
