package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldCollisionTest {
    private World world() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        world.getChunk(-1, 0);
        world.getChunk(0, -1);
        world.getChunk(-1, -1);
        return world;
    }
    @Test
    void allowsWalkingOnTopOfGeneratedGround() {
        World world = world();
        assertTrue(WorldCollision.canOccupy(world, 0.5, 65.0, 0.5));
        assertTrue(WorldCollision.canMove(world, 0.5, 65.0, 0.5, 8.5, 65.0, 0.5));
    }

    @Test
    void rejectsMovementThroughSolidWallEvenWhenDestinationIsClear() {
        World world = world();
        world.setBlock(1, 65, 0, BlockType.STONE);
        world.setBlock(1, 66, 0, BlockType.STONE);

        assertTrue(WorldCollision.canOccupy(world, 2.5, 65.0, 0.5));
        assertFalse(WorldCollision.canMove(world, 0.5, 65.0, 0.5, 2.5, 65.0, 0.5));
    }

    @Test
    void detectsGroundSupportAcrossThePlayerFootprint() {
        World world = world();

        assertTrue(WorldCollision.isOnGround(world, 0.5, 65.0, 0.5));
        assertTrue(WorldCollision.isOnGround(world, 0.1, 65.0, 0.1));
        assertFalse(WorldCollision.isOnGround(world, 0.5, 66.0, 0.5));

        world.setBlock(0, 65, 0, BlockType.STONE);
        assertTrue(WorldCollision.isOnGround(world, 0.5, 66.0, 0.5));

        world.setBlock(0, 65, 0, BlockType.AIR);
        assertFalse(WorldCollision.isOnGround(world, 0.5, 66.0, 0.5));
    }

    @Test
    void rejectsInvalidGroundQueries() {
        World world = world();

        assertFalse(WorldCollision.isOnGround(world, Double.NaN, 65.0, 0.5));
        assertFalse(WorldCollision.isOnGround(world, 0.5, WorldChunk.MIN_Y, 0.5));
        assertFalse(WorldCollision.isOnGround(world, 30_000_001, 65.0, 0.5));
    }

    @Test
    void rejectsOversizedSweepsWithoutUnboundedSampling() {
        World world = world();
        assertFalse(WorldCollision.canMove(world, 0.5, 65, 0.5, 1_000_000, 65, 0.5));
    }

    @Test
    void rejectsPositionsInsideBlocksAndOutsideBuildHeight() {
        World world = world();
        world.setBlock(0, 65, 0, BlockType.STONE);
        assertFalse(WorldCollision.canOccupy(world, 0.5, 65.0, 0.5));
        assertFalse(WorldCollision.canOccupy(world, 0.5, 319.5, 0.5));
        assertFalse(WorldCollision.canOccupy(world, Double.NaN, 65.0, 0.5));
    }

    @Test
    void rejectsMovementThatPassesThroughAThinObstacleDiagonally() {
        World world = world();
        world.setBlock(2, 65, 2, BlockType.STONE);
        world.setBlock(2, 66, 2, BlockType.STONE);
        assertFalse(WorldCollision.canMove(world, 0.5, 65.0, 0.5, 4.5, 65.0, 4.5));
    }
}
