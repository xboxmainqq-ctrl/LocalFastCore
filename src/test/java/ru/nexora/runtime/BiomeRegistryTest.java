package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BiomeRegistryTest {
    private final BiomeRegistry registry = BiomeRegistry.builtIn();

    @Test
    void builtInRegistryContainsOriginalBiomeSet() {
        assertEquals(9, registry.definitions().size());
        assertEquals("mistwood", registry.get(BiomeRegistry.MISTWOOD_ID).key());
        assertEquals("glassgrass_steppe", registry.get("glassgrass_steppe").key());
        assertEquals("emberfen", registry.get(BiomeRegistry.EMBERFEN_ID).key());
        assertEquals("frostveil", registry.get(BiomeRegistry.FROSTVEIL_ID).key());
        assertEquals("skyglass_highlands", registry.get(BiomeRegistry.SKYGLASS_HIGHLANDS_ID).key());
        assertEquals("saltwind_basin", registry.get(BiomeRegistry.SALTWIND_BASIN_ID).key());
        assertEquals("bloomveil_grove", registry.get(BiomeRegistry.BLOOMVEIL_GROVE_ID).key());
        assertEquals("ashen_wastes", registry.get(BiomeRegistry.ASHEN_WASTES_ID).key());
        assertEquals("moonfrost_tundra", registry.get(BiomeRegistry.MOONFROST_TUNDRA_ID).key());
    }

    @Test
    void originalBiomesAreSelectedByTheirClimateProfiles() {
        assertEquals(BiomeRegistry.BLOOMVEIL_GROVE_ID,
                registry.select(new ClimateSample(0.64, 0.97, 0.24)).id());
        assertEquals(BiomeRegistry.ASHEN_WASTES_ID,
                registry.select(new ClimateSample(0.97, 0.25, 0.57)).id());
        assertEquals(BiomeRegistry.MOONFROST_TUNDRA_ID,
                registry.select(new ClimateSample(0.03, 0.85, 0.85)).id());
    }

    @Test
    void selectionPrefersClosestClimateDefinition() {
        BiomeDefinition selected = registry.select(new ClimateSample(0.58, 0.80, 0.43));
        assertEquals(BiomeRegistry.MISTWOOD_ID, selected.id());
    }

    @Test
    void terrainReliefBlendsSmoothlyAcrossNearbyClimateSamples() {
        ClimateSample first = new ClimateSample(0.58, 0.80, 0.43);
        ClimateSample nearby = new ClimateSample(0.59, 0.80, 0.43);
        double firstRelief = registry.blendedReliefMultiplier(first);
        double nearbyRelief = registry.blendedReliefMultiplier(nearby);
        assertTrue(firstRelief >= 0.25 && firstRelief <= 2.5);
        assertTrue(Math.abs(firstRelief - nearbyRelief) < 0.08);
    }

    @Test
    void rejectsDuplicateIdsAndInvalidClimateValues() {
        BiomeDefinition biome = registry.get(BiomeRegistry.MISTWOOD_ID);
        assertThrows(IllegalArgumentException.class,
                () -> new BiomeRegistry(List.of(biome, biome)));
        assertThrows(IllegalArgumentException.class,
                () -> new ClimateSample(1.1, 0.5, 0.5));
    }

    @Test
    void climateSamplingIsDeterministicAndBounded() {
        ClimateSample first = BiomeClimateSampler.sample(-812, 4096, 1234L, 72);
        ClimateSample second = BiomeClimateSampler.sample(-812, 4096, 1234L, 72);
        assertEquals(first, second);
        assertTrue(first.temperature() >= 0.0 && first.temperature() <= 1.0);
        assertTrue(first.moisture() >= 0.0 && first.moisture() <= 1.0);
        assertTrue(first.elevation() >= 0.0 && first.elevation() <= 1.0);
        assertNotEquals(first, BiomeClimateSampler.sample(-812, 4096, 9876L, 72));
    }
}
