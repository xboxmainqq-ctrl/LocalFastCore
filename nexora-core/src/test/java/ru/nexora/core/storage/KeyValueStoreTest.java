package ru.nexora.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;

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
    @Test
    void saveUsesUniqueTemporaryFileAndLeavesNoStagingFiles() throws Exception {
        Path file = temporaryDirectory.resolve("data.properties");
        try (KeyValueStore store = new KeyValueStore(file)) {
            store.put("core", "mode", "safe");
            store.save();
            store.put("core", "mode", "stable");
            store.save();
        }

        try (var entries = Files.list(temporaryDirectory)) {
            assertEquals(List.of(file), entries.toList());
        }
        try (KeyValueStore reopened = new KeyValueStore(file)) {
            assertEquals("stable", reopened.get("core", "mode").orElseThrow());
        }
    }

    @Test
    void refusesSymbolicLinkStoreFilesWhenSupported() throws Exception {
        Path target = Files.writeString(temporaryDirectory.resolve("target.properties"), "safe:key=value\\n");
        Path link = temporaryDirectory.resolve("linked.properties");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException | IOException unavailable) {
            return;
        }

        assertThrows(IOException.class, () -> new KeyValueStore(link));
        assertEquals("safe:key=value\\n", Files.readString(target));
    }

    @Test
    void refusesNonRegularStorePath() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("store-directory"));
        assertThrows(IOException.class, () -> new KeyValueStore(directory));
    }

}
