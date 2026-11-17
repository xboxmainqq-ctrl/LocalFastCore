package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldPathfinderTest {
    @Test
    void findsDetourAroundSolidBlockInsteadOfWalkingIntoIt() {
        World world = new World("path-test", 0L, new FlatWorldGenerator());
        world.setBlock(1, 65, 0, BlockType.STONE);

        var step = WorldPathfinder.findNextStep(world,
                0.5, 65.0, 0.5, 3.5, 0.5).orElseThrow();

        assertFalse(step.x() == 1.5 && step.z() == 0.5,
                "The first waypoint must not pass through the blocking block");
        assertTrue(WorldCollision.canMove(world,
                0.5, 65.0, 0.5, step.x(), 65.0, step.z()));
    }

    @Test
    void returnsEmptyForSameCellAndInvalidCoordinates() {
        World world = new World("path-empty", 0L, new FlatWorldGenerator());
        assertTrue(WorldPathfinder.findNextStep(world, 0.1, 65, 0.1, 0.9, 0.9).isEmpty());
        assertTrue(WorldPathfinder.findNextStep(world, Double.NaN, 65, 0, 3, 3).isEmpty());
    }
}
