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
    void supportsBedrockXuidWithoutNumericConversion() throws IOException {
        FilePlayerStateStore store = new FilePlayerStateStore(temporaryDirectory);
        String xuid = "xuid-A_123";
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(1, xuid, 1L, 10L, new byte[] {9, 8});

        assertEquals(PlayerStateStore.SaveResult.SAVED, store.save(snapshot));
        assertEquals(xuid, store.loadLatest(xuid).orElseThrow().playerId());
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
    void restoresLastVerifiedGenerationWhenPrimarySnapshotIsCorrupted() throws IOException {
        FilePlayerStateStore store = new FilePlayerStateStore(temporaryDirectory);
        PlayerStateSnapshot first = new PlayerStateSnapshot(1, "18", 1L, 10L, new byte[] {1, 2, 3});
        PlayerStateSnapshot second = new PlayerStateSnapshot(1, "18", 2L, 20L, new byte[] {4, 5, 6});
        assertEquals(PlayerStateStore.SaveResult.SAVED, store.save(first));
        assertEquals(PlayerStateStore.SaveResult.SAVED, store.save(second));

        Path primary = temporaryDirectory.resolve("18.nxps");
        byte[] corrupted = Files.readAllBytes(primary);
        corrupted[corrupted.length - 1] ^= 0x40;
        Files.write(primary, corrupted);

        PlayerStateSnapshot recovered = new FilePlayerStateStore(temporaryDirectory)
                .loadLatest("18").orElseThrow();
        assertEquals(1L, recovered.revision());
        assertArrayEquals(new byte[] {1, 2, 3}, recovered.payload());

        // Recovery rewrites the primary, so the next higher revision can be committed.
        assertEquals(PlayerStateStore.SaveResult.SAVED,
                store.save(new PlayerStateSnapshot(1, "18", 2L, 30L, new byte[] {7, 8, 9})));
        assertEquals(2L, store.loadLatest("18").orElseThrow().revision());
    }

    @Test
    void rejectsConflictingPayloadForSameRevision() throws IOException {
        FilePlayerStateStore store = new FilePlayerStateStore(temporaryDirectory);
        store.save(new PlayerStateSnapshot(1, "15", 2L, 20L, new byte[] {1}));
        assertThrows(IllegalStateException.class,
                () -> store.save(new PlayerStateSnapshot(1, "15", 2L, 21L, new byte[] {2})));
    }
}
