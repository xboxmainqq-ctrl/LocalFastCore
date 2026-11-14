package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NexoraMainTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesDataDirectoryAndSeed() {
        NexoraMain.LaunchOptions options = NexoraMain.parseArguments(new String[] {
                "--data-dir", temporaryDirectory.resolve("server-data").toString(),
                "--seed", "-987654321"
        });

        assertEquals(temporaryDirectory.resolve("server-data").toAbsolutePath().normalize(),
                options.dataDirectory());
        assertEquals(-987654321L, options.seed());
        assertFalse(options.help());
        assertFalse(options.version());
    }

    @Test
    void supportsHelpAndVersionFlags() {
        assertTrue(NexoraMain.parseArguments(new String[] {"--help"}).help());
        assertTrue(NexoraMain.parseArguments(new String[] {"--version"}).version());
    }

    @Test
    void rejectsUnknownAndIncompleteArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> NexoraMain.parseArguments(new String[] {"--unknown"}));
        assertThrows(IllegalArgumentException.class,
                () -> NexoraMain.parseArguments(new String[] {"--data-dir"}));
        assertThrows(IllegalArgumentException.class,
                () -> NexoraMain.parseArguments(new String[] {"--seed", "not-a-number"}));
    }

    @Test
    void startsWorldAndTickLoopThenPersistsWorldOnStop() {
        Path dataDirectory = temporaryDirectory.resolve("nexora");
        NexoraMain.RuntimeHandle runtime = NexoraMain.createRuntime(dataDirectory, 42L, ignored -> { });

        runtime.server().start();
        try {
            World world = runtime.worlds().get("world").orElseThrow();
            assertTrue(world.isChunkLoaded(new ChunkPosition(0, 0)));
            assertEquals(new BlockState("minecraft:grass_block"),
                    world.blockAt(new BlockPosition(0, 64, 0)));
            assertTrue(runtime.gameLoop().isRunning());
            assertEquals(ServerState.RUNNING, runtime.server().state());
        } finally {
            runtime.server().stop();
        }

        assertFalse(runtime.gameLoop().isRunning());
        assertEquals(ServerState.STOPPED, runtime.server().state());
        assertTrue(Files.isRegularFile(dataDirectory.resolve("worlds").resolve("world.nxw")));
    }
}
