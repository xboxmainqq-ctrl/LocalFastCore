package ru.nexora.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FilePlayerStateStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsSnapshotsAcrossStoreInstances() throws IOException {
        FilePlayerStateStore first = new FilePlayerStateStore(temporaryDirectory);
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(2, "81", 4L, 1234L,
                new byte[] {4, 3, 2, 1});
        assertEquals(PlayerStateStore.SaveResult.SAVED, first.save(snapshot));

        FilePlayerStateStore afterRestart = new FilePlayerStateStore(temporaryDirectory);
        PlayerStateSnapshot loaded = afterRestart.loadLatest("81").orElseThrow();
        assertEquals(2, loaded.schemaVersion());
        assertEquals(4L, loaded.revision());
        assertEquals(1234L, loaded.capturedAtEpochMillis());
        assertArrayEquals(new byte[] {4, 3, 2, 1}, loaded.payload());
    }

    @Test
    void keepsRevisionMonotonicAndSaveIdempotent() throws IOException {
        FilePlayerStateStore store = new FilePlayerStateStore(temporaryDirectory);
        PlayerStateSnapshot current = new PlayerStateSnapshot(1, "9", 8L, 80L, new byte[] {8});
        assertEquals(PlayerStateStore.SaveResult.SAVED, store.save(current));
        assertEquals(PlayerStateStore.SaveResult.STALE,
                store.save(new PlayerStateSnapshot(1, "9", 7L, 70L, new byte[] {7})));
        assertEquals(PlayerStateStore.SaveResult.ALREADY_CURRENT,
                store.save(new PlayerStateSnapshot(1, "9", 8L, 99L, new byte[] {8})));
        assertEquals(8L, store.loadLatest("9").orElseThrow().revision());
    }

    @Test
    void detectsCorruptedSnapshot() throws IOException {
        FilePlayerStateStore store = new FilePlayerStateStore(temporaryDirectory);
        store.save(new PlayerStateSnapshot(1, "12", 1L, 10L, new byte[] {1, 2, 3}));
        Path file = temporaryDirectory.resolve("12.nxps");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> store.loadLatest("12"));
    }

    @Test
    void rejectsConflictingPayloadForSameRevision() throws IOException {
        FilePlayerStateStore store = new FilePlayerStateStore(temporaryDirectory);
        store.save(new PlayerStateSnapshot(1, "15", 2L, 20L, new byte[] {1}));
        assertThrows(IllegalStateException.class,
                () -> store.save(new PlayerStateSnapshot(1, "15", 2L, 21L, new byte[] {2})));
    }
}
