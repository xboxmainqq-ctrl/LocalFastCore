package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;
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
    void savesAndRestoresSurvivalVitals() throws Exception {
        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        PlayerData player = new PlayerData(
                "vitals-player", "VitalsPlayer", "NEXORA World",
                1, 65, 2, 0, 0, 2,
                new PlayerVitals(13.5f, 8, 2.5f, 1.25f));
        storage.save(player);
        assertEquals(player, storage.load("vitals-player").orElseThrow());
    }

    @Test
    void migratesVersionOneProfilesToDefaultSurvivalVitals() throws Exception {
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bodyBytes)) {
            out.writeInt(0x4e58504c); // NXPL
            out.writeByte(1);
            writeString(out, "legacy-player");
            writeString(out, "LegacyPlayer");
            writeString(out, "NEXORA World");
            out.writeFloat(0); out.writeFloat(65); out.writeFloat(0);
            out.writeFloat(0); out.writeFloat(0);
            out.writeLong(1);
        }
        byte[] body = bodyBytes.toByteArray();
        CRC32C crc = new CRC32C();
        crc.update(body, 0, body.length);
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        fileBytes.writeBytes(body);
        try (DataOutputStream out = new DataOutputStream(fileBytes)) {
            out.writeInt((int) crc.getValue());
        }
        Files.write(temporaryDirectory.resolve("legacy-player.nxp"), fileBytes.toByteArray());

        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        assertEquals(PlayerVitals.DEFAULT, storage.load("legacy-player").orElseThrow().vitals());
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

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    @Test
    void returnsEmptyForPlayersWithoutSavedProfiles() throws Exception {
        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        assertTrue(storage.load("not-yet-seen").isEmpty());
    }
}
