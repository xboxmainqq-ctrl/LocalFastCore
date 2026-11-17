package ru.nexora.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FurnaceStateStorageTest {
    @TempDir Path temporaryDirectory;

    @Test
    void savesAndRestoresProgressAcrossRestart() throws Exception {
        FurnaceStateStorage storage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        FurnaceService.FurnaceStateSnapshot expected =
                new FurnaceService.FurnaceStateSnapshot(1250, 87);

        storage.save(-12, 64, 31, expected);

        assertEquals(expected, storage.load(-12, 64, 31).orElseThrow());
        assertTrue(storage.load(-12, 64, 32).isEmpty());
    }

    @Test
    void rejectsCorruptedStateAndCanDeleteIt() throws Exception {
        FurnaceStateStorage storage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        storage.save(1, 2, 3, new FurnaceService.FurnaceStateSnapshot(100, 50));
        Path file = temporaryDirectory.resolve("NEXORA/furnaces/1_2_3.nxf");
        byte[] bytes = Files.readAllBytes(file);
        bytes[8] ^= 0x01;
        Files.write(file, bytes);

        assertThrows(java.io.IOException.class, () -> storage.load(1, 2, 3));
        storage.delete(1, 2, 3);
        assertTrue(storage.load(1, 2, 3).isEmpty());
    }

    @Test
    void rejectsInvalidStateValues() throws Exception {
        FurnaceStateStorage storage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        assertThrows(IllegalArgumentException.class, () ->
                storage.save(0, 0, 0, new FurnaceService.FurnaceStateSnapshot(2401, 0)));
        assertThrows(IllegalArgumentException.class, () ->
                storage.save(0, 0, 0, new FurnaceService.FurnaceStateSnapshot(0, 200)));
    }
}
