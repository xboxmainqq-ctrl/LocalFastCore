package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryWorldTest {
    @Test
    void mapsNegativeBlockCoordinatesToChunksUsingFloorDivision() {
        BlockPosition position = new BlockPosition(-1, 64, -17);

        assertEquals(new ChunkPosition(-1, -2), position.chunkPosition());
        assertEquals(15, position.localX());
        assertEquals(15, position.localZ());
        assertEquals(new ChunkPosition(-2, -2), ChunkPosition.fromBlock(-17, -17));
    }

    @Test
    void storesBlocksInSparseChunksAndReturnsAirForUnsetPositions() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(-64, 320));
        BlockPosition stonePosition = new BlockPosition(-1, 70, 16);
        BlockState stone = new BlockState("minecraft:stone");

        assertEquals(BlockState.AIR, world.blockAt(stonePosition));
        assertEquals(0, world.loadedChunkCount());

        world.setBlock(stonePosition, stone);

        assertEquals(stone, world.blockAt(stonePosition));
        assertTrue(world.isChunkLoaded(new ChunkPosition(-1, 1)));
        assertEquals(1, world.loadedChunkCount());
        assertEquals(BlockState.AIR, world.blockAt(new BlockPosition(-2, 70, 16)));
    }

    @Test
    void removingLastBlockUnloadsEmptyChunk() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 256));
        BlockPosition position = new BlockPosition(3, 64, 5);
        world.setBlock(position, new BlockState("minecraft:dirt"));

        world.setBlock(position, BlockState.AIR);

        assertEquals(BlockState.AIR, world.blockAt(position));
        assertEquals(0, world.loadedChunkCount());
        assertFalse(world.isChunkLoaded(new ChunkPosition(0, 0)));
    }

    @Test
    void enforcesWorldHeightBounds() {
        InMemoryWorld world = new InMemoryWorld("nether", new WorldHeight(0, 128));

        assertThrows(IllegalArgumentException.class,
                () -> world.blockAt(new BlockPosition(0, -1, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> world.setBlock(new BlockPosition(0, 128, 0), new BlockState("minecraft:stone")));
    }

    @Test
    void validatesWorldAndBlockIdentifiersAndCopiesProperties() {
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryWorld("  ", new WorldHeight(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> new BlockState("stone"));

        Map<String, String> properties = new HashMap<>();
        properties.put("facing", "north");
        BlockState state = new BlockState("minecraft:oak_log", properties);
        properties.put("facing", "south");

        assertEquals("north", state.properties().get("facing"));
        assertThrows(UnsupportedOperationException.class,
                () -> state.properties().put("axis", "y"));
    }

    @Test
    void validatesWorldHeightRange() {
        assertThrows(IllegalArgumentException.class, () -> new WorldHeight(10, 10));
        assertThrows(IllegalArgumentException.class, () -> new WorldHeight(11, 10));
        assertEquals(384, new WorldHeight(-64, 320).height());
    }

    @Test
    void atomicallyLoadsGeneratedChunkAndRejectsDuplicateLoad() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(-64, 320));
        FlatChunkGenerator generator = new FlatChunkGenerator(
                10, 2,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:dirt"),
                new BlockState("minecraft:bedrock")
        );
        ChunkPosition position = new ChunkPosition(-1, 2);
        GeneratedChunk generated = generator.generate(position, world.height(), 42L);

        assertTrue(world.loadChunk(generated));
        assertTrue(world.isChunkLoaded(position));
        assertEquals(1, world.loadedChunkCount());
        assertEquals(new BlockState("minecraft:grass_block"),
                world.blockAt(new BlockPosition(-1, 10, 32)));

        world.setBlock(new BlockPosition(-1, 10, 32), new BlockState("minecraft:gold_block"));
        assertFalse(world.loadChunk(generated), "a duplicate load must not overwrite live chunk data");
        assertEquals(new BlockState("minecraft:gold_block"),
                world.blockAt(new BlockPosition(-1, 10, 32)));
    }

    @Test
    void rejectsGeneratedChunkOutsideWorldHeightWithoutPartialLoad() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 16));
        GeneratedChunk generated = new GeneratedChunk(
                new ChunkPosition(0, 0),
                Map.of(
                        new BlockPosition(0, 1, 0), new BlockState("minecraft:stone"),
                        new BlockPosition(1, 20, 0), new BlockState("minecraft:dirt")
                )
        );

        assertThrows(IllegalArgumentException.class, () -> world.loadChunk(generated));
        assertEquals(0, world.loadedChunkCount());
        assertFalse(world.isChunkLoaded(new ChunkPosition(0, 0)));
    }

    @Test
    void storesNegativeAndPositiveYInSeparateCompactSections() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(-64, 320));
        BlockState stone = new BlockState("minecraft:stone");
        BlockState dirt = new BlockState("minecraft:dirt");
        world.setBlock(new BlockPosition(-1, -17, -16), stone);
        world.setBlock(new BlockPosition(-1, 16, -16), dirt);

        assertEquals(stone, world.blockAt(new BlockPosition(-1, -17, -16)));
        assertEquals(dirt, world.blockAt(new BlockPosition(-1, 16, -16)));
        assertEquals(2, world.blocksInChunk(new ChunkPosition(-1, -1)).size());
    }

    @Test
    void paletteWidensBeyond256DistinctStatesAndPreservesSnapshots() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 16));
        for (int i = 0; i < 300; i++) {
            world.setBlock(new BlockPosition(i % 16, 0, i / 16),
                    new BlockState("nexora:test_block_" + i));
        }

        for (int i = 0; i < 300; i++) {
            assertEquals(new BlockState("nexora:test_block_" + i),
                    world.blockAt(new BlockPosition(i % 16, 0, i / 16)));
        }
        Map<BlockPosition, BlockState> snapshot = world.blocksInChunk(new ChunkPosition(0, 0));
        assertEquals(256, snapshot.size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put(new BlockPosition(0, 0, 0), BlockState.AIR));
    }
}
