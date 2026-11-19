package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkStreamingLoadStressTest {
    private static final int SESSION_COUNT = 128;
    private static final int CHUNK_BUDGET_PER_SESSION_TICK = 4;
    private static final int VIEW_RADIUS = 8;

    @Test
    void keepsLargeSimultaneousChunkViewsWithinPerSessionAndAggregateTickBudgets() {
        List<ChunkStreamingQueue> sessions = new ArrayList<>(SESSION_COUNT);
        for (int session = 0; session < SESSION_COUNT; session++) {
            sessions.add(new ChunkStreamingQueue(session * 32, -session * 17, VIEW_RADIUS));
        }

        int serverTicks = 0;
        long totalPublished = 0L;
        int maximumAggregatePerTick = 0;
        while (sessions.stream().anyMatch(queue -> !queue.isComplete())) {
            int aggregateThisTick = 0;
            for (ChunkStreamingQueue queue : sessions) {
                if (queue.isComplete()) continue;
                List<Long> batch = queue.nextBatch(CHUNK_BUDGET_PER_SESSION_TICK);
                assertTrue(batch.size() <= CHUNK_BUDGET_PER_SESSION_TICK);
                assertFalse(batch.isEmpty());
                aggregateThisTick += batch.size();
                totalPublished += batch.size();
            }
            maximumAggregatePerTick = Math.max(maximumAggregatePerTick, aggregateThisTick);
            assertTrue(aggregateThisTick <= SESSION_COUNT * CHUNK_BUDGET_PER_SESSION_TICK);
            serverTicks++;
        }

        int chunksPerSession = (VIEW_RADIUS * 2 + 1) * (VIEW_RADIUS * 2 + 1);
        assertEquals((long) SESSION_COUNT * chunksPerSession, totalPublished);
        assertEquals((chunksPerSession + CHUNK_BUDGET_PER_SESSION_TICK - 1)
                / CHUNK_BUDGET_PER_SESSION_TICK, serverTicks);
        assertEquals(SESSION_COUNT * CHUNK_BUDGET_PER_SESSION_TICK, maximumAggregatePerTick);
        assertTrue(sessions.stream().allMatch(ChunkStreamingQueue::isComplete));
    }

    @Test
    void aSingleSlowSessionDoesNotConsumeAnotherSessionsChunkBudget() {
        ChunkStreamingQueue slow = new ChunkStreamingQueue(0, 0, VIEW_RADIUS);
        ChunkStreamingQueue fast = new ChunkStreamingQueue(100, 100, 1);

        List<Long> fastView = fast.nextBatch(CHUNK_BUDGET_PER_SESSION_TICK);
        List<Long> slowBatch = slow.nextBatch(CHUNK_BUDGET_PER_SESSION_TICK);

        assertEquals(4, fastView.size());
        assertEquals(4, slowBatch.size());
        assertEquals(5, fast.remaining());
        assertEquals(285, slow.remaining());
    }
}
