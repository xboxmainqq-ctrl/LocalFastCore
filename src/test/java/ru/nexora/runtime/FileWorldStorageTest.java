package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileWorldStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndRestoresWorldAcrossInstances() throws IOException {
        FileWorldStorage storage = new FileWorldStorage(temporaryDirectory.resolve("worlds"));
        InMemoryWorld original = new InMemoryWorld("overworld", new WorldHeight(-64, 320));
        original.setBlock(new BlockPosition(3, 70, 9),
                new BlockState("minecraft:stone"));
        original.setBlock(new BlockPosition(-1, -20, -17),
                new BlockState("minecraft:oak_log", Map.of("pillar_axis", "y")));

        storage.save(original);

        InMemoryWorld loaded = storage.load("overworld").orElseThrow();
        assertEquals("overworld", loaded.id());
        assertEquals(new WorldHeight(-64, 320), loaded.height());
        assertEquals(new BlockState("minecraft:stone"),
                loaded.blockAt(new BlockPosition(3, 70, 9)));
        assertEquals(new BlockState("minecraft:oak_log", Map.of("pillar_axis", "y")),
                loaded.blockAt(new BlockPosition(-1, -20, -17)));
        assertEquals(2, loaded.loadedChunkCount());
    }


    @Test
    void savesAndRestoresBiomeSections() throws IOException {
        FileWorldStorage storage = new FileWorldStorage(temporaryDirectory.resolve("biome-worlds"));
        InMemoryWorld original = new InMemoryWorld("biomes", new WorldHeight(-64, 320));
        BlockPosition first = new BlockPosition(-1, -17, -16);
        BlockPosition second = new BlockPosition(3, 70, 9);
        original.setBiome(first, 42);
        original.setBiome(second, 7);
        original.setBlock(new BlockPosition(0, 64, 0), new BlockState("minecraft:stone"));

        storage.save(original);

        InMemoryWorld loaded = storage.load("biomes").orElseThrow();
        assertEquals(42, loaded.biomeAt(first));
        assertEquals(7, loaded.biomeAt(second));
        assertEquals(BiomeSection.DEFAULT_BIOME_ID, loaded.biomeAt(new BlockPosition(1, 64, 1)));
        assertEquals(new BlockState("minecraft:stone"),
                loaded.blockAt(new BlockPosition(0, 64, 0)));
    }

    @Test
    void savingAgainReplacesPreviousSnapshot() throws IOException {
        FileWorldStorage storage = new FileWorldStorage(temporaryDirectory);
        InMemoryWorld world = new InMemoryWorld("main", new WorldHeight(0, 128));
        BlockPosition position = new BlockPosition(1, 10, 1);
        world.setBlock(position, new BlockState("minecraft:stone"));
        storage.save(world);

        world.setBlock(position, new BlockState("minecraft:dirt"));
        storage.save(world);

        assertEquals(new BlockState("minecraft:dirt"),
                storage.load("main").orElseThrow().blockAt(position));
    }

    @Test
    void missingWorldReturnsEmpty() throws IOException {
        FileWorldStorage storage = new FileWorldStorage(temporaryDirectory);
        assertTrue(storage.load("missing").isEmpty());
    }

    @Test
    void rejectsUnsafeWorldIds() {
        FileWorldStorage storage = new FileWorldStorage(temporaryDirectory);
        assertThrows(IllegalArgumentException.class, () -> storage.load("../outside"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.save(new InMemoryWorld("../outside", new WorldHeight(0, 16))));
    }

    @Test
    void detectsCorruptedWorldFile() throws IOException {
        FileWorldStorage storage = new FileWorldStorage(temporaryDirectory);
        InMemoryWorld world = new InMemoryWorld("corrupt", new WorldHeight(0, 32));
        world.setBlock(new BlockPosition(0, 1, 0), new BlockState("minecraft:stone"));
        storage.save(world);

        Path file = temporaryDirectory.resolve("corrupt.nxw");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> storage.load("corrupt"));
    }

    @Test
    void rejectsSymbolicLinkWorldFileWhenSupported() throws IOException {
        FileWorldStorage storage = new FileWorldStorage(temporaryDirectory);
        Path outside = temporaryDirectory.resolve("outside.nxw");
        Files.writeString(outside, "not a world");
        Path link = temporaryDirectory.resolve("linked.nxw");
        try {
            Files.createSymbolicLink(link, outside.getFileName());
        } catch (UnsupportedOperationException | SecurityException exception) {
            return;
        } catch (IOException exception) {
            return;
        }
        assertThrows(IOException.class, () -> storage.load("linked"));
    }
}
