package ru.nexora.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContainerInventoryStorageTest {
    @TempDir
    Path directory;

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
