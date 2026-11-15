package ru.nexora.client;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public final class LocalWorldStoreTest {
    @Test
    public void savesAndReloadsChunksAndPlayerPosition() throws Exception {
        File root = Files.createTempDirectory("nexora-world-store").toFile();
        LocalWorldStore store = new LocalWorldStore(root);
        NexoraAndroidProtocolCodec.ChunkData chunk =
                new NexoraAndroidProtocolCodec.ChunkData(2, -3, List.of(
                        new NexoraAndroidProtocolCodec.ChunkBlock(1, 70, 4, "minecraft:grass_block"),
                        new NexoraAndroidProtocolCodec.ChunkBlock(1, 69, 4, "minecraft:dirt")));

        store.saveChunk("test_world", chunk);
        store.savePlayerPosition("test_world",
                new LocalWorldStore.PlayerPosition(12.25, 71.0, -8.5));

        List<NexoraAndroidProtocolCodec.ChunkData> loaded = store.loadChunks("test_world");
        assertEquals(1, loaded.size());
        assertEquals(chunk, loaded.get(0));
        assertEquals(new LocalWorldStore.PlayerPosition(12.25, 71.0, -8.5),
                store.loadPlayerPosition("test_world"));
        store.deleteWorld("test_world");
        assertTrue(store.loadChunks("test_world").isEmpty());
        assertNull(store.loadPlayerPosition("test_world"));
    }

    @Test
    public void savesAndReloadsLocalHotbar() throws Exception {
        LocalWorldStore store = new LocalWorldStore(
                Files.createTempDirectory("nexora-local-hotbar").toFile());
        List<LocalWorldStore.InventorySlot> inventory = List.of(
                new LocalWorldStore.InventorySlot("minecraft:dirt", 32),
                new LocalWorldStore.InventorySlot("minecraft:stone", 7),
                new LocalWorldStore.InventorySlot("", 0),
                new LocalWorldStore.InventorySlot("", 0),
                new LocalWorldStore.InventorySlot("", 0),
                new LocalWorldStore.InventorySlot("", 0),
                new LocalWorldStore.InventorySlot("", 0),
                new LocalWorldStore.InventorySlot("", 0),
                new LocalWorldStore.InventorySlot("", 0));

        assertNull(store.loadInventory("test_world"));
        store.saveInventory("test_world", inventory);
        assertEquals(inventory, store.loadInventory("test_world"));
    }

    @Test
    public void missingWorldReturnsEmptyState() throws Exception {
        LocalWorldStore store = new LocalWorldStore(
                Files.createTempDirectory("nexora-empty-world").toFile());
        assertTrue(store.loadChunks("not_created").isEmpty());
        assertNull(store.loadPlayerPosition("not_created"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPathTraversalWorldIds() throws Exception {
        LocalWorldStore store = new LocalWorldStore(
                Files.createTempDirectory("nexora-invalid-world").toFile());
        store.loadChunks("../outside");
    }
}
