package ru.nexora.runtime.rpg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RpgClassRegistryTest {
    @Test
    void defaultClassesAreDistinctAndNamespaced() {
        RpgClassRegistry registry = RpgClassRegistry.createDefaults();

        assertEquals(4, registry.snapshot().size());
        assertEquals("Воин", registry.require("nexora:warrior").displayName());
        assertEquals("Маг", registry.require("nexora:mage").displayName());
        assertNotEquals(
                registry.require("nexora:warrior").baseStats(),
                registry.require("nexora:mage").baseStats());
    }

    @Test
    void duplicateClassIdsAreRejected() {
        RpgClassRegistry registry = RpgClassRegistry.createDefaults();
        RpgClassDefinition warrior = registry.require("nexora:warrior");

        assertThrows(IllegalArgumentException.class, () -> registry.register(warrior));
    }

    @Test
    void frozenRegistryCannotBeChanged() {
        RpgClassRegistry registry = RpgClassRegistry.createDefaults();
        registry.freeze();

        assertThrows(IllegalStateException.class, () -> registry.register(
                new RpgClassDefinition("nexora:rogue", "Разбойник", "Тест",
                        new RpgStats(1, 1, 1, 1, 1), 50, 10)));
    }

    @Test
    void invalidStatsAndClassValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RpgStats(-1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RpgClassDefinition(
                "warrior", "Воин", "Описание", new RpgStats(1, 1, 1, 1, 1), 100, 10));
    }
}
