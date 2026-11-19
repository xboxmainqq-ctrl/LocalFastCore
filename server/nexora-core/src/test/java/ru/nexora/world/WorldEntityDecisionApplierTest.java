package ru.nexora.world;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import ru.nexora.combat.LivingEntityAiService;

class WorldEntityDecisionApplierTest {

    @Test
    void appliesMovementButLeavesPlayerDamageAsOutputEvent() {
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity mob = manager.spawn("minecraft:zombie", 20, 0, 64, 0);
        WorldEntityDecisionApplier applier = new WorldEntityDecisionApplier(manager);

        var result = applier.apply(new LivingEntityAiService.Decision(
                mob.runtimeId(), "player-1", 0.23, 0, 0, 0, false));

        assertTrue(result.moved());
        assertFalse(result.attacked());
        assertEquals(0.23, manager.find(mob.runtimeId()).orElseThrow().x(), 0.00001);
    }

    @Test
    void emitsAttackOutputWithoutMutatingMobHealth() {
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity mob = manager.spawn("minecraft:zombie", 20, 0, 64, 0);
        WorldEntityDecisionApplier applier = new WorldEntityDecisionApplier(manager);

        var result = applier.apply(new LivingEntityAiService.Decision(
                mob.runtimeId(), "player-1", 0, 0, 0, 3, true));

        assertTrue(result.attacked());
        assertEquals(3, result.attackDamage());
        assertEquals(20, manager.find(mob.runtimeId()).orElseThrow().health());
    }

    @Test
    void ignoresMissingMob() {
        WorldEntityDecisionApplier applier = new WorldEntityDecisionApplier(new WorldLivingEntityManager());

        var result = applier.apply(new LivingEntityAiService.Decision(
                12345, "player-1", 1, 0, 0, 0, false));

        assertFalse(result.moved());
        assertFalse(result.attacked());
    }

    @Test
    void invalidMovementDecisionIsRejectedAtBoundary() {
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity mob = manager.spawn("minecraft:zombie", 20, 0, 64, 0);
        WorldEntityDecisionApplier applier = new WorldEntityDecisionApplier(manager);

        assertThrows(IllegalArgumentException.class, () -> applier.apply(
                new LivingEntityAiService.Decision(
                        mob.runtimeId(), null, Double.NaN, 0, 0, 0, false)));
    }

    @Test
    void worldAwareApplierRoutesGroundMobAroundABlockingWall() {
        World world = new World("mob-detour", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        world.setBlock(1, 65, 0, BlockType.STONE);
        world.setBlock(1, 66, 0, BlockType.STONE);
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity mob = manager.spawn("minecraft:zombie", 20, 0.5, 65, 0.5);
        WorldEntityDecisionApplier applier = new WorldEntityDecisionApplier(world, manager);

        var result = applier.apply(new LivingEntityAiService.Decision(
                mob.runtimeId(), "player-1", 0.23, 0, 0, 0, false), 10_000);

        WorldLivingEntity moved = manager.find(mob.runtimeId()).orElseThrow();
        assertTrue(result.moved());
        assertTrue(moved.z() > 0.5,
                "The mob should move sideways around the blocking wall");
        assertNotEquals(0.5, moved.x(), 1.0e-6,
                "The mob should take a side waypoint rather than push into the wall");
        assertTrue(WorldCollision.canOccupyMob(world, mob.identifier(),
                moved.x(), moved.y(), moved.z()));
    }

    @Test
    void worldAwareApplierDoesNotMoveMobIntoAnUnloadedChunk() {
        World world = new World("mob-no-load", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity mob = manager.spawn("minecraft:zombie", 20, 15.5, 65, 0.5);
        WorldEntityDecisionApplier applier = new WorldEntityDecisionApplier(world, manager);

        var result = applier.apply(new LivingEntityAiService.Decision(
                mob.runtimeId(), "player-1", 0.23, 0, 0, 0, false), 10_000);

        assertFalse(result.moved());
        assertEquals(1, world.loadedChunkCount());
        assertEquals(15.5, manager.find(mob.runtimeId()).orElseThrow().x());
    }
}
