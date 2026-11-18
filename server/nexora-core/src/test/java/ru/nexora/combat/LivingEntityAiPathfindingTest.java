package ru.nexora.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.MobPathCache;
import ru.nexora.world.World;
import ru.nexora.world.WorldLivingEntity;
import ru.nexora.world.WorldLivingEntityManager;

class LivingEntityAiPathfindingTest {

    @Test
    void groundMobPursuitUsesTheSharedBoundedWorldPathCache() {
        World world = new World("mob-path-ai", 1L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        WorldLivingEntityManager entities = new WorldLivingEntityManager();
        WorldLivingEntity mob = entities.spawn("minecraft:zombie", 20, 0.5, 65, 0.5);
        MobPathCache cache = new MobPathCache();
        LivingEntityAiService service = new LivingEntityAiService(world, cache);
        var player = new LivingEntityAiService.PlayerTarget("player", 6.5, 65, 0.5, true);

        var decision = service.decide(mob, List.of(player), 1_000, 0, null, player);

        assertTrue(decision.moving());
        assertTrue(decision.moveX() > 0);
        assertTrue(decision.moveX() <= LivingEntityAiService.WALK_SPEED_PER_TICK);
        assertEquals(1, cache.stats().recomputations());
        assertEquals(1, cache.size());
    }

    @Test
    void groundMobDoesNotForceLoadChunksWhenPathFootprintIsNotResident() {
        World world = new World("mob-path-no-load", 1L, new FlatWorldGenerator());
        WorldLivingEntity mob = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0.5, 65, 0.5);
        MobPathCache cache = new MobPathCache();
        LivingEntityAiService service = new LivingEntityAiService(world, cache);
        var player = new LivingEntityAiService.PlayerTarget("player", 6.5, 65, 0.5, true);

        var decision = service.decide(mob, List.of(player), 1_000, 0, null, player);

        assertFalse(decision.moving());
        assertEquals(0, world.loadedChunkCount(),
                "Pathfinding must not generate neighboring chunks as a side effect");
        assertEquals(1, cache.stats().recomputations());
    }
}
