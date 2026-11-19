package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    void exposesAggregateManualAndFinalSaveMeasurements() throws Exception {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        WorldAutosave autosave = new WorldAutosave(
                world, Duration.ofHours(1), ignored -> fail(ignored));

        autosave.saveNow();
        autosave.close();

        WorldAutosave.SaveStats stats = autosave.saveStats();
        assertEquals(2, stats.attempts());
        assertEquals(2, stats.successfulSaves());
        assertEquals(0, stats.failedSaves());
        assertTrue(stats.totalExecutionNanos() >= stats.maxExecutionNanos());
        assertTrue(stats.maxExecutionNanos() >= stats.lastExecutionNanos());
        assertTrue(stats.averageExecutionMillis() >= 0.0);
    }

    @Test
    void countsFailedSaveAttemptsWithoutLosingTheFailure() throws Exception {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        WorldAutosave autosave = new WorldAutosave(world, Duration.ofHours(1),
                ignored -> {}, () -> { throw new java.io.IOException("profile save failed"); });

        java.io.IOException failure = assertThrows(java.io.IOException.class, autosave::saveNow);
        assertTrue(failure.getMessage().contains("profile save failed"));
        WorldAutosave.SaveStats stats = autosave.saveStats();
        assertEquals(1, stats.attempts());
        assertEquals(0, stats.successfulSaves());
        assertEquals(1, stats.failedSaves());
        assertThrows(java.io.IOException.class, autosave::close);
    }

    @Test
    void rejectsNonPositiveAutosaveIntervals() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());

        assertThrows(IllegalArgumentException.class,
                () -> new WorldAutosave(world, Duration.ZERO, ignored -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldAutosave(world, Duration.ofMillis(-1), ignored -> {}));
    }
    @Test
    void reportsPeriodicSaveExecutionDuration() throws Exception {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        AtomicLong elapsedNanos = new AtomicLong(-1L);
        CountDownLatch observed = new CountDownLatch(1);
        WorldAutosave autosave = new WorldAutosave(
                world, Duration.ofMillis(10), ignored -> fail(ignored), () -> {},
                elapsed -> {
                    elapsedNanos.set(elapsed);
                    observed.countDown();
                });

        try {
            assertTrue(observed.await(2, TimeUnit.SECONDS));
            assertTrue(elapsedNanos.get() >= 0L);
        } finally {
            autosave.close();
        }
    }

}
