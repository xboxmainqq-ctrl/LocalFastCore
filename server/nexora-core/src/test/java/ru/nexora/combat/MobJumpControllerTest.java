package ru.nexora.combat;

import org.junit.jupiter.api.Test;
import ru.nexora.world.BlockType;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;

import static org.junit.jupiter.api.Assertions.*;

class MobJumpControllerTest {
    @Test
    void jumpsOverOneBlockObstacle() {
        World world = new World("mob-jump-one-block", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        world.setBlock(1, 65, 0, BlockType.STONE);
        var mob = world.livingEntities().spawn("minecraft:zombie", 20, 0.5, 65, 0.5);
        MobJumpController controller = new MobJumpController();

        assertTrue(controller.tryJump(world, mob, 0.73, 0.5, 1_000));
        var jumped = world.livingEntities().find(mob.runtimeId()).orElseThrow();
        assertEquals(MobJumpController.JUMP_VELOCITY, jumped.velocityY(), 0.0001);
        assertEquals(1, controller.stats().jumps());
    }

    @Test
    void doesNotJumpThroughTwoBlockWallsOrLowCeilings() {
        World wallWorld = new World("mob-jump-two-block", 0L, new FlatWorldGenerator());
        wallWorld.getChunk(0, 0);
        wallWorld.setBlock(1, 65, 0, BlockType.STONE);
        wallWorld.setBlock(1, 66, 0, BlockType.STONE);
        var wallMob = wallWorld.livingEntities().spawn("minecraft:zombie", 20, 0.5, 65, 0.5);
        assertFalse(new MobJumpController().tryJump(wallWorld, wallMob, 0.73, 0.5, 1_000));

        World ceilingWorld = new World("mob-jump-ceiling", 0L, new FlatWorldGenerator());
        ceilingWorld.getChunk(0, 0);
        ceilingWorld.setBlock(0, 67, 0, BlockType.STONE);
        ceilingWorld.setBlock(1, 65, 0, BlockType.STONE);
        var ceilingMob = ceilingWorld.livingEntities().spawn("minecraft:zombie", 20, 0.5, 65, 0.5);
        assertFalse(new MobJumpController().tryJump(ceilingWorld, ceilingMob, 0.73, 0.5, 1_000));
    }

    @Test
    void respectsCooldownAndDoesNotJumpFlyingMobs() {
        World world = new World("mob-jump-cooldown", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        world.setBlock(1, 65, 0, BlockType.STONE);
        var zombie = world.livingEntities().spawn("minecraft:zombie", 20, 0.5, 65, 0.5);
        MobJumpController controller = new MobJumpController();

        assertTrue(controller.tryJump(world, zombie, 0.73, 0.5, 1_000));
        assertFalse(controller.tryJump(world, zombie, 0.73, 0.5, 1_100));

        var bat = world.livingEntities().spawn("minecraft:bat", 6, 0.5, 65, 0.5);
        assertFalse(controller.tryJump(world, bat, 0.73, 0.5, 2_000));
    }

    @Test
    void neverLoadsNeighborChunksForJumpChecks() {
        World world = new World("mob-jump-chunk-edge", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        var mob = world.livingEntities().spawn("minecraft:zombie", 20, 15.5, 65, 0.5);

        assertFalse(new MobJumpController().tryJump(world, mob, 15.73, 0.5, 1_000));
        assertFalse(world.isChunkLoadedAt(16, 0));
    }
}
