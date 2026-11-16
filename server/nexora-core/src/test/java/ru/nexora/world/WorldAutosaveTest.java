package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorldAutosaveTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void performsFinalSaveWhenClosed() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        World world = new World("NEXORA", 42L, new FlatWorldGenerator(), 0, 65, 0, storage);
        WorldAutosave autosave = new WorldAutosave(world, Duration.ofHours(1), ignored -> fail(ignored));

        world.setBlock(2, 70, 3, BlockType.STONE);
        autosave.close();
        autosave.close();

        World restarted = new World("NEXORA", 42L, new FlatWorldGenerator(), 0, 65, 0, storage);
        assertEquals(BlockType.STONE, restarted.getBlock(2, 70, 3));
        assertThrows(IllegalStateException.class, autosave::saveNow);
    }

    @Test
    void invokesAdditionalProfileSaveDuringManualAndFinalFlush() throws Exception {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        AtomicInteger profileSaves = new AtomicInteger();
        WorldAutosave autosave = new WorldAutosave(
                world, Duration.ofHours(1), ignored -> {}, profileSaves::incrementAndGet);

        autosave.saveNow();
        autosave.close();

        assertEquals(2, profileSaves.get());
    }

    @Test
    void rejectsNonPositiveAutosaveIntervals() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());

        assertThrows(IllegalArgumentException.class,
                () -> new WorldAutosave(world, Duration.ZERO, ignored -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldAutosave(world, Duration.ofMillis(-1), ignored -> {}));
    }
}
