package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlayerDataStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndRestoresPlayerIdentityAndLocation() throws Exception {
        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        PlayerData player = new PlayerData(
                "123456789", "NexoraPlayer", "NEXORA World",
                -15.5f, 70.0f, 32.25f, 135.0f, 12.0f, 1_800_000_000_000L);

        storage.save(player);

        assertEquals(player, storage.load("123456789").orElseThrow());
    }

    @Test
    void rejectsCorruptedPlayerProfiles() throws Exception {
        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        storage.save(new PlayerData("123", "Player", "NEXORA", 0, 65, 0, 0, 0, 1));
        Path file = temporaryDirectory.resolve("123.nxp");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 1;
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> storage.load("123"));
    }

    @Test
    void rejectsUnsafeXuidAndOutOfWorldCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerData(
                "../outside", "Player", "NEXORA", 0, 65, 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PlayerData(
                "123", "Player", "NEXORA", 0, 9999, 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PlayerData(
                "123", "Player", "NEXORA", Float.NaN, 65, 0, 0, 0, 1));
    }

    @Test
    void returnsEmptyForPlayersWithoutSavedProfiles() throws Exception {
        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        assertTrue(storage.load("not-yet-seen").isEmpty());
    }
}
