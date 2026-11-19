package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkStreamingQueueTest {
    @Test
    void traversesEveryChunkExactlyOnceInRingOrder() {
        ChunkStreamingQueue queue = new ChunkStreamingQueue(10, -4, 2);
        assertEquals(25, queue.remaining());
        List<Long> all = new java.util.ArrayList<>();
        while (!queue.isComplete()) all.addAll(queue.nextBatch(4));
        assertEquals(25, all.size());
        assertEquals(25, new HashSet<>(all).size());
        assertEquals(ChunkStreamingQueue.chunkKey(10, -4), all.get(0));
        assertEquals(ChunkStreamingQueue.chunkKey(9, -5), all.get(1));
        assertEquals(ChunkStreamingQueue.chunkKey(10, -5), all.get(2));
        assertEquals(ChunkStreamingQueue.chunkKey(11, -5), all.get(3));
    }

    @Test
    void neverReturnsMoreThanRequestedBudgetAndCompletesAcrossCalls() {
        ChunkStreamingQueue queue = new ChunkStreamingQueue(0, 0, 3);
        int total = 0;
        while (!queue.isComplete()) {
            List<Long> batch = queue.nextBatch(5);
            assertTrue(batch.size() <= 5);
            assertFalse(batch.isEmpty());
            total += batch.size();
        }
        assertEquals(49, total);
        assertEquals(0, queue.remaining());
        assertTrue(queue.nextBatch(5).isEmpty());
    }

    @Test
    void peekDoesNotConsumeChunksUntilTransportConfirmsAcceptance() {
        ChunkStreamingQueue queue = new ChunkStreamingQueue(2, 3, 1);
        List<Long> first = queue.peekBatch(4);
        assertEquals(first, queue.peekBatch(4));
        assertEquals(9, queue.remaining());
        queue.advance(first.size());
        assertEquals(5, queue.remaining());
        assertNotEquals(first, queue.peekBatch(4));
    }

    @Test
    void radiusZeroContainsOnlyTheCenterChunk() {
        ChunkStreamingQueue queue = new ChunkStreamingQueue(-2, 7, 0);
        assertEquals(List.of(ChunkStreamingQueue.chunkKey(-2, 7)), queue.nextBatch(1));
        assertTrue(queue.isComplete());
    }

    @Test
    void rejectsInvalidRadiusAndBudget() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkStreamingQueue(0, 0, 33));
        assertThrows(IllegalArgumentException.class, () -> new ChunkStreamingQueue(0, 0, -1).nextBatch(0));
    }
}
