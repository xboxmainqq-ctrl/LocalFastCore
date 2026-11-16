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
    void createsBroadContinentsAndDistinctMountainRelief() {
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        int belowSea = 0;
        int samples = 0;
        for (int x = -4096; x <= 4096; x += 128) {
            for (int z = -4096; z <= 4096; z += 128) {
                int elevation = TerrainHeightSampler.sample(x, z, 20261116L, height, 1.0);
                minimum = Math.min(minimum, elevation);
                maximum = Math.max(maximum, elevation);
                if (elevation < OverworldChunkGenerator.DEFAULT_SEA_LEVEL) belowSea++;
                samples++;
            }
        }
        assertTrue(minimum < 50, "Expected low coastal shelves and ocean basins");
        assertTrue(maximum > 110, "Expected tall mountain systems");
        assertTrue(maximum - minimum > 70, "Expected a wide natural elevation range");
        assertTrue(belowSea > samples / 5, "Expected a meaningful amount of coastline and ocean");
    }

    @Test
    void generatesBedrockTerrainAndWaterWithinChunkBounds() {
        GeneratedChunk generated = generator.generate(new ChunkPosition(-2, -1), height, 42L);

        assertEquals(256, generated.blocks().values().stream()
                .filter(state -> state.identifier().equals("minecraft:bedrock")).count());
        assertEquals(256, generated.biomeColumns().size());
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
    void generatesUndergroundCavesWithoutBreakingChunkBounds() {
        ChunkPosition chunk = new ChunkPosition(0, 0);
        long seed = 123456789L;
        GeneratedChunk generated = generator.generate(chunk, height, seed);
        boolean foundCarvedCave = false;
        for (int x = 0; x < 16 && !foundCarvedCave; x++) {
            for (int z = 0; z < 16 && !foundCarvedCave; z++) {
                for (int y = -54; y <= 48; y++) {
                    BlockPosition position = new BlockPosition(x, y, z);
                    if (OverworldChunkGenerator.isCave(x, y, z, seed)
                            && !generated.blocks().containsKey(position)) {
                        foundCarvedCave = true;
                        break;
                    }
                }
            }
        }
        assertTrue(foundCarvedCave, "Expected at least one carved underground cave cell");
        assertTrue(generated.blocks().keySet().stream()
                .allMatch(position -> position.chunkPosition().equals(chunk)));
    }

    @Test
    void lushCavePocketsGenerateMossAndCaveDecorationsDeterministically() {
        boolean foundLushFeature = false;
        for (int chunkX = -3; chunkX <= 3 && !foundLushFeature; chunkX++) {
            for (int chunkZ = -3; chunkZ <= 3 && !foundLushFeature; chunkZ++) {
                GeneratedChunk generated = generator.generate(
                        new ChunkPosition(chunkX, chunkZ), height, 123456789L);
                foundLushFeature = generated.blocks().values().stream().anyMatch(state ->
                        state.identifier().equals("minecraft:moss_block")
                                || state.identifier().equals("minecraft:moss_carpet")
                                || state.identifier().equals("minecraft:glow_lichen")
                                || state.identifier().equals("minecraft:spore_blossom")
                                || state.identifier().equals("minecraft:hanging_roots"));
            }
        }
        assertTrue(foundLushFeature, "Expected lush vegetation in underground cave pockets");
    }

    @Test
    void undergroundLakesAndCrystalGeodesAreGenerated() {
        boolean foundUndergroundLake = false;
        boolean foundCrystal = false;
        for (int chunkX = -8; chunkX <= 8 && !(foundUndergroundLake && foundCrystal); chunkX++) {
            for (int chunkZ = -8; chunkZ <= 8 && !(foundUndergroundLake && foundCrystal); chunkZ++) {
                GeneratedChunk generated = generator.generate(
                        new ChunkPosition(chunkX, chunkZ), height, 123456789L);
                foundUndergroundLake |= generated.fluids().keySet().stream()
                        .anyMatch(position -> position.y() < 0);
                foundCrystal |= generated.blocks().values().stream().anyMatch(state ->
                        state.identifier().equals("minecraft:amethyst_block")
                                || state.identifier().equals("minecraft:amethyst_cluster")
                                || state.identifier().equals("minecraft:calcite"));
            }
        }
        assertTrue(foundUndergroundLake, "Expected a naturally generated underground water pool");
        assertTrue(foundCrystal, "Expected at least one amethyst geode feature");
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
