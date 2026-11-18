package ru.nexora.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerStateStoreTest {
    @Test
    void savesAndLoadsLatestRevisionWithoutExposingMutablePayload() {
        InMemoryPlayerStateStore store = new InMemoryPlayerStateStore();
        byte[] payload = {1, 2, 3};
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(1, "42", 2L, 100L, payload);
        payload[0] = 9;

        assertEquals(PlayerStateStore.SaveResult.SAVED, store.save(snapshot));
        byte[] loaded = store.loadLatest("42").orElseThrow().payload();
        assertArrayEquals(new byte[] {1, 2, 3}, loaded);
        loaded[1] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, store.loadLatest("42").orElseThrow().payload());
    }

    @Test
    void rejectsStaleAndTreatsIdenticalRevisionAsIdempotent() {
        InMemoryPlayerStateStore store = new InMemoryPlayerStateStore();
        PlayerStateSnapshot latest = new PlayerStateSnapshot(1, "7", 5L, 500L, new byte[] {5});
        assertEquals(PlayerStateStore.SaveResult.SAVED, store.save(latest));
        assertEquals(PlayerStateStore.SaveResult.STALE,
                store.save(new PlayerStateSnapshot(1, "7", 4L, 400L, new byte[] {4})));
        assertEquals(PlayerStateStore.SaveResult.ALREADY_CURRENT,
                store.save(new PlayerStateSnapshot(1, "7", 5L, 999L, new byte[] {5})));
        assertEquals(5L, store.loadLatest("7").orElseThrow().revision());
    }

    @Test
    void rejectsConflictingPayloadForSameRevision() {
        InMemoryPlayerStateStore store = new InMemoryPlayerStateStore();
        store.save(new PlayerStateSnapshot(1, "7", 3L, 300L, new byte[] {1}));
        assertThrows(IllegalStateException.class,
                () -> store.save(new PlayerStateSnapshot(1, "7", 3L, 301L, new byte[] {2})));
    }

    @Test
    void validatesSnapshotMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerStateSnapshot(0, "1", 0L, 0L, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerStateSnapshot(1, "", 0L, 0L, new byte[0]));
    }
}
