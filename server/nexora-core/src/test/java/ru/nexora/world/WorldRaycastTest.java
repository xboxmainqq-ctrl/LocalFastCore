package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldRaycastTest {
    @Test
    void permitsClearRayToTargetBlock() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        world.setBlock(3, 65, 0, BlockType.STONE);

        assertTrue(WorldRaycast.hasLineOfSight(world, 0.5, 66.62, 0.5, 3, 65, 0));
    }

    @Test
    void blocksRayWhenSolidVoxelIsBetweenPlayerAndTarget() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        world.setBlock(2, 65, 0, BlockType.STONE);
        world.setBlock(3, 65, 0, BlockType.STONE);

        assertFalse(WorldRaycast.hasLineOfSight(world, 0.5, 66.62, 0.5, 3, 65, 0));
    }

    @Test
    void doesNotTreatTargetBlockAsAnObstruction() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        world.setBlock(1, 65, 0, BlockType.STONE);

        assertTrue(WorldRaycast.hasLineOfSight(world, 0.5, 66.62, 0.5, 1, 65, 0));
    }

    @Test
    void rejectsInvalidRayOriginsAndOutOfWorldTargets() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());

        assertFalse(WorldRaycast.hasLineOfSight(world, Double.NaN, 65, 0, 1, 65, 0));
        assertFalse(WorldRaycast.hasLineOfSight(world, 0.5, 65, 0.5, 1, 400, 0));
    }
}
