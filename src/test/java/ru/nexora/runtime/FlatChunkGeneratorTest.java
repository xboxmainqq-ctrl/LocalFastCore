package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlatChunkGeneratorTest {
    private static final BlockState GRASS = new BlockState("minecraft:grass_block");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");
    private static final BlockState BEDROCK = new BlockState("minecraft:bedrock");

    @Test
    void generatesAllColumnsAndConfiguredLayers() {
        FlatChunkGenerator generator = new FlatChunkGenerator(10, 3, GRASS, DIRT, BEDROCK);
        GeneratedChunk generated = generator.generate(
                new ChunkPosition(2, -1), new WorldHeight(-64, 320), 1234L
        );

        assertEquals(16 * 16 * 4, generated.blocks().size());
        assertEquals(BEDROCK, generated.blocks().get(new BlockPosition(32, 7, -16)));
        assertEquals(DIRT, generated.blocks().get(new BlockPosition(32, 8, -16)));
        assertEquals(DIRT, generated.blocks().get(new BlockPosition(32, 9, -16)));
        assertEquals(GRASS, generated.blocks().get(new BlockPosition(32, 10, -16)));
        assertEquals(GRASS, generated.blocks().get(new BlockPosition(47, 10, -1)));
    }

    @Test
    void outputIsDeterministicAndImmutable() {
        FlatChunkGenerator generator = new FlatChunkGenerator(4, 2, GRASS, DIRT, BEDROCK);
        ChunkPosition position = new ChunkPosition(-3, 5);
        WorldHeight height = new WorldHeight(-64, 320);

        GeneratedChunk first = generator.generate(position, height, 7L);
        GeneratedChunk second = generator.generate(position, height, 99L);

        assertEquals(first, second, "flat generator does not use the seed and must be deterministic");
        assertThrows(UnsupportedOperationException.class,
                () -> first.blocks().put(new BlockPosition(-48, 4, 80), GRASS));
    }

    @Test
    void generatedChunkDefensivelyCopiesInputWithoutExposingMutableStorage() {
        Map<BlockPosition, BlockState> source = new HashMap<>();
        BlockPosition block = new BlockPosition(1, 5, 1);
        source.put(block, GRASS);

        GeneratedChunk generated = new GeneratedChunk(new ChunkPosition(0, 0), source);
        source.clear();

        assertEquals(Map.of(block, GRASS), generated.blocks());
        assertThrows(UnsupportedOperationException.class,
                () -> generated.blocks().put(new BlockPosition(2, 5, 2), DIRT));
    }

    @Test
    void rejectsTerrainOutsideWorldHeight() {
        FlatChunkGenerator generator = new FlatChunkGenerator(10, 4, GRASS, DIRT, BEDROCK);
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(new ChunkPosition(0, 0), new WorldHeight(8, 20), 0L));
    }

    @Test
    void rejectsAirTerrainBlocks() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlatChunkGenerator(10, 2, BlockState.AIR, DIRT, BEDROCK));
    }

    @Test
    void generatedChunkRejectsBlocksFromAnotherChunkAndAir() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratedChunk(
                new ChunkPosition(0, 0), Map.of(new BlockPosition(16, 0, 0), GRASS)
        ));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedChunk(
                new ChunkPosition(0, 0), Map.of(new BlockPosition(0, 0, 0), BlockState.AIR)
        ));
    }

    @Test
    void rejectsChunkCoordinatesThatOverflowBlockCoordinates() {
        FlatChunkGenerator generator = new FlatChunkGenerator(10, 2, GRASS, DIRT, BEDROCK);
        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                new ChunkPosition(Integer.MAX_VALUE, 0), new WorldHeight(0, 20), 0L
        ));
    }
}
