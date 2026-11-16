package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndRestoresGeneratedAndModifiedBlocks() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        WorldChunk chunk = new WorldChunk(-2, 3);
        new FlatWorldGenerator().generate(chunk, 99L);
        chunk.setBlock(4, 70, 5, BlockType.STONE);

        storage.saveChunk(chunk);
        WorldChunk restored = storage.loadChunk(-2, 3).orElseThrow();

        assertEquals(BlockType.BEDROCK, restored.getBlock(0, 60, 0));
        assertEquals(BlockType.GRASS_BLOCK, restored.getBlock(15, 64, 15));
        assertEquals(BlockType.STONE, restored.getBlock(4, 70, 5));
        assertEquals(BlockType.AIR, restored.getBlock(4, 71, 5));
    }

    @Test
    void worldLoadsSavedChunksInsteadOfRegeneratingThem() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        World first = new World("NEXORA", 1L, new FlatWorldGenerator(), 0, 65, 0, storage);
        first.setBlock(-1, 70, -1, BlockType.STONE);
        first.saveAll();

        World restarted = new World("NEXORA", 1L, new FlatWorldGenerator(), 0, 65, 0, storage);
        assertEquals(BlockType.STONE, restarted.getBlock(-1, 70, -1));
        assertEquals(BlockType.AIR, restarted.getBlock(0, 70, 0));
    }

    @Test
    void rejectsCorruptChunkChecksum() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        storage.saveChunk(new WorldChunk(0, 0));
        Path file = temporaryDirectory.resolve("NEXORA/chunks/0_0.nxc");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> storage.loadChunk(0, 0));
    }

    @Test
    void rejectsUnsafeWorldDirectoryNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorldStorage(temporaryDirectory, "../outside"));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldStorage(temporaryDirectory, "bad/world"));
    }

    @Test
    void returnsEmptyForChunksThatHaveNotBeenSaved() throws Exception {
        WorldStorage storage = new WorldStorage(temporaryDirectory, "NEXORA");
        assertTrue(storage.loadChunk(10, -10).isEmpty());
    }
}
