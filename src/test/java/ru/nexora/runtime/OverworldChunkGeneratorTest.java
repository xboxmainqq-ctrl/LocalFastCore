package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OverworldChunkGeneratorTest {
    private final OverworldChunkGenerator generator = new OverworldChunkGenerator();
    private final WorldHeight height = new WorldHeight(-64, 320);

    @Test
    void generationIsDeterministicForSameSeedAndPosition() {
        ChunkPosition position = new ChunkPosition(-3, 5);
        GeneratedChunk first = generator.generate(position, height, 987654321L);
        GeneratedChunk second = generator.generate(position, height, 987654321L);

        assertEquals(first, second);
    }

    @Test
    void differentSeedsProduceDifferentTerrain() {
        ChunkPosition position = new ChunkPosition(0, 0);
        GeneratedChunk first = generator.generate(position, height, 1L);
        GeneratedChunk second = generator.generate(position, height, 2L);

        assertNotEquals(first.blocks(), second.blocks());
    }

    @Test
    void generatesBedrockTerrainAndWaterWithinChunkBounds() {
        GeneratedChunk generated = generator.generate(new ChunkPosition(-2, -1), height, 42L);

        assertEquals(256, generated.blocks().values().stream()
                .filter(state -> state.identifier().equals("minecraft:bedrock")).count());
        assertTrue(generated.blocks().values().stream()
                .anyMatch(state -> state.identifier().equals("minecraft:grass_block")));
        assertTrue(generated.blocks().values().stream()
                .anyMatch(state -> state.identifier().equals("minecraft:stone")));
        assertTrue(generated.fluids().values().stream()
                .allMatch(state -> state.identifier().equals("minecraft:water")));
        assertTrue(generated.blocks().keySet().stream().allMatch(p -> p.chunkPosition().equals(new ChunkPosition(-2, -1))));
        assertTrue(generated.fluids().keySet().stream().allMatch(p -> p.chunkPosition().equals(new ChunkPosition(-2, -1))));
    }

    @Test
    void supportsSmallWorldHeightsAndRejectsImpossibleHeights() {
        GeneratedChunk small = generator.generate(new ChunkPosition(0, 0), new WorldHeight(0, 64), 9L);
        assertTrue(small.blocks().keySet().stream().allMatch(p -> p.y() >= 0 && p.y() < 64));
        assertTrue(small.fluids().keySet().stream().allMatch(p -> p.y() >= 0 && p.y() < 64));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                new ChunkPosition(0, 0), new WorldHeight(0, 7), 9L));
    }

    @Test
    void rejectsChunkCoordinateOverflow() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                new ChunkPosition(Integer.MAX_VALUE, 0), height, 0L));
    }

    @Test
    void generatedTerrainIsImmutable() {
        GeneratedChunk generated = generator.generate(new ChunkPosition(0, 0), height, 17L);
        assertThrows(UnsupportedOperationException.class,
                () -> generated.blocks().put(new BlockPosition(0, 0, 0), new BlockState("minecraft:stone")));
        assertThrows(UnsupportedOperationException.class,
                () -> generated.fluids().put(new BlockPosition(0, 0, 0), new BlockState("minecraft:water")));
    }
}
