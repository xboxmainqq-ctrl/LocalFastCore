package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldCollisionTest {
    @Test
    void allowsWalkingOnTopOfGeneratedGround() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        assertTrue(WorldCollision.canOccupy(world, 0.5, 65.0, 0.5));
        assertTrue(WorldCollision.canMove(world, 0.5, 65.0, 0.5, 8.5, 65.0, 0.5));
    }

    @Test
    void rejectsMovementThroughSolidWallEvenWhenDestinationIsClear() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        world.setBlock(1, 65, 0, BlockType.STONE);
        world.setBlock(1, 66, 0, BlockType.STONE);

        assertTrue(WorldCollision.canOccupy(world, 2.5, 65.0, 0.5));
        assertFalse(WorldCollision.canMove(world, 0.5, 65.0, 0.5, 2.5, 65.0, 0.5));
    }

    @Test
    void rejectsOversizedSweepsWithoutUnboundedSampling() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        assertFalse(WorldCollision.canMove(world, 0.5, 65, 0.5, 1_000_000, 65, 0.5));
    }

    @Test
    void rejectsPositionsInsideBlocksAndOutsideBuildHeight() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        world.setBlock(0, 65, 0, BlockType.STONE);
        assertFalse(WorldCollision.canOccupy(world, 0.5, 65.0, 0.5));
        assertFalse(WorldCollision.canOccupy(world, 0.5, 319.5, 0.5));
        assertFalse(WorldCollision.canOccupy(world, Double.NaN, 65.0, 0.5));
    }

    @Test
    void rejectsMovementThatPassesThroughAThinObstacleDiagonally() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        world.setBlock(2, 65, 2, BlockType.STONE);
        world.setBlock(2, 66, 2, BlockType.STONE);
        assertFalse(WorldCollision.canMove(world, 0.5, 65.0, 0.5, 4.5, 65.0, 4.5));
    }
}
