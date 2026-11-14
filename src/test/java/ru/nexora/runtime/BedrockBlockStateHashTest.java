package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BedrockBlockStateHashTest {
    @Test
    void matchesKnownVanillaStoneNetworkHash() {
        assertEquals(-2144268767, BedrockBlockStateHash.hash(new BlockState("minecraft:stone")));
    }

    @Test
    void sortsPropertiesBeforeHashing() {
        BlockState first = new BlockState("minecraft:oak_log", Map.of(
                "axis", "s:y", "waterlogged", "b:0"));
        BlockState second = new BlockState("minecraft:oak_log", Map.of(
                "waterlogged", "b:0", "axis", "s:y"));

        assertEquals(BedrockBlockStateHash.hash(first), BedrockBlockStateHash.hash(second));
    }

    @Test
    void preservesNbtPropertyTypesAndRejectsMalformedTypedValues() {
        BlockState stringValue = new BlockState("minecraft:test_block", Map.of("enabled", "s:1"));
        BlockState byteValue = new BlockState("minecraft:test_block", Map.of("enabled", "b:1"));
        assertNotEquals(BedrockBlockStateHash.hash(stringValue), BedrockBlockStateHash.hash(byteValue));

        assertThrows(IllegalArgumentException.class, () ->
                BedrockBlockStateHash.hash(new BlockState("minecraft:test_block",
                        Map.of("enabled", "b:999"))));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockBlockStateHash.hash(new BlockState("minecraft:test_block",
                        Map.of("enabled", "x:1"))));
    }

    @Test
    void handlesReservedUnknownStateAndHashRegistry() {
        assertEquals(-2, BedrockBlockStateHash.hash(new BlockState("minecraft:unknown")));
        assertEquals(BedrockBlockStateHash.hash(BlockState.AIR),
                BedrockRuntimeBlockRegistry.hashing().runtimeId(BlockState.AIR));
    }
}
