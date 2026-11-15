package ru.nexora.runtime;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BedrockBlockCompatibilityRegistryTest {
    @Test void builtInRegistryCoversGeneratorBlocks() {
        var registry = BedrockBlockCompatibilityRegistry.builtIn();
        for (String id : List.of("minecraft:bedrock", "minecraft:stone", "minecraft:dirt",
                "minecraft:grass_block", "minecraft:podzol", "minecraft:sand", "minecraft:mud",
                "minecraft:snow_block", "minecraft:water", "minecraft:oak_log", "minecraft:oak_leaves",
                "minecraft:birch_log", "minecraft:birch_leaves", "minecraft:dark_oak_log",
                "minecraft:dark_oak_leaves", "minecraft:spruce_log", "minecraft:spruce_leaves",
                "minecraft:dead_bush")) {
            assertTrue(registry.supports(id), id);
            assertEquals(id, registry.require(id).bedrockIdentifier());
        }
    }
    @Test void customWorldBlockCanMapToVanillaBedrockBlock() {
        var registry = new BedrockBlockCompatibilityRegistry(List.of(
                new BedrockBlockMapping("nexora:mistwood_soil", "minecraft:podzol", false)));
        assertEquals("minecraft:podzol", registry.require("nexora:mistwood_soil").bedrockIdentifier());
    }
    @Test void adapterTranslatesWorldStateBeforeRuntimePaletteLookup() {
        var mappings = new BedrockBlockCompatibilityRegistry(List.of(
                new BedrockBlockMapping("nexora:mistwood_soil", "minecraft:podzol", false)));
        var palette = BedrockRuntimeBlockRegistry.of(java.util.Map.of(
                BlockState.AIR, 0,
                new BlockState("minecraft:podzol"), 42));
        var adapted = mappings.adapt(palette);
        assertEquals(42, adapted.runtimeId(new BlockState("nexora:mistwood_soil")));
    }

    @Test void unknownBlocksAreRejectedExplicitly() {
        var registry = BedrockBlockCompatibilityRegistry.builtIn();
        assertFalse(registry.supports("nexora:crystal_stone"));
        assertThrows(IllegalArgumentException.class, () -> registry.require("nexora:crystal_stone"));
    }
    @Test void duplicateWorldIdentifiersAreRejected() {
        var mapping = new BedrockBlockMapping("nexora:soil", "minecraft:dirt", false);
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockBlockCompatibilityRegistry(List.of(mapping, mapping)));
    }
    @Test void malformedIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockBlockMapping("stone", "minecraft:stone", false));
    }
}
