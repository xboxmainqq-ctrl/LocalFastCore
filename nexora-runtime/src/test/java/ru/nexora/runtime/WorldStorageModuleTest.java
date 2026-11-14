package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldStorageModuleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsConfiguredWorldAndPersistsItWhenStopped() throws IOException {
        WorldRegistry registry = new WorldRegistry();
        FileWorldStorage storage = new FileWorldStorage(temporaryDirectory.resolve("worlds"));
        WorldStorageModule module = new WorldStorageModule(
                registry, storage, Map.of("overworld", new WorldHeight(-64, 320))
        );

        module.start();
        World world = registry.get("overworld").orElseThrow();
        assertEquals(new WorldHeight(-64, 320), world.height());
        assertEquals(java.util.List.of("overworld"), module.managedWorldIds());

        BlockPosition block = new BlockPosition(12, 64, -7);
        world.setBlock(block, new BlockState("minecraft:diamond_block"));
        module.stop();

        assertTrue(storage.load("overworld").isPresent());
        assertEquals(new BlockState("minecraft:diamond_block"),
                storage.load("overworld").orElseThrow().blockAt(block));
        assertTrue(module.managedWorldIds().isEmpty());
    }

    @Test
    void loadsSavedWorldIntoFreshRegistryOnNextStart() throws IOException {
        FileWorldStorage storage = new FileWorldStorage(temporaryDirectory.resolve("worlds"));
        WorldRegistry firstRegistry = new WorldRegistry();
        WorldStorageModule first = new WorldStorageModule(
                firstRegistry, storage, Map.of("overworld", new WorldHeight(0, 256))
        );
        first.start();
        BlockPosition block = new BlockPosition(-18, 80, 35);
        firstRegistry.get("overworld").orElseThrow()
                .setBlock(block, new BlockState("minecraft:gold_block"));
        first.stop();

        WorldRegistry secondRegistry = new WorldRegistry();
        WorldStorageModule second = new WorldStorageModule(
                secondRegistry, storage, Map.of("overworld", new WorldHeight(0, 128))
        );
        second.start();

        World restored = secondRegistry.get("overworld").orElseThrow();
        assertEquals(new WorldHeight(0, 256), restored.height());
        assertEquals(new BlockState("minecraft:gold_block"), restored.blockAt(block));
        second.stop();
    }

    @Test
    void reusesWorldAlreadyPresentInRegistry() throws IOException {
        WorldRegistry registry = new WorldRegistry();
        InMemoryWorld existing = new InMemoryWorld("overworld", new WorldHeight(0, 128));
        registry.register(existing);
        WorldStorageModule module = new WorldStorageModule(
                registry,
                new FileWorldStorage(temporaryDirectory),
                Map.of("overworld", new WorldHeight(-64, 320))
        );

        module.start();
        assertSame(existing, registry.get("overworld").orElseThrow());
        assertTrue(module.managedWorldIds().isEmpty());
        module.stop();
    }

    @Test
    void canBeStartedAndStoppedMoreThanOnce() throws IOException {
        WorldRegistry registry = new WorldRegistry();
        WorldStorageModule module = new WorldStorageModule(
                registry,
                new FileWorldStorage(temporaryDirectory),
                Map.of("overworld", new WorldHeight(0, 256))
        );

        module.start();
        module.stop();
        module.start();
        assertTrue(registry.get("overworld").isPresent());
        module.stop();
    }

    @Test
    void rejectsDuplicateNormalizedWorldIds() {
        Map<String, WorldHeight> definitions = new LinkedHashMap<>();
        definitions.put("overworld", new WorldHeight(0, 256));
        definitions.put(" overworld ", new WorldHeight(0, 256));

        assertThrows(IllegalArgumentException.class, () -> new WorldStorageModule(
                new WorldRegistry(), new FileWorldStorage(temporaryDirectory), definitions
        ));
    }

    @Test
    void rollsBackWorldsRegisteredBeforeAnotherWorldFailsToLoad() throws IOException {
        Path worldsDirectory = temporaryDirectory.resolve("worlds");
        FileWorldStorage storage = new FileWorldStorage(worldsDirectory);
        storage.save(new InMemoryWorld("broken", new WorldHeight(0, 256)));

        Path brokenFile = worldsDirectory.resolve("broken.nxw");
        byte[] corrupted = Files.readAllBytes(brokenFile);
        corrupted[0] ^= 0x7F;
        Files.write(brokenFile, corrupted);

        WorldRegistry registry = new WorldRegistry();
        Map<String, WorldHeight> definitions = new LinkedHashMap<>();
        definitions.put("first", new WorldHeight(0, 256));
        definitions.put("broken", new WorldHeight(0, 256));
        WorldStorageModule module = new WorldStorageModule(registry, storage, definitions);

        assertThrows(IOException.class, module::start);
        assertTrue(registry.get("first").isEmpty(),
                "worlds registered earlier in startup must be rolled back");
        assertTrue(registry.get("broken").isEmpty());
        assertTrue(module.managedWorldIds().isEmpty());
    }

    @Test
    void keepsFailedShutdownSaveRetryable() throws IOException {
        Path worldsDirectory = temporaryDirectory.resolve("worlds");
        WorldRegistry registry = new WorldRegistry();
        FileWorldStorage storage = new FileWorldStorage(worldsDirectory);
        WorldStorageModule module = new WorldStorageModule(
                registry, storage, Map.of("overworld", new WorldHeight(0, 256))
        );
        module.start();

        Files.createDirectories(worldsDirectory);
        Path conflictingTarget = worldsDirectory.resolve("overworld.nxw");
        Files.createDirectory(conflictingTarget);
        assertThrows(IOException.class, module::stop);
        assertEquals(java.util.List.of("overworld"), module.managedWorldIds(),
                "module must retain ownership after a failed save so shutdown can be retried");
        assertTrue(registry.get("overworld").isPresent());

        Files.delete(conflictingTarget);
        module.stop();

        assertTrue(module.managedWorldIds().isEmpty());
        assertTrue(storage.load("overworld").isPresent());
    }

}
