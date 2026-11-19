package ru.nexora.world;

import org.junit.jupiter.api.Test;
import ru.nexora.runtime.StateOwnership;

import static org.junit.jupiter.api.Assertions.*;

class ChunkStateTest {
    @Test
    void chunkFollowsExplicitLifecycle() {
        StateOwnership ownership = new StateOwnership();
        ChunkState chunk = new ChunkState(new ChunkKey(3, -2), ownership);

        assertEquals(ChunkState.Status.UNLOADED, chunk.status());
        chunk.beginLoading();
        assertEquals(ChunkState.Status.LOADING, chunk.status());
        chunk.activate();
        assertEquals(ChunkState.Status.ACTIVE, chunk.status());
        chunk.beginUnloading();
        assertEquals(ChunkState.Status.UNLOADING, chunk.status());
    }
}
