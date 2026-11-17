package ru.nexora.combat;

import org.junit.jupiter.api.Test;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;

import static org.junit.jupiter.api.Assertions.*;

class MobPhysicsServiceTest {
    private final MobPhysicsService physics = new MobPhysicsService();

    @Test
    void unsupportedGroundMobFallsUnderGravity() {
        World world = new World("mob-physics-fall", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        var mob = world.livingEntities().spawn("minecraft:zombie", 20, 0.5, 70, 0.5);

        var result = physics.tick(world, mob);

        assertTrue(result.moved());
        assertEquals(70 - MobPhysicsService.GRAVITY_PER_TICK, result.entity().y(), 0.0001);
        assertTrue(result.entity().velocityY() < 0);
        assertFalse(result.grounded());
    }

    @Test
    void groundedMobDoesNotSinkThroughTheFloor() {
        World world = new World("mob-physics-ground", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        var mob = world.livingEntities().spawn("minecraft:zombie", 20, 0.5, 65, 0.5);

        var result = physics.tick(world, mob);

        assertFalse(result.moved());
        assertTrue(result.grounded());
        assertEquals(65, result.entity().y(), 0.0001);
        assertEquals(0, result.entity().velocityY(), 0.0001);
    }

    @Test
    void knockbackVelocityMovesMobAndDampsOverTime() {
        World world = new World("mob-physics-knockback", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        var mob = world.livingEntities().spawn("minecraft:zombie", 20, 0.5, 65, 0.5);
        mob = world.livingEntities().setVelocity(mob.runtimeId(), 0.4, 0, 0).orElseThrow();

        var result = physics.tick(world, mob);

        assertTrue(result.moved());
        assertEquals(0.9, result.entity().x(), 0.0001);
        assertEquals(0.24, result.entity().velocityX(), 0.0001);
    }

    @Test
    void physicsDoesNotLoadAnUnloadedNeighborChunk() {
        World world = new World("mob-physics-chunk-edge", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        var mob = world.livingEntities().spawn("minecraft:zombie", 20, 15.5, 65, 0.5);
        mob = world.livingEntities().setVelocity(mob.runtimeId(), 0.4, 0, 0).orElseThrow();

        var result = physics.tick(world, mob);

        assertFalse(result.moved());
        assertEquals(15.5, result.entity().x(), 0.0001);
        assertEquals(0, result.entity().velocityX(), 0.0001);
        assertFalse(world.isChunkLoadedAt(16, 0));
    }

    @Test
    void slimeBouncesWhenItsDownwardMotionHitsTheGround() {
        World world = new World("mob-physics-slime-bounce", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        var slime = world.livingEntities().spawn("minecraft:slime", 20, 0.5, 65.1, 0.5);
        slime = world.livingEntities().setVelocity(slime.runtimeId(), 0, -3.0, 0).orElseThrow();

        var result = physics.tick(world, slime);

        assertFalse(result.moved());
        assertFalse(result.grounded());
        assertTrue(result.entity().velocityY() > 0);
    }

    @Test
    void flyingMobDoesNotReceiveGroundGravity() {
        World world = new World("mob-physics-flight", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        var bat = world.livingEntities().spawn("minecraft:bat", 6, 0.5, 70, 0.5);

        var result = physics.tick(world, bat);

        assertFalse(result.moved());
        assertEquals(70, result.entity().y(), 0.0001);
        assertEquals(0, result.entity().velocityY(), 0.0001);
    }
    @Test
    void knockbackDoesNotLoadAnUnloadedNeighborChunk() {
        World world = new World("mob-physics-chunk-edge", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        var mob = world.livingEntities().spawn("minecraft:zombie", 20, 15.5, 65, 0.5);
        mob = world.livingEntities().setVelocity(mob.runtimeId(), 1.0, 0, 0).orElseThrow();

        var result = physics.tick(world, mob);

        assertFalse(result.moved());
        assertEquals(15.5, result.entity().x(), 0.0001);
        assertEquals(0, result.entity().velocityX(), 0.0001);
        assertFalse(world.isChunkLoadedAt(16, 0));
    }

    @Test
    void slimeBouncesInsteadOfStoppingWhenItLands() {
        World world = new World("mob-physics-slime-bounce", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        var slime = world.livingEntities().spawn("minecraft:slime", 16, 0.5, 65, 0.5);
        slime = world.livingEntities().setVelocity(slime.runtimeId(), 0, -0.5, 0).orElseThrow();

        var result = physics.tick(world, slime);

        assertTrue(result.entity().y() > 65.0);
        assertTrue(result.entity().velocityY() > 0.0);
        assertFalse(result.grounded());
    }

}
