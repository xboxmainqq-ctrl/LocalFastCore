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
    void persistsPlayerExperiencePoints() throws Exception {
        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        PlayerData player = new PlayerData("xp-player", "XpPlayer", "NEXORA",
                0, 65, 0, 0, 0, 3, PlayerVitals.DEFAULT, 9876, 350);
        storage.save(player);
        assertEquals(9876, storage.load("xp-player").orElseThrow().experiencePoints());
        assertEquals(350, storage.load("xp-player").orElseThrow().experienceRemainderMilli());
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
    void restoresPreviousVerifiedProfileWhenPrimaryChecksumIsCorrupted() throws Exception {
        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        PlayerData previous = new PlayerData("recover-player", "Before", "NEXORA",
                1, 65, 2, 0, 0, 10);
        PlayerData latest = new PlayerData("recover-player", "After", "NEXORA",
                9, 70, 8, 0, 0, 20);
        storage.save(previous);
        storage.save(latest);

        Path primary = temporaryDirectory.resolve("recover-player.nxp");
        Path backup = temporaryDirectory.resolve("recover-player.nxp.bak");
        byte[] damaged = Files.readAllBytes(primary);
        damaged[damaged.length - 1] ^= 1;
        Files.write(primary, damaged);

        assertEquals(previous, storage.load("recover-player").orElseThrow());
        assertArrayEquals(Files.readAllBytes(backup), Files.readAllBytes(primary));
        PlayerData newer = new PlayerData("recover-player", "Recovered", "NEXORA",
                11, 72, 12, 0, 0, 30);
        storage.save(newer);
        assertEquals(newer, storage.load("recover-player").orElseThrow());
    }

    @Test
    void failsClosedWhenBothProfileGenerationsAreCorrupted() throws Exception {
        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        storage.save(new PlayerData("double-fault", "First", "NEXORA", 0, 65, 0, 0, 0, 1));
        storage.save(new PlayerData("double-fault", "Second", "NEXORA", 1, 66, 1, 0, 0, 2));
        Path primary = temporaryDirectory.resolve("double-fault.nxp");
        Path backup = temporaryDirectory.resolve("double-fault.nxp.bak");
        for (Path file : new Path[]{primary, backup}) {
            byte[] damaged = Files.readAllBytes(file);
            damaged[damaged.length - 1] ^= 1;
            Files.write(file, damaged);
        }
        assertThrows(IOException.class, () -> storage.load("double-fault"));
    }

    @Test
    void concurrentProfileSavesKeepPrimaryAndBackupRecoverable() throws Exception {
        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        String xuid = "concurrent-profile";
        storage.save(new PlayerData(xuid, "Initial", "NEXORA", 0, 65, 0, 0, 0, 1));

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            java.util.List<java.util.concurrent.Future<?>> saves = new java.util.ArrayList<>();
            for (int worker = 0; worker < 6; worker++) {
                final int workerId = worker;
                saves.add(executor.submit(() -> {
                    for (int generation = 1; generation <= 25; generation++) {
                        PlayerData profile = new PlayerData(xuid, "Worker" + workerId, "NEXORA",
                                workerId + generation, 65, generation, 0, 0,
                                workerId * 100L + generation);
                        try {
                            storage.save(profile);
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    }
                }));
            }
            for (java.util.concurrent.Future<?> save : saves) save.get();
        } finally {
            executor.shutdownNow();
        }

        Path primary = temporaryDirectory.resolve(xuid + ".nxp");
        Path backup = temporaryDirectory.resolve(xuid + ".nxp.bak");
        assertTrue(storage.load(xuid).isPresent());
        assertTrue(Files.size(backup) > 0);

        byte[] damaged = Files.readAllBytes(primary);
        damaged[damaged.length - 1] ^= 1;
        Files.write(primary, damaged);
        assertTrue(storage.load(xuid).isPresent());
        assertArrayEquals(Files.readAllBytes(backup), Files.readAllBytes(primary));
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
    void rejectsMalformedUtf8EvenWhenProfileChecksumIsValid() throws Exception {
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bodyBytes)) {
            out.writeInt(0x4e58504c); // NXPL
            out.writeByte(1);
            writeString(out, "malformed-player");
            out.writeShort(1);
            out.writeByte(0xc3); // Incomplete two-byte UTF-8 sequence.
            writeString(out, "NEXORA");
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
        Files.write(temporaryDirectory.resolve("malformed-player.nxp"), fileBytes.toByteArray());

        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        assertThrows(IOException.class, () -> storage.load("malformed-player"));
    }

    @Test
    void returnsEmptyForPlayersWithoutSavedProfiles() throws Exception {
        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        assertTrue(storage.load("not-yet-seen").isEmpty());
    }
}
