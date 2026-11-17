package ru.nexora.world;

import org.junit.jupiter.api.Test;

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

        var early = manager.tick(world, baseTick + 199,
                List.of(new MobSpawnerManager.PlayerPosition(0.5, 65, 0.5)),
                request -> { spawned.incrementAndGet(); return true; });
        assertEquals(0, early.spawned());

        var due = manager.tick(world, baseTick + 200,
                List.of(new MobSpawnerManager.PlayerPosition(0.5, 65, 0.5)),
                request -> { spawned.incrementAndGet(); return true; });
        assertTrue(due.spawned() > 0);
        assertEquals(due.spawned(), spawned.get());
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
    void blockChangeRegistersAndRemovesSpawner() {
        MobSpawnerManager manager = new MobSpawnerManager();
        manager.onBlockChanged(new World.BlockChange(2, 65, 2, BlockType.AIR, BlockType.SPAWNER), 50);
        assertEquals(1, manager.stats().trackedSpawners());
        manager.onBlockChanged(new World.BlockChange(2, 65, 2, BlockType.SPAWNER, BlockType.AIR), 51);
        assertEquals(0, manager.stats().trackedSpawners());
    }
}
