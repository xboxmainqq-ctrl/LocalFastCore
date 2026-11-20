package ru.nexora.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Map;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

class ContainerInventoryStorageTest {
    @TempDir
    Path directory;


    @Test
    void roundTripsEnchantmentAndRepairMetadata() throws Exception {
        ContainerInventoryStorage storage = new ContainerInventoryStorage(directory, "metadata-world");
        ContainerInventory inventory = new ContainerInventory(27);
        ItemStack enchanted = new ItemStack("minecraft:diamond_sword", 1, 1,
                Map.of("minecraft:sharpness", 5, "minecraft:unbreaking", 3), 12);
        inventory.setSlot(4, enchanted);

        storage.save(4, 70, -8, inventory);

        assertEquals(enchanted, storage.load(4, 70, -8).orElseThrow().getSlot(4).orElseThrow());
    }

    @Test
    void loadsLegacyVersionOneContainerFilesWithoutMetadata() throws Exception {
        String worldName = "legacy-world";
        int x = -2, y = 64, z = 7;
        ContainerInventoryStorage storage = new ContainerInventoryStorage(directory, worldName);
        Path file = directory.resolve(worldName).resolve("containers")
                .resolve(x + "_" + y + "_" + z + ".nxc");
        byte[] itemId = "minecraft:diamond_sword".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bodyBytes)) {
            out.writeInt(0x4e584354); // NXCT
            out.writeByte(1);
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            out.writeShort(27);
            out.writeBoolean(true);
            out.writeByte(itemId.length);
            out.write(itemId);
            out.writeByte(1);
            out.writeByte(1);
            for (int slot = 1; slot < 27; slot++) out.writeBoolean(false);
        }
        byte[] body = bodyBytes.toByteArray();
        CRC32C checksum = new CRC32C();
        checksum.update(body, 0, body.length);
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        fileBytes.writeBytes(body);
        try (DataOutputStream out = new DataOutputStream(fileBytes)) {
            out.writeInt((int) checksum.getValue());
        }
        Files.write(file, fileBytes.toByteArray());

        ItemStack restored = storage.load(x, y, z).orElseThrow().getSlot(0).orElseThrow();
        assertEquals(new ItemStack("minecraft:diamond_sword", 1, 1), restored);
    }

    @Test
    void restoresPreviousVerifiedContainerWhenPrimaryIsCorruptedAndDeleteRemovesBackup() throws Exception {
        ContainerInventoryStorage storage = new ContainerInventoryStorage(directory, "recovery-world");
        ContainerInventory first = new ContainerInventory(27);
        first.setSlot(0, new ItemStack("minecraft:iron_ingot", 4, 64));
        ContainerInventory second = new ContainerInventory(27);
        second.setSlot(0, new ItemStack("minecraft:diamond", 2, 64));
        storage.save(1, 65, -2, first);
        storage.save(1, 65, -2, second);

        Path primary = directory.resolve("recovery-world/containers/1_65_-2.nxc");
        Path backup = directory.resolve("recovery-world/containers/1_65_-2.nxc.bak");
        byte[] damaged = Files.readAllBytes(primary);
        damaged[5] ^= 1;
        Files.write(primary, damaged);

        assertEquals(first.getSlot(0), storage.load(1, 65, -2).orElseThrow().getSlot(0));
        assertArrayEquals(Files.readAllBytes(backup), Files.readAllBytes(primary));

        storage.delete(1, 65, -2);
        assertFalse(Files.exists(primary));
        assertFalse(Files.exists(backup));
        assertTrue(storage.load(1, 65, -2).isEmpty());
    }
}
