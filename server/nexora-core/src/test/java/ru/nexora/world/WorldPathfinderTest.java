package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldPathfinderTest {
    @Test
    void findsDetourAroundSolidBlockInsteadOfWalkingIntoIt() {
        World world = new World("path-test", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        world.setBlock(1, 65, 0, BlockType.STONE);

        var step = WorldPathfinder.findNextStep(world,
                0.5, 65.0, 0.5, 3.5, 0.5).orElseThrow();

        assertTrue(WorldCollision.canStepMob(world, "minecraft:zombie",
                0.5, 65.0, 0.5, step.x(), step.y(), step.z()));
        assertTrue(step.y() > 65.0 || step.x() != 1.5 || step.z() != 0.5,
                "A waypoint over the obstacle is safe only when the mob has stepped onto it");
    }

    @Test
    void doesNotLoadNeighborChunksWhileSearchingForAPath() {
        World world = new World("path-unloaded", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);

        assertTrue(WorldPathfinder.findNextStep(world,
                15.5, 65.0, 0.5, 20.5, 0.5).isEmpty());
        assertEquals(1, world.loadedChunkCount());
    }

    @Test
    void returnsEmptyForSameCellAndInvalidCoordinates() {
        World world = new World("path-empty", 0L, new FlatWorldGenerator());
        assertTrue(WorldPathfinder.findNextStep(world, 0.1, 65, 0.1, 0.9, 0.9).isEmpty());
        assertTrue(WorldPathfinder.findNextStep(world, Double.NaN, 65, 0, 3, 3).isEmpty());
    }

    @Test
    void findsOneBlockStepUpWithSpeciesCollisionBox() {
        World world = new World("path-step-up", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        world.setBlock(1, 65, 0, BlockType.STONE);

        var step = WorldPathfinder.findNextStep(world,
                0.5, 65.0, 0.5, 1.5, 0.5, "minecraft:zombie").orElseThrow();

        assertEquals(66.0, step.y(), 1.0e-6);
        assertTrue(WorldCollision.canStepMob(world, "minecraft:zombie",
                0.5, 65.0, 0.5, step.x(), step.y(), step.z()));
    }

    @Test
    void findsSafeOneBlockDropWithoutPathingIntoVoid() {
        World world = new World("path-step-down", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        world.setBlock(0, 65, 0, BlockType.STONE);

        var step = WorldPathfinder.findNextStep(world,
                0.5, 66.0, 0.5, 1.5, 0.5, "minecraft:zombie").orElseThrow();

        assertEquals(65.0, step.y(), 1.0e-6);
        assertTrue(WorldCollision.canStepMob(world, "minecraft:zombie",
                0.5, 66.0, 0.5, step.x(), step.y(), step.z()));
    }

    @Test
    void refusesStepUpWhenThereIsNoHeadroom() {
        World world = new World("path-step-ceiling", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        world.setBlock(1, 65, 0, BlockType.STONE);
        world.setBlock(0, 66, 0, BlockType.STONE);

        assertFalse(WorldCollision.canStepMob(world, "minecraft:zombie",
                0.5, 65.0, 0.5, 1.5, 66.0, 0.5));
    }

    @Test
    void reportsSearchAreaResidentOnlyWhenAllRelevantChunksAreLoaded() {
        World world = new World("path-area-loaded", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        assertFalse(WorldPathfinder.isSearchAreaLoaded(world, 8.5, 8.5));

        for (int chunkX = -1; chunkX <= 1; chunkX++) {
            for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
                world.getChunk(chunkX, chunkZ);
            }
        }
        assertTrue(WorldPathfinder.isSearchAreaLoaded(world, 8.5, 8.5));
    }

}
