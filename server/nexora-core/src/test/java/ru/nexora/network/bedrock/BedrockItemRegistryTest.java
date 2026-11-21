package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockItemRegistryTest {
    private static BedrockItemRegistry.Entry entry(String id, int runtimeId) {
        return new BedrockItemRegistry.Entry(id,
                new BedrockInventoryContentCodec.ItemDefinition(runtimeId, 0, 0, new byte[0]));
    }

    @Test
    void resolvesDefinitionsByNamespacedIdAndRuntimeId() {
        var shard = entry("nexora:arcane_shard", 257);
        var wand = entry("nexora:apprentice_wand", 258);
        var registry = new BedrockItemRegistry(List.of(shard, wand));

        assertEquals(2, registry.size());
        assertSame(shard, registry.findByIdentifier("nexora:arcane_shard"));
        assertSame(wand, registry.findByRuntimeItemId(258));
        assertEquals(257, registry.resolve("nexora:arcane_shard").runtimeItemId());
        assertNull(registry.resolve("nexora:missing"));
        assertNull(registry.findByRuntimeItemId(999));
    }

    @Test
    void preservesRegistrationOrderAndReturnsImmutableEntries() {
        var first = entry("nexora:first", 257);
        var second = entry("nexora:second", 258);
        var registry = new BedrockItemRegistry(List.of(first, second));

        assertEquals(List.of(first, second), registry.entries());
        assertThrows(UnsupportedOperationException.class, () -> registry.entries().clear());
    }

    @Test
    void rejectsDuplicateNamespacedIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new BedrockItemRegistry(List.of(
                entry("nexora:shard", 257), entry("nexora:shard", 258))));
    }

    @Test
    void rejectsDuplicateRuntimeIds() {
        assertThrows(IllegalArgumentException.class, () -> new BedrockItemRegistry(List.of(
                entry("nexora:first", 257), entry("nexora:second", 257))));
    }

    @Test
    void rejectsInvalidOrMissingDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> entry("arcane_shard", 257));
        assertThrows(NullPointerException.class,
                () -> new BedrockItemRegistry(java.util.Arrays.asList((BedrockItemRegistry.Entry) null)));
        assertThrows(NullPointerException.class,
                () -> new BedrockItemRegistry.Entry("nexora:shard", null));
    }

    @Test
    void rejectsRuntimeIdsOutsideSignedInt16WireRange() {
        assertThrows(IllegalArgumentException.class, () -> new BedrockItemRegistry(List.of(
                entry("nexora:too_large", 32_768))));
        assertThrows(IllegalArgumentException.class, () -> new BedrockItemRegistry(List.of(
                entry("nexora:too_small", -32_769))));
    }

    @Test
    void rejectsMalformedNetworkNbtRootForItemComponentData() {
        var definition = new BedrockInventoryContentCodec.ItemDefinition(300, 0, 0, new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> new BedrockItemRegistry.Entry(
                "nexora:wrong_root", definition, 64, false, 2, new byte[] {9, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> new BedrockItemRegistry.Entry(
                "nexora:truncated_name", definition, 64, false, 2, new byte[] {10, 2, 'x'}));
    }

    @Test
    void emptyRegistryIsSafeAndUsableAsInventoryResolver() {
        var registry = BedrockItemRegistry.empty();
        assertEquals(0, registry.size());
        assertTrue(registry.entries().isEmpty());
        assertNull(registry.resolve("nexora:unknown"));
    }
}
