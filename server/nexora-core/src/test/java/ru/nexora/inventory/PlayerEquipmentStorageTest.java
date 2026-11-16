package ru.nexora.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PlayerEquipmentStorageTest {
    @TempDir Path directory;

    @Test
    void roundTripsArmorOffhandAndCursor() throws Exception {
        PlayerEquipmentStorage storage = new PlayerEquipmentStorage(directory);
        PlayerEquipment equipment = new PlayerEquipment();
        equipment.setArmor(0, new ItemStack("minecraft:diamond", 1, 64));
        equipment.setArmor(3, new ItemStack("minecraft:iron_ingot", 2, 64));
        equipment.setOffhand(new ItemStack("minecraft:ender_pearl", 4, 16));
        equipment.setCursor(new ItemStack("minecraft:bread", 1, 64));

        storage.save("equipment-player", equipment);
        PlayerEquipment loaded = storage.load("equipment-player").orElseThrow();
        assertEquals(equipment.snapshot(), loaded.snapshot());
        assertTrue(loaded.armor(1).isEmpty());
    }

    @Test
    void returnsEmptyForMissingAndRejectsChecksumCorruption() throws Exception {
        PlayerEquipmentStorage storage = new PlayerEquipmentStorage(directory);
        assertTrue(storage.load("missing-player").isEmpty());
        PlayerEquipment equipment = new PlayerEquipment();
        equipment.setOffhand(new ItemStack("minecraft:shield", 1, 1));
        storage.save("corrupt-player", equipment);
        Path file = directory.resolve("corrupt-player.nxe");
        byte[] bytes = Files.readAllBytes(file);
        bytes[6] ^= 1;
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> storage.load("corrupt-player"));
    }

    @Test
    void rejectsInvalidXuid() throws Exception {
        PlayerEquipmentStorage storage = new PlayerEquipmentStorage(directory);
        assertThrows(IllegalArgumentException.class, () -> storage.save("../escape", new PlayerEquipment()));
        assertThrows(IllegalArgumentException.class, () -> storage.load("../escape"));
    }

    @Test
    void rejectsInvalidEquipmentSnapshotShape() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerEquipment.Snapshot(
                List.of(Optional.empty()), Optional.empty(), Optional.empty()));
    }
}
