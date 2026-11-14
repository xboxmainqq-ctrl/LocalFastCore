package ru.nexora.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KeyValueStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void persistsNamespacedValuesAcrossInstances() throws Exception {
        Path file = temporaryDirectory.resolve("data.properties");
        try (KeyValueStore store = new KeyValueStore(file)) {
            store.put("rpg", "mana", "80");
            store.save();
        }

        try (KeyValueStore reopened = new KeyValueStore(file)) {
            assertEquals("80", reopened.get("rpg", "mana").orElseThrow());
            assertTrue(reopened.remove("rpg", "mana"));
            assertTrue(reopened.get("rpg", "mana").isEmpty());
        }
    }

    @Test
    void rejectsInvalidNamespace() throws Exception {
        try (KeyValueStore store = new KeyValueStore(temporaryDirectory.resolve("data.properties"))) {
            assertThrows(IllegalArgumentException.class, () -> store.put("bad:namespace", "key", "value"));
        }
    }
}
