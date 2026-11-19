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
                new FurnaceService.FurnaceStateSnapshot(1250, 87, 1600);

        storage.save(-12, 64, 31, expected);

        assertEquals(expected, storage.load(-12, 64, 31).orElseThrow());
        assertTrue(storage.load(-12, 64, 32).isEmpty());
    }

    @Test
    void persistsCoalBlockBurnDuration() throws Exception {
        FurnaceStateStorage storage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        FurnaceService.FurnaceStateSnapshot expected =
                new FurnaceService.FurnaceStateSnapshot(15999, 50, 16000, 1750);
        storage.save(2, 64, 2, expected);
        assertEquals(expected, storage.load(2, 64, 2).orElseThrow());
    }

    @Test
    void rejectsInvalidPendingExperience() throws Exception {
        FurnaceStateStorage storage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        assertThrows(IllegalArgumentException.class, () -> storage.save(0, 0, 0,
                new FurnaceService.FurnaceStateSnapshot(0, 0, 0,
                        FurnaceService.MAX_PENDING_EXPERIENCE_MILLI + 1)));
    }

    @Test
    void rejectsCorruptedStateAndCanDeleteIt() throws Exception {
        FurnaceStateStorage storage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        storage.save(1, 2, 3, new FurnaceService.FurnaceStateSnapshot(100, 50, 1600));
        Path file = temporaryDirectory.resolve("NEXORA/furnaces/1_2_3.nxf");
        byte[] bytes = Files.readAllBytes(file);
        bytes[8] ^= 0x01;
        Files.write(file, bytes);

        assertThrows(java.io.IOException.class, () -> storage.load(1, 2, 3));
        storage.delete(1, 2, 3);
        assertTrue(storage.load(1, 2, 3).isEmpty());
    }

    @Test
    void restoresPreviousVerifiedFurnaceStateWhenPrimaryIsCorrupted() throws Exception {
        FurnaceStateStorage storage = new FurnaceStateStorage(temporaryDirectory, "recovery-world");
        var first = new FurnaceService.FurnaceStateSnapshot(100, 20, 200);
        var second = new FurnaceService.FurnaceStateSnapshot(150, 40, 200);
        storage.save(4, 65, 9, first);
        storage.save(4, 65, 9, second);
        Path primary = temporaryDirectory.resolve("recovery-world/furnaces/4_65_9.nxf");
        Path backup = temporaryDirectory.resolve("recovery-world/furnaces/4_65_9.nxf.bak");
        byte[] damaged = Files.readAllBytes(primary);
        damaged[8] ^= 1;
        Files.write(primary, damaged);

        assertEquals(first, storage.load(4, 65, 9).orElseThrow());
        assertArrayEquals(Files.readAllBytes(backup), Files.readAllBytes(primary));
    }

    @Test
    void rejectsInvalidStateValues() throws Exception {
        FurnaceStateStorage storage = new FurnaceStateStorage(temporaryDirectory, "NEXORA");
        assertThrows(IllegalArgumentException.class, () ->
                storage.save(0, 0, 0, new FurnaceService.FurnaceStateSnapshot(16001, 0, 16001)));
        assertThrows(IllegalArgumentException.class, () ->
                storage.save(0, 0, 0, new FurnaceService.FurnaceStateSnapshot(0, 200, 0)));
    }
}
