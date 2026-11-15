package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class LocalWorldRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    private FlatChunkGenerator generator() {
        return new FlatChunkGenerator(
                63, 3,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:dirt"),
                new BlockState("minecraft:bedrock")
        );
    }

    @Test
    void startsTicksAndStreamsChunksAroundPlayer() throws Exception {
        FileWorldStorage storage = new FileWorldStorage(temporaryDirectory);
        LocalWorldSession session = LocalWorldSession.open(
                storage, "runtime", new WorldHeight(0, 128), generator(), 42L
        );
        LocalWorldRuntime runtime = new LocalWorldRuntime(
                session, generator(), 42L, 0.5, 64.0, 0.5, 0.0f, 1
        );
        try (runtime) {
            runtime.start();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (runtime.loadedChunks() < 9 && runtime.ticks() < 100) {
                    Thread.sleep(10);
                }
            });
            assertTrue(runtime.ticks() > 0);
            assertEquals(9, runtime.loadedChunks());
            assertTrue(runtime.playerState().onGround());
        }
        assertTrue(storage.load("runtime").isPresent());
    }

    @Test
    void acceptsMovementInputFromClientThread() throws Exception {
        ChunkGenerator generator = generator();
        LocalWorldSession session = LocalWorldSession.open(
                new FileWorldStorage(temporaryDirectory),
                "input", new WorldHeight(0, 128), generator, 1L
        );
        LocalWorldRuntime runtime = new LocalWorldRuntime(
                session, generator, 1L, 0.5, 64.0, 0.5, 0.0f, 1
        );
        try (runtime) {
            runtime.start();
            runtime.submitInput(new LocalPlayerController.Input(1, 0, false, false, 0));
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (runtime.ticks() < 30) Thread.sleep(5);
            });
            assertTrue(runtime.playerState().z() > 0.5);
        }
    }
}
