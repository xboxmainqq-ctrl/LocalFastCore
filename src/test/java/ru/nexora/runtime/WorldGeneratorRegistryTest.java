package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldGeneratorRegistryTest {
    @Test
    void createsRegisteredBuiltInGenerator() {
        WorldGeneratorRegistry registry = WorldGeneratorRegistry.builtIn();
        assertTrue(registry.contains("overworld"));
        assertInstanceOf(OverworldChunkGenerator.class, registry.create("overworld"));
    }

    @Test
    void supportsAdditionalGeneratorFactories() {
        ChunkGenerator custom = (position, height, seed) -> {
            throw new UnsupportedOperationException("test generator");
        };
        WorldGeneratorRegistry registry = WorldGeneratorRegistry.builtIn()
                .register("my_generator", () -> custom);
        assertSame(custom, registry.create("my_generator"));
    }

    @Test
    void rejectsUnknownAndDuplicateGenerators() {
        WorldGeneratorRegistry registry = WorldGeneratorRegistry.builtIn();
        assertThrows(IllegalArgumentException.class, () -> registry.create("missing"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("overworld", OverworldChunkGenerator::new));
    }
}
