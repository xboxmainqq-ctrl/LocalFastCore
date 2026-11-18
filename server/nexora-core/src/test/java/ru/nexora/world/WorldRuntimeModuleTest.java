package ru.nexora.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorldRuntimeModuleTest {
    @Test
    void closesOwnedServicesAndPerformsFinalWorldAndPlayerSave() throws Exception {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        AtomicInteger playerSaves = new AtomicInteger();
        WorldRuntimeModule runtime = new WorldRuntimeModule(
                world,
                Duration.ofHours(1),
                ignored -> fail(ignored),
                playerSaves::incrementAndGet,
                ignored -> {},
                ignored -> fail(ignored),
                ignored -> {});

        assertEquals(1, runtime.registeredTickTasks());
        assertEquals(1, runtime.registeredPersistenceTasks());
        assertEquals(1, runtime.tickStats().registeredTasks());
        assertEquals(1, runtime.persistenceStats().registeredTasks());
        assertEquals(0, runtime.persistenceStats().failedRuns());

        runtime.close();
        runtime.close();

        assertTrue(runtime.isClosed());
        assertEquals(1, playerSaves.get());
        assertThrows(IllegalStateException.class, runtime::saveNow);
    }

    @Test
    void reportsFinalPlayerSaveFailure() throws Exception {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        AtomicInteger playerSaves = new AtomicInteger();
        WorldRuntimeModule runtime = new WorldRuntimeModule(
                world,
                Duration.ofHours(1),
                ignored -> {},
                () -> {
                    playerSaves.incrementAndGet();
                    throw new IOException("player persistence failed");
                },
                ignored -> {},
                ignored -> {},
                ignored -> {});

        IOException failure = assertThrows(IOException.class, runtime::close);

        assertTrue(runtime.isClosed());
        assertEquals(1, playerSaves.get());
        assertTrue(failure.getMessage().contains("player persistence failed"));
    }

    @Test
    void rejectsMissingDependencies() {
        assertThrows(NullPointerException.class, () -> new WorldRuntimeModule(
                null, Duration.ofHours(1), ignored -> {}, () -> {}, ignored -> {},
                ignored -> {}, ignored -> {}));
    }
}
