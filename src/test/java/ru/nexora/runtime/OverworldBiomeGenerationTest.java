package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OverworldBiomeGenerationTest {
    @Test
    void generatedColumnsCarryRegisteredBiomeIdsAndBiomeSurfaceMaterials() {
        OverworldChunkGenerator generator = new OverworldChunkGenerator();
        BiomeRegistry registry = BiomeRegistry.builtIn();
        WorldHeight height = new WorldHeight(-64, 320);
        GeneratedChunk chunk = generator.generate(new ChunkPosition(-8, 11), height, 778899L);

        assertEquals(256, chunk.biomes().size());
        chunk.biomes().forEach((surface, biomeId) -> {
            BiomeDefinition biome = registry.get(biomeId);
            assertNotNull(biome, "Every generated biome ID must exist in the registry");
            assertEquals(biome.surfaceBlock(), chunk.blocks().get(surface));
        });
    }

    @Test
    void sameSeedProducesIdenticalBiomeAssignments() {
        OverworldChunkGenerator generator = new OverworldChunkGenerator();
        ChunkPosition position = new ChunkPosition(17, -23);
        WorldHeight height = new WorldHeight(-64, 320);
        assertEquals(
                generator.generate(position, height, 2026L).biomes(),
                generator.generate(position, height, 2026L).biomes()
        );
    }
}
