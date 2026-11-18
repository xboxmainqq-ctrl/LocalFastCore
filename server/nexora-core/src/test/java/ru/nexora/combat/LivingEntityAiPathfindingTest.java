package ru.nexora.combat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.MobPathCache;
import ru.nexora.world.World;
import ru.nexora.world.WorldLivingEntity;
import ru.nexora.world.WorldLivingEntityManager;

class LivingEntityAiPathfindingTest {

    @Test
    void blockedMovementUsesBoundedPathCacheToFindAResidentWaypoint() {
        World world = new World("mob-path-cache", 1L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        WorldLivingEntity mob = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0.5, 65, 0.5);
        MobPathCache cache = new MobPathCache();

        var step = cache.findNextStep(mob.runtimeId(), world,
                mob.x(), mob.y(), mob.z(), 6.5, 0.5, 1_000);

        assertTrue(step.isPresent());
        assertTrue(step.orElseThrow().x() > mob.x());
        assertEquals(1, cache.stats().recomputations());
        assertEquals(1, cache.size());
    }

    @Test
    void pathCacheDoesNotGenerateChunksWhenTheFootprintIsNotResident() {
        World world = new World("mob-path-cache-no-load", 1L, new FlatWorldGenerator());
        WorldLivingEntity mob = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0.5, 65, 0.5);
        MobPathCache cache = new MobPathCache();

        var step = cache.findNextStep(mob.runtimeId(), world,
                mob.x(), mob.y(), mob.z(), 6.5, 0.5, 1_000);

        assertTrue(step.isEmpty());
        assertEquals(0, world.loadedChunkCount());
        assertEquals(1, cache.stats().recomputations());
    }
}
