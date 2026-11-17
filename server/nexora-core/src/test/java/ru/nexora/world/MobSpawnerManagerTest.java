package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.io.IOException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MobSpawnerManagerTest {
    private World createWorld() {
        World world = new World("mob-spawner-test", 0L, new FlatWorldGenerator());
        world.setBlock(0, 65, 0, BlockType.SPAWNER);
        return world;
    }

    @Test
    void spawnerWaitsForDelayAndRequiresNearbyPlayer() {
        World world = createWorld();
        MobSpawnerManager manager = world.mobSpawners();
        assertTrue(manager.register(0, 65, 0, 0));
        AtomicInteger spawned = new AtomicInteger();
        long baseTick = manager.snapshot().get(0).nextSpawnTick()
                - MobSpawnerState.DEFAULT_DELAY_TICKS;

        var inactive = manager.tick(world, baseTick + 100,
                List.of(new MobSpawnerManager.PlayerPosition(100, 65, 100)),
                request -> { spawned.incrementAndGet(); return true; });
        assertEquals(0, inactive.activeSpawners());
        assertEquals(0, spawned.get());
        assertEquals(baseTick + 300, manager.snapshot().get(0).nextSpawnTick());

        var early = manager.tick(world, baseTick + 200,
                List.of(new MobSpawnerManager.PlayerPosition(0.5, 65, 0.5)),
                request -> { spawned.incrementAndGet(); return true; });
        assertEquals(0, early.spawned());

        var due = manager.tick(world, baseTick + 300,
                List.of(new MobSpawnerManager.PlayerPosition(0.5, 65, 0.5)),
                request -> { spawned.incrementAndGet(); return true; });
        assertTrue(due.spawned() > 0);
        assertEquals(due.spawned(), spawned.get());
    }

    @Test
    void inactiveCountdownPausesAcrossElapsedServerTicks() {
        World world = createWorld();
        MobSpawnerManager manager = new MobSpawnerManager();
        assertTrue(manager.register(0, 65, 0, 0));

        manager.tick(world, 0, List.of(), request -> true);
        manager.tick(world, 1, List.of(), request -> true);
        manager.tick(world, 100, List.of(), request -> true);
        assertEquals(300, manager.snapshot().get(0).nextSpawnTick());

        var early = manager.tick(world, 299,
                List.of(new MobSpawnerManager.PlayerPosition(0.5, 65, 0.5)), request -> true);
        assertEquals(0, early.spawned());
        var due = manager.tick(world, 300,
                List.of(new MobSpawnerManager.PlayerPosition(0.5, 65, 0.5)), request -> true);
        assertTrue(due.spawned() > 0);
    }

    @Test
    void nearbySameTypeCapPreventsMoreSpawns() {
        World world = createWorld();
        MobSpawnerManager manager = world.mobSpawners();
        manager.register(0, 65, 0, 0);
        for (int i = 0; i < MobSpawnerManager.MAX_NEARBY_SAME_TYPE; i++) {
            world.livingEntities().spawn("minecraft:zombie", 20, 0.5 + i * 0.1, 65, 0.5);
        }

        var result = manager.tick(world, manager.snapshot().get(0).nextSpawnTick(),
                List.of(new MobSpawnerManager.PlayerPosition(0.5, 65, 0.5)), request -> true);

        assertEquals(0, result.spawned());
        assertEquals(1, result.capped());
    }

    @Test
    void invalidSpawnSpaceDoesNotCallSpawnHandler() {
        World world = createWorld();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (x == 0 && z == 0) continue;
                world.setBlock(x, 65, z, BlockType.STONE);
            }
        }
        MobSpawnerManager manager = world.mobSpawners();
        manager.register(0, 65, 0, 0);
        AtomicInteger calls = new AtomicInteger();

        var result = manager.tick(world, manager.snapshot().get(0).nextSpawnTick(),
                List.of(new MobSpawnerManager.PlayerPosition(0.5, 65, 0.5)),
                request -> { calls.incrementAndGet(); return true; });

        assertEquals(0, calls.get());
        assertEquals(0, result.spawned());
        assertEquals(MobSpawnerManager.MAX_SPAWN_ATTEMPTS, result.invalidPositions());
    }

    @Test
    void spawnersInUnloadedChunksDoNotForceChunkLoads() {
        World world = new World("unloaded-spawner-test", 0L, new FlatWorldGenerator());
        MobSpawnerManager manager = world.mobSpawners();
        assertTrue(manager.register(32, 65, 32, System.currentTimeMillis() / 50L));
        assertEquals(0, world.loadedChunkCount());

        manager.tick(world, manager.snapshot().get(0).nextSpawnTick(),
                List.of(new MobSpawnerManager.PlayerPosition(32.5, 65, 32.5)), request -> true);

        assertEquals(0, world.loadedChunkCount());
    }

    @Test
    void nearbySpawnersShareTheSameTypeCapDuringOneTick() {
        World world = new World("shared-spawner-cap-test", 0L, new FlatWorldGenerator());
        world.setBlock(0, 65, 0, BlockType.SPAWNER);
        world.setBlock(1, 65, 0, BlockType.SPAWNER);
        MobSpawnerManager manager = world.mobSpawners();
        long due = manager.snapshot().stream().mapToLong(MobSpawnerState::nextSpawnTick).max().orElseThrow();
        AtomicInteger spawned = new AtomicInteger();

        manager.tick(world, due,
                List.of(new MobSpawnerManager.PlayerPosition(0.5, 65, 0.5)),
                request -> { spawned.incrementAndGet(); return true; });

        assertTrue(spawned.get() <= MobSpawnerManager.MAX_NEARBY_SAME_TYPE);
    }

    @Test
    void stopsFurtherAttemptsWhenSpawnerIsRemovedDuringSpawnCallback() {
        World world = createWorld();
        MobSpawnerManager manager = world.mobSpawners();
        AtomicInteger spawned = new AtomicInteger();
        long due = manager.snapshot().get(0).nextSpawnTick();

        var result = manager.tick(world, due,
                List.of(new MobSpawnerManager.PlayerPosition(0.5, 65, 0.5)),
                request -> {
                    spawned.incrementAndGet();
                    world.setBlock(0, 65, 0, BlockType.AIR);
                    return true;
                });

        assertEquals(1, spawned.get());
        assertEquals(1, result.spawned());
        assertTrue(manager.snapshot().isEmpty());
    }


    @Test
    void oneSpawnerBatchIsProcessedAtMostOncePerServerTick() {
        World world = createWorld();
        MobSpawnerManager manager = world.mobSpawners();
        long due = manager.snapshot().get(0).nextSpawnTick();
        AtomicInteger calls = new AtomicInteger();
        var first = manager.tick(world, due,
                List.of(new MobSpawnerManager.PlayerPosition(0.5, 65, 0.5)),
                request -> { calls.incrementAndGet(); return true; });
        var duplicate = manager.tick(world, due,
                List.of(new MobSpawnerManager.PlayerPosition(0.5, 65, 0.5)),
                request -> { calls.incrementAndGet(); return true; });

        assertEquals(1, first.processedSpawners());
        assertEquals(0, duplicate.processedSpawners());
        assertEquals(first.spawned(), calls.get());
    }

    @Test
    void spawnerWorkIsBoundedAndRotatesAcrossTicks() {
        World world = new World("spawner-budget-test", 0L, new FlatWorldGenerator());
        for (int i = 0; i <= MobSpawnerManager.MAX_SPAWNERS_PER_TICK; i++) {
            world.setBlock(i % 16, 65, i / 16, BlockType.SPAWNER);
        }
        MobSpawnerManager manager = world.mobSpawners();
        long due = manager.snapshot().stream().mapToLong(MobSpawnerState::nextSpawnTick)
                .max().orElseThrow();
        List<MobSpawnerManager.PlayerPosition> players = manager.snapshot().stream()
                .map(state -> new MobSpawnerManager.PlayerPosition(
                        state.x() + 0.5, state.y(), state.z() + 0.5))
                .toList();

        var first = manager.tick(world, due, players, request -> false);
        var second = manager.tick(world, due + 1, players, request -> false);

        assertEquals(MobSpawnerManager.MAX_SPAWNERS_PER_TICK, first.processedSpawners());
        assertEquals(1, second.processedSpawners());
        assertEquals(MobSpawnerManager.MAX_SPAWNERS_PER_TICK + 1, first.trackedSpawners());
    }

    @Test
    void configurationAndRemovalAreValidated() {
        MobSpawnerManager manager = new MobSpawnerManager();
        assertTrue(manager.register(1, 65, 1, 10));
        assertTrue(manager.configure(1, 65, 1, "minecraft:skeleton", 100, 20));
        assertEquals("minecraft:skeleton", manager.snapshot().get(0).entityIdentifier());
        assertEquals(100, manager.snapshot().get(0).delayTicks());
        assertFalse(manager.configure(1, 65, 1, "minecraft:invalid id", 100, 20));
        assertFalse(manager.configure(1, 65, 1, "minecraft:zombie", 1, 20));
        assertTrue(manager.remove(1, 65, 1));
        assertTrue(manager.snapshot().isEmpty());
    }

    @Test
    void spawnerStateRejectsCoordinatesOutsideWorldBorder() {
        assertThrows(IllegalArgumentException.class, () ->
                new MobSpawnerState(30_000_001, 65, 0, "minecraft:zombie", 200, 300));
        assertThrows(IllegalArgumentException.class, () ->
                new MobSpawnerState(0, 65, -30_000_001, "minecraft:zombie", 200, 300));
    }

    @Test
    void blockChangeRegistersAndRemovesSpawner() {
        MobSpawnerManager manager = new MobSpawnerManager();
        manager.onBlockChanged(new World.BlockChange(2, 65, 2, BlockType.AIR, BlockType.SPAWNER), 50);
        assertEquals(1, manager.stats().trackedSpawners());
        manager.onBlockChanged(new World.BlockChange(2, 65, 2, BlockType.SPAWNER, BlockType.AIR), 51);
        assertEquals(0, manager.stats().trackedSpawners());
    }
    @Test
    void spawnerConfigurationAndTimerSurviveWorldRestart(@TempDir Path directory) throws IOException {
        WorldStorage storage = new WorldStorage(directory, "spawner-persistence");
        World first = new World("spawner-persistence", 1L, new FlatWorldGenerator(),
                0, 65, 0, storage, null, null, null, null);
        first.setBlock(3, 65, 3, BlockType.SPAWNER);
        long placedAt = Math.max(0L, System.nanoTime() / 50_000_000L);
        assertTrue(first.mobSpawners().configure(3, 65, 3,
                "minecraft:skeleton", 300, placedAt));
        first.saveAll();

        World restarted = new World("spawner-persistence", 1L, new FlatWorldGenerator(),
                0, 65, 0, new WorldStorage(directory, "spawner-persistence"),
                null, null, null, null);
        MobSpawnerState restored = restarted.mobSpawners().snapshot().get(0);
        long remainingAfterRestart = restored.nextSpawnTick()
                - Math.max(0L, System.nanoTime() / 50_000_000L);
        assertEquals("minecraft:skeleton", restored.entityIdentifier());
        assertEquals(300, restored.delayTicks());
        assertTrue(remainingAfterRestart >= 0 && remainingAfterRestart <= 300);
        assertEquals(BlockType.SPAWNER, restarted.getBlock(3, 65, 3));
    }

    @Test
    void corruptedSpawnerStorageIsRejected(@TempDir Path directory) throws IOException {
        MobSpawnerStorage storage = new MobSpawnerStorage(directory);
        storage.save(List.of(new MobSpawnerState(1, 65, 1, "minecraft:zombie", 200, 300)));
        Path file = directory.resolve("mob-spawners.dat");
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        bytes[5] ^= 0x7f;
        java.nio.file.Files.write(file, bytes);

        assertThrows(IOException.class, storage::load);
    }

    @Test
    void spawnValidationNeverLoadsNeighborChunks() {
        World world = new World("spawner-neighbor-chunk", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        MobSpawnerManager manager = world.mobSpawners();
        manager.register(15, 65, 0, 0);
        long due = manager.snapshot().get(0).nextSpawnTick();

        manager.tick(world, due,
                List.of(new MobSpawnerManager.PlayerPosition(15.5, 65, 0.5)), request -> true);

        assertFalse(world.isChunkLoadedAt(1, 0));
    }

}
