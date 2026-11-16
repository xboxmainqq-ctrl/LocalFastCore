package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockBreakServiceTest {
    @Test
    void breaksLoadedBlockWithinReach() {
        InMemoryWorld world = loadedWorld();
        BlockPosition target = new BlockPosition(2, 1, 2);
        world.setBlock(target, new BlockState("minecraft:stone"));

        BlockBreakService.Outcome outcome = BlockBreakService.breakBlock(
                world, target, 2.5, 1.0, 2.5);

        assertEquals(BlockBreakService.Result.BROKEN, outcome.result());
        assertEquals("minecraft:stone", outcome.previousState().identifier());
        assertTrue(world.blockAt(target).isAir());
    }

    @Test
    void refusesBlocksOutsideReach() {
        InMemoryWorld world = loadedWorld();
        BlockPosition target = new BlockPosition(10, 1, 10);
        world.setBlock(target, new BlockState("minecraft:stone"));

        BlockBreakService.Outcome outcome = BlockBreakService.breakBlock(
                world, target, 0.5, 1.0, 0.5);

        assertEquals(BlockBreakService.Result.OUT_OF_REACH, outcome.result());
        assertEquals("minecraft:stone", world.blockAt(target).identifier());
    }

    @Test
    void refusesBreakingThroughAnOccludingWall() {
        InMemoryWorld world = loadedWorld();
        BlockPosition wall = new BlockPosition(2, 1, 1);
        BlockPosition target = new BlockPosition(3, 1, 1);
        world.setBlock(wall, new BlockState("minecraft:stone"));
        world.setBlock(target, new BlockState("minecraft:stone"));

        BlockBreakService.Outcome outcome = BlockBreakService.breakBlock(
                world, target, 1.5, 1.0, 1.5);

        assertEquals(BlockBreakService.Result.OCCLUDED, outcome.result());
        assertEquals("minecraft:stone", world.blockAt(target).identifier());
    }

    @Test
    void refusesBedrock() {
        InMemoryWorld world = loadedWorld();
        BlockPosition target = new BlockPosition(2, 1, 2);
        world.setBlock(target, new BlockState("minecraft:bedrock"));

        BlockBreakService.Outcome outcome = BlockBreakService.breakBlock(
                world, target, 2.5, 1.0, 2.5);

        assertEquals(BlockBreakService.Result.UNBREAKABLE, outcome.result());
        assertEquals("minecraft:bedrock", world.blockAt(target).identifier());
    }

    @Test
    void refusesUnloadedChunkAndOutOfWorldCoordinates() {
        InMemoryWorld world = loadedWorld();

        assertEquals(BlockBreakService.Result.UNLOADED_CHUNK,
                BlockBreakService.breakBlock(world, new BlockPosition(20, 1, 1),
                        19.5, 1.0, 1.5).result());
        assertEquals(BlockBreakService.Result.OUT_OF_WORLD,
                BlockBreakService.breakBlock(world, new BlockPosition(1, 20, 1),
                        1.5, 1.0, 1.5).result());
    }

    @Test
    void airDoesNotProduceABreak() {
        InMemoryWorld world = loadedWorld();
        assertEquals(BlockBreakService.Result.AIR,
                BlockBreakService.breakBlock(world, new BlockPosition(1, 1, 1),
                        1.5, 1.0, 1.5).result());
    }

    private static InMemoryWorld loadedWorld() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 16));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0), Map.of()));
        return world;
    }
}
