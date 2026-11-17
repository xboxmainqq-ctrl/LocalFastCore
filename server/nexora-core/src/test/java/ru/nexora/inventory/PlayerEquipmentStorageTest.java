package ru.nexora.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

class PlayerEquipmentStorageTest {
    @TempDir Path directory;

    @Test
    void roundTripsArmorOffhandCursorAndAllMagicalSlots() throws Exception {
        PlayerEquipmentStorage storage = new PlayerEquipmentStorage(directory);
        PlayerEquipment equipment = new PlayerEquipment();
        equipment.setArmor(0, new ItemStack("minecraft:diamond_chestplate", 1, 1,
                java.util.Map.of("minecraft:unbreaking", 2), 4, 87));
        equipment.setArmor(3, new ItemStack("minecraft:iron_ingot", 2, 64));
        equipment.setOffhand(new ItemStack("minecraft:ender_pearl", 4, 16));
        equipment.setCursor(new ItemStack("minecraft:bread", 1, 64));
        equipment.setMagicSlot(EquipmentSlot.MAGIC_STAFF, new ItemStack("nexora:ember_staff", 1, 1));
        equipment.setMagicSlot(EquipmentSlot.RING_LEFT, new ItemStack("nexora:power_ring", 1, 1));
        equipment.setMagicSlot(EquipmentSlot.RING_RIGHT, new ItemStack("nexora:ward_ring", 1, 1));
        equipment.setMagicSlot(EquipmentSlot.AMULET, new ItemStack("nexora:moon_amulet", 1, 1));
        equipment.setMagicSlot(EquipmentSlot.TALISMAN, new ItemStack("nexora:luck_talisman", 1, 1));
        equipment.setMagicSlot(EquipmentSlot.ARTIFACT_LEFT, new ItemStack("nexora:ancient_eye", 1, 1));
        equipment.setMagicSlot(EquipmentSlot.ARTIFACT_RIGHT, new ItemStack("nexora:phoenix_core", 1, 1));

        storage.save("equipment-player", equipment);
        PlayerEquipment loaded = storage.load("equipment-player").orElseThrow();
        assertEquals(equipment.snapshot(), loaded.snapshot());
        assertEquals(87, loaded.armor(0).orElseThrow().durabilityDamage());
        assertEquals(java.util.Map.of("minecraft:unbreaking", 2),
                loaded.armor(0).orElseThrow().enchantments());
        assertEquals(4, loaded.armor(0).orElseThrow().repairCost());
        assertTrue(loaded.armor(1).isEmpty());
        assertEquals("nexora:ember_staff",
                loaded.magicSlot(EquipmentSlot.MAGIC_STAFF).orElseThrow().itemId());
        assertEquals("nexora:phoenix_core",
                loaded.magicSlot(EquipmentSlot.ARTIFACT_RIGHT).orElseThrow().itemId());
    }

    @Test
    void loadsLegacyVersionOneEquipmentFilesWithEmptyMagicalSlots() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeInt(0x4e584551); // NXEQ
            out.writeByte(1);
            for (int i = 0; i < PlayerEquipment.ARMOR_SLOTS + 2; i++) out.writeBoolean(false);
        }
        byte[] payload = body.toByteArray();
        CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length);
        ByteArrayOutputStream complete = new ByteArrayOutputStream();
        complete.writeBytes(payload);
        try (DataOutputStream out = new DataOutputStream(complete)) {
            out.writeInt((int) crc.getValue());
        }
        Files.write(directory.resolve("legacy-player.nxe"), complete.toByteArray());

        PlayerEquipment loaded = new PlayerEquipmentStorage(directory).load("legacy-player").orElseThrow();
        assertTrue(loaded.armorSnapshot().stream().allMatch(Optional::isEmpty));
        assertTrue(loaded.magicSnapshot().stream().allMatch(Optional::isEmpty));
        assertTrue(loaded.offhand().isEmpty());
        assertTrue(loaded.cursor().isEmpty());
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
        assertThrows(IllegalArgumentException.class, () -> new PlayerEquipment.Snapshot(
                List.of(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                Optional.empty(), Optional.empty(), List.of(Optional.empty())));
    }
}
