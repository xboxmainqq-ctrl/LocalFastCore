package ru.nexora.combat;

import org.junit.jupiter.api.Test;
import ru.nexora.world.BlockType;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;

import static org.junit.jupiter.api.Assertions.*;

class MobClimbControllerTest {
    @Test
    void spiderClimbsAHighWallWhenDirectMovementIsBlocked() {
        World world = new World("mob-climb-wall", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        for (int y = 65; y <= 67; y++) world.setBlock(1, y, 0, BlockType.STONE);
        var spider = world.livingEntities().spawn("minecraft:spider", 16, 0.5, 65, 0.5);
        MobClimbController controller = new MobClimbController();

        assertTrue(controller.tryClimb(world, spider, 0.77, 0.5));
        var climbing = world.livingEntities().find(spider.runtimeId()).orElseThrow();
        assertEquals(MobClimbController.CLIMB_VELOCITY, climbing.velocityY(), 0.0001);
        assertEquals(1, controller.stats().climbs());
    }

    @Test
    void onlySpidersClimbAndOpenAirDoesNotTriggerClimbing() {
        World world = new World("mob-climb-only-spiders", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        world.setBlock(1, 65, 0, BlockType.STONE);
        var zombie = world.livingEntities().spawn("minecraft:zombie", 20, 0.5, 65, 0.5);
        var spider = world.livingEntities().spawn("minecraft:spider", 16, 0.5, 65, 0.5);
        MobClimbController controller = new MobClimbController();

        assertFalse(controller.tryClimb(world, zombie, 0.77, 0.5));
        assertFalse(controller.tryClimb(world, spider, 0.77, 0.5));
        assertEquals(0, controller.stats().climbs());
    }

    @Test
    void climbingNeverLoadsNeighborChunks() {
        World world = new World("mob-climb-chunk-edge", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        world.setBlock(15, 65, 0, BlockType.STONE);
        var spider = world.livingEntities().spawn("minecraft:spider", 16, 15.5, 65, 0.5);

        assertFalse(new MobClimbController().tryClimb(world, spider, 15.8, 0.5));
        assertFalse(world.isChunkLoadedAt(1, 0));
    }
}
