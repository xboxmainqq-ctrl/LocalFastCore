package ru.nexora.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FileClusterReplayFilterTest {
    @TempDir Path directory;

    @Test
    void rejectsReplayAcrossFilterInstancesAndProcessStyleRestart() throws Exception {
        UUID id = UUID.randomUUID();
        FileClusterReplayFilter first = new FileClusterReplayFilter(directory, 10, 1_000);
        FileClusterReplayFilter restarted = new FileClusterReplayFilter(directory, 10, 1_000);

        assertTrue(first.accept(id, 100));
        assertFalse(restarted.accept(id, 200));
        assertTrue(restarted.accept(id, 1_100));
    }

    @Test
    void failsClosedAtCapacityAndReclaimsExpiredMarkers() throws Exception {
        FileClusterReplayFilter filter = new FileClusterReplayFilter(directory, 1, 100);
        assertTrue(filter.accept(UUID.randomUUID(), 100));
        assertFalse(filter.accept(UUID.randomUUID(), 150));
        assertTrue(filter.accept(UUID.randomUUID(), 200));
    }

    @Test
    void concurrentInstancesAcceptSameMessageOnlyOnce() throws Exception {
        UUID id = UUID.randomUUID();
        FileClusterReplayFilter first = new FileClusterReplayFilter(directory, 10, 1_000);
        FileClusterReplayFilter second = new FileClusterReplayFilter(directory, 10, 1_000);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> first.accept(id, 100));
            var b = pool.submit(() -> second.accept(id, 100));
            assertNotEquals(a.get(), b.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsCorruptMarkerInsteadOfAcceptingPotentialReplay() throws Exception {
        UUID id = UUID.randomUUID();
        assertTrue(new FileClusterReplayFilter(directory, 10, 1_000).accept(id, 100));
        Files.write(directory.resolve(id + ".nxrd"), new byte[] {1, 2, 3});
        assertThrows(java.io.IOException.class,
                () -> new FileClusterReplayFilter(directory, 10, 1_000).accept(id, 200));
    }

    @Test
    void rejectsSymlinkedReplayDirectory() throws Exception {
        Path target = Files.createDirectory(directory.resolve("target"));
        Path link = directory.resolve("linked");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            return;
        }
        assertThrows(java.io.IOException.class,
                () -> new FileClusterReplayFilter(link, 10, 1_000).accept(UUID.randomUUID(), 100));
    }
}
