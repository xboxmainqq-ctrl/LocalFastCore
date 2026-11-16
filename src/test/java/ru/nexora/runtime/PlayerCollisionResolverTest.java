package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlayerCollisionResolverTest {
    @Test
    void stopsHorizontalMovementAtSolidWall() {
        InMemoryWorld world = floorWorld();
        Map<BlockPosition, BlockState> blocks = new HashMap<>();
        for (int y = 1; y <= 3; y++) {
            blocks.put(new BlockPosition(2, y, 0), new BlockState("minecraft:stone"));
        }
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0), blocks));

        PlayerCollisionResolver.Resolution result = PlayerCollisionResolver.resolve(
                world, 1.5, 1.0, 0.5, 4.0, 1.0, 0.5);

        assertTrue(result.collidedX());
        assertTrue(result.x() < 1.71);
        assertTrue(result.x() > 1.6);
        assertEquals(1.0, result.y(), 1.0e-9);
    }

    @Test
    void slidesAlongWallInsteadOfCancellingEntireDiagonalMove() {
        InMemoryWorld world = floorWorld();
        Map<BlockPosition, BlockState> blocks = new HashMap<>();
        for (int y = 1; y <= 3; y++) {
            blocks.put(new BlockPosition(2, y, 0), new BlockState("minecraft:stone"));
        }
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0), blocks));

        PlayerCollisionResolver.Resolution result = PlayerCollisionResolver.resolve(
                world, 1.5, 1.0, 0.5, 4.0, 1.0, 3.0);

        assertTrue(result.collidedX());
        assertFalse(result.collidedZ());
        assertEquals(3.0, result.z(), 1.0e-9);
    }

    @Test
    void preventsFallingThroughFloorAndReportsGroundContact() {
        InMemoryWorld world = floorWorld();

        PlayerCollisionResolver.Resolution result = PlayerCollisionResolver.resolve(
                world, 1.5, 4.0, 1.5, 1.5, -1.0, 1.5);

        assertTrue(result.collidedY());
        assertTrue(result.onGround());
        assertEquals(1.0, result.y(), 1.0e-9);
    }

    @Test
    void preventsHeadFromPassingThroughCeiling() {
        InMemoryWorld world = floorWorld();
        Map<BlockPosition, BlockState> blocks = new HashMap<>();
        for (int x = 0; x < 4; x++) {
            blocks.put(new BlockPosition(x, 5, 0), new BlockState("minecraft:stone"));
        }
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0), blocks));

        PlayerCollisionResolver.Resolution result = PlayerCollisionResolver.resolve(
                world, 1.5, 1.0, 0.5, 1.5, 4.0, 0.5);

        assertTrue(result.collidedY());
        assertTrue(result.y() + 1.8 <= 5.00001);
    }

    @Test
    void treatsUnloadedChunksAsSolidBoundaries() {
        InMemoryWorld world = floorWorld();

        PlayerCollisionResolver.Resolution result = PlayerCollisionResolver.resolve(
                world, 15.0, 1.0, 8.0, 18.0, 1.0, 8.0);

        assertTrue(result.collidedX());
        assertTrue(result.x() < 15.71);
    }

    private static InMemoryWorld floorWorld() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 16));
        Map<BlockPosition, BlockState> floor = new HashMap<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                floor.put(new BlockPosition(x, 0, z), new BlockState("minecraft:stone"));
            }
        }
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0), floor));
        return world;
    }
}
