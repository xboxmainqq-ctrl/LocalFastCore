package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerVitalsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void startsWithTenHeartsAndFullHunger() {
        PlayerVitals vitals = new PlayerVitals(UUID.randomUUID());
        assertEquals(20, vitals.maxHealth());
        assertEquals(20, vitals.health());
        assertEquals(10, vitals.maxHearts());
        assertEquals(20, vitals.hunger());
        assertEquals(1.0d, vitals.hungerDrainMultiplier());
    }

    @Test
    void bonusHeartsIncreaseHealthAndReduceHungerDrain() {
        PlayerVitals vitals = new PlayerVitals(UUID.randomUUID());
        assertTrue(vitals.increaseMaxHealth(8));
        assertEquals(28, vitals.maxHealth());
        assertEquals(28, vitals.health());
        assertEquals(14, vitals.maxHearts());
        assertEquals(0.8d, vitals.hungerDrainMultiplier(), 0.000001d);
        assertEquals(0.8d, vitals.applyExhaustion(1.0d), 0.000001d);
    }

    @Test
    void hungerReductionIsCappedAndHealthCannotExceedMaximum() {
        PlayerVitals vitals = new PlayerVitals(UUID.randomUUID());
        assertTrue(vitals.increaseMaxHealth(180));
        assertEquals(0.25d, vitals.hungerDrainMultiplier(), 0.000001d);
        assertEquals(200, vitals.maxHealth());
        assertEquals(0, vitals.heal(10));
        assertTrue(vitals.reduceMaxHealth(180));
        assertEquals(20, vitals.maxHealth());
        assertEquals(20, vitals.health());
        assertFalse(vitals.reduceMaxHealth(2));
    }

    @Test
    void damageHealingAndHungerAreBounded() {
        PlayerVitals vitals = new PlayerVitals(UUID.randomUUID());
        assertEquals(7, vitals.damage(7));
        assertEquals(13, vitals.health());
        assertEquals(7, vitals.heal(7));
        assertEquals(20, vitals.health());
        assertEquals(0, vitals.damage(-1));
        vitals.setHunger(99);
        assertEquals(20, vitals.hunger());
        vitals.setHunger(-1);
        assertEquals(0, vitals.hunger());
    }

    @Test
    void persistsAndRestoresProgression() throws IOException {
        UUID playerId = UUID.randomUUID();
        FilePlayerVitalsStorage storage =
                new FilePlayerVitalsStorage(temporaryDirectory.resolve("vitals"));
        PlayerVitals vitals = new PlayerVitals(playerId);
        assertTrue(vitals.increaseMaxHealth(10));
        vitals.damage(6);
        vitals.setHunger(13);

        storage.save(vitals);
        PlayerVitals loaded = storage.load(playerId);

        assertEquals(30, loaded.maxHealth());
        assertEquals(24, loaded.health());
        assertEquals(13, loaded.hunger());
        assertEquals(0.75d, loaded.hungerDrainMultiplier(), 0.000001d);
    }

    @Test
    void returnsDefaultsWhenNoSaveExists() throws IOException {
        PlayerVitals loaded = new FilePlayerVitalsStorage(
                temporaryDirectory.resolve("vitals")).load(UUID.randomUUID());
        assertEquals(20, loaded.maxHealth());
        assertEquals(20, loaded.health());
        assertEquals(20, loaded.hunger());
    }

    @Test
    void rejectsCorruptedSave() throws IOException {
        UUID playerId = UUID.randomUUID();
        Path directory = temporaryDirectory.resolve("vitals");
        FilePlayerVitalsStorage storage = new FilePlayerVitalsStorage(directory);
        storage.save(new PlayerVitals(playerId));
        Path file = directory.resolve(playerId + ".nxv");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 5] ^= 0x20;
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> storage.load(playerId));
    }
}
