package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OverworldBiomeGenerationTest {
    @Test
    void generatedColumnsCarryRegisteredBiomeIdsAndSurfaceMaterials() {
        OverworldChunkGenerator generator = new OverworldChunkGenerator();
        BiomeRegistry registry = BiomeRegistry.builtIn();
        WorldHeight height = new WorldHeight(-64, 320);
        GeneratedChunk chunk = generator.generate(new ChunkPosition(-8, 11), height, 778899L);

        assertEquals(256, chunk.biomeColumns().size());
        chunk.biomeColumns().forEach((column, biomeId) -> {
            BiomeDefinition biome = registry.get(biomeId);
            assertNotNull(biome, "Every generated biome ID must exist in the registry");
            assertTrue(chunk.blocks().keySet().stream().anyMatch(position ->
                    position.x() == column.x() && position.z() == column.z()
                            && position.y() >= height.minY()
                            && position.y() < height.maxYExclusive()));
        });
    }

    @Test
    void loadedBiomeColumnsCoverTheEntireWorldHeight() {
        WorldHeight height = new WorldHeight(-64, 320);
        GeneratedChunk generated = new OverworldChunkGenerator().generate(
                new ChunkPosition(-8, 11), height, 778899L);
        WorldChunk loaded = WorldChunk.from(generated);
        generated.biomeColumns().forEach((column, biomeId) -> {
            assertEquals(biomeId, loaded.biomeAt(new BlockPosition(column.x(), height.minY(), column.z())));
            assertEquals(biomeId, loaded.biomeAt(new BlockPosition(column.x(), 0, column.z())));
            assertEquals(biomeId, loaded.biomeAt(new BlockPosition(
                    column.x(), height.maxYExclusive() - 1, column.z())));
        });
        assertEquals(24, loaded.biomeSectionYs().size());
    }

    @Test
    void sameSeedProducesIdenticalBiomeAssignments() {
        OverworldChunkGenerator generator = new OverworldChunkGenerator();
        ChunkPosition position = new ChunkPosition(17, -23);
        WorldHeight height = new WorldHeight(-64, 320);
        assertEquals(
                generator.generate(position, height, 2026L).biomeColumns(),
                generator.generate(position, height, 2026L).biomeColumns()
        );
    }
}
