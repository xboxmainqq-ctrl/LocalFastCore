package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NamespacedRegistryTest {
    @Test
    void registersNamespacedDefinitionsAndResolvesThem() {
        NamespacedRegistry<String> blocks = new NamespacedRegistry<>("block");

        blocks.register("minecraft:stone", "Stone");
        blocks.register("nexora:crystal_block", "Crystal");

        assertEquals("Stone", blocks.require("minecraft:stone"));
        assertEquals("Crystal", blocks.find("nexora:crystal_block").orElseThrow());
        assertTrue(blocks.find("nexora:missing").isEmpty());
        assertEquals(2, blocks.size());
    }

    @Test
    void rejectsInvalidIdentifiersNullDefinitionsAndDuplicateIds() {
        NamespacedRegistry<String> items = new NamespacedRegistry<>("item");

        assertThrows(IllegalArgumentException.class, () -> items.register("stone", "Stone"));
        assertThrows(IllegalArgumentException.class, () -> items.register("NEXORA:Stone", "Stone"));
        assertThrows(IllegalArgumentException.class, () -> items.register("nexora:", "Stone"));
        assertThrows(NullPointerException.class, () -> items.register("nexora:empty", null));

        items.register("minecraft:stone", "Stone");
        assertThrows(IllegalArgumentException.class,
                () -> items.register("minecraft:stone", "Replacement"));
        assertEquals("Stone", items.require("minecraft:stone"));
    }

    @Test
    void snapshotsAreSortedImmutableAndDetached() {
        NamespacedRegistry<Integer> entities = new NamespacedRegistry<>("entity");
        entities.register("nexora:zombie", 2);
        entities.register("minecraft:cow", 1);

        Map<String, Integer> snapshot = entities.snapshot();

        assertEquals(List.of("minecraft:cow", "nexora:zombie"), List.copyOf(snapshot.keySet()));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put("nexora:dragon", 3));

        entities.register("nexora:dragon", 3);
        assertEquals(2, snapshot.size());
        assertEquals(3, entities.size());
    }

    @Test
    void freezeLocksRegistrationAndReturnsStableSnapshot() {
        NamespacedRegistry<String> blocks = new NamespacedRegistry<>("block");
        blocks.register("minecraft:air", "Air");

        Map<String, String> frozen = blocks.freeze();

        assertTrue(blocks.isFrozen());
        assertEquals(Map.of("minecraft:air", "Air"), frozen);
        assertThrows(IllegalStateException.class,
                () -> blocks.register("nexora:marble", "Marble"));
        assertEquals(frozen, blocks.snapshot());
    }

    @Test
    void requireRejectsUnknownIdentifiersAndRegistryKindIsAvailable() {
        NamespacedRegistry<String> recipes = new NamespacedRegistry<>("recipe");

        assertEquals("recipe", recipes.contentKind());
        assertThrows(IllegalArgumentException.class,
                () -> recipes.require("minecraft:unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> recipes.find("invalid identifier"));
        assertThrows(IllegalArgumentException.class,
                () -> new NamespacedRegistry<>(" "));
    }
}
