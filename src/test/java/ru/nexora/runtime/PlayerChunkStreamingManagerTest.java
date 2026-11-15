package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerChunkStreamingManagerTest {
    @Test
    void requestsNearestChunksWithinPerTickBudgetAndDoesNotStarveBehindPendingWork() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 64));
        TickScheduler scheduler = new TickScheduler();
        AsyncChunkGenerationService generation = new AsyncChunkGenerationService(
                generator(), world.height(), 19L, 1, 16, 16
        );
        WorldChunkPipeline pipeline = new WorldChunkPipeline(
                world, scheduler, generation, 4, failure -> fail(failure)
        );
        PlayerChunkStreamingManager streaming =
                new PlayerChunkStreamingManager(world, pipeline, 1, 1);
        pipeline.start();
        try {
            streaming.updatePlayer(UUID.randomUUID(), 0, 0);
            assertEquals(1, streaming.tick());
            await(() -> generation.metrics().acceptedRequests() == 1);
            assertEquals(1L, generation.metrics().acceptedRequests());

            // The first chunk remains pending, but it must not consume the
            // next tick's scheduling budget or prevent adjacent chunks loading.
            assertEquals(1, streaming.tick());
            assertEquals(2L, generation.metrics().acceptedRequests());
            assertEquals(5, streaming.ticketedChunkCount());
        } finally {
            pipeline.stop();
        }
    }

    @Test
    void sharedTicketsPreventOnePlayerFromCancellingAnotherPlayersChunk() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 64));
        TickScheduler scheduler = new TickScheduler();
        AsyncChunkGenerationService generation = new AsyncChunkGenerationService(
                generator(), world.height(), 7L, 1, 4, 4
        );
        WorldChunkPipeline pipeline = new WorldChunkPipeline(
                world, scheduler, generation, 2, failure -> fail(failure)
        );
        PlayerChunkStreamingManager streaming =
                new PlayerChunkStreamingManager(world, pipeline, 0, 2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ChunkPosition shared = new ChunkPosition(0, 0);
        pipeline.start();
        try {
            streaming.updatePlayer(first, 0, 0);
            streaming.updatePlayer(second, 0, 0);
            assertEquals(2, streaming.ticketCount(shared));
            assertEquals(1, streaming.tick());
            assertTrue(pipeline.isPending(shared));

            streaming.removePlayer(first);
            assertEquals(1, streaming.ticketCount(shared));
            assertTrue(pipeline.isPending(shared), "the remaining player's ticket must keep the request alive");

            streaming.removePlayer(second);
            assertEquals(0, streaming.ticketCount(shared));
            assertFalse(pipeline.isPending(shared), "the last ticket removal should cancel stale generation");
        } finally {
            pipeline.stop();
        }
    }

    @Test
    void movingPlayerCancelsChunksThatAreNoLongerNeeded() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 64));
        TickScheduler scheduler = new TickScheduler();
        AsyncChunkGenerationService generation = new AsyncChunkGenerationService(
                generator(), world.height(), 3L, 1, 4, 4
        );
        WorldChunkPipeline pipeline = new WorldChunkPipeline(
                world, scheduler, generation, 2, failure -> fail(failure)
        );
        PlayerChunkStreamingManager streaming =
                new PlayerChunkStreamingManager(world, pipeline, 0, 1);
        UUID player = UUID.randomUUID();
        ChunkPosition oldPosition = new ChunkPosition(0, 0);
        ChunkPosition newPosition = new ChunkPosition(1, 0);
        pipeline.start();
        try {
            streaming.updatePlayer(player, 0, 0);
            assertEquals(1, streaming.tick());
            assertTrue(pipeline.isPending(oldPosition));

            streaming.updatePlayer(player, 16, 0);
            assertEquals(0, streaming.ticketCount(oldPosition));
            assertFalse(pipeline.isPending(oldPosition));
            assertEquals(1, streaming.ticketCount(newPosition));
            assertEquals(1, streaming.tick());
            assertTrue(pipeline.isPending(newPosition));
        } finally {
            pipeline.stop();
        }
    }

    @Test
    void rejectsUnsafeConfiguration() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 64));
        TickScheduler scheduler = new TickScheduler();
        AsyncChunkGenerationService generation = new AsyncChunkGenerationService(
                generator(), world.height(), 0L, 1, 2, 2
        );
        WorldChunkPipeline pipeline = new WorldChunkPipeline(
                world, scheduler, generation, 1, failure -> fail(failure)
        );
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerChunkStreamingManager(world, pipeline, -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerChunkStreamingManager(world, pipeline, 33, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerChunkStreamingManager(world, pipeline, 1, 0));
    }

    private static FlatChunkGenerator generator() {
        return new FlatChunkGenerator(
                5, 2,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:dirt"),
                new BlockState("minecraft:bedrock")
        );
    }

    private static void await(Check check) throws InterruptedException {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            while (!check.ready()) Thread.sleep(2L);
        });
    }

    @FunctionalInterface
    private interface Check {
        boolean ready();
    }
}
