package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorldChunkPipelineTest {
    @Test
    void commitsGeneratedChunksOnlyOnScheduledGameTicks() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 64));
        TickScheduler scheduler = new TickScheduler();
        AsyncChunkGenerationService generation = new AsyncChunkGenerationService(
                generator(), world.height(), 123L, 1, 4, 4
        );
        WorldChunkPipeline pipeline = new WorldChunkPipeline(
                world, scheduler, generation, 2, failure -> fail(failure)
        );
        pipeline.start();
        try {
            ChunkPosition position = new ChunkPosition(-1, 2);
            assertEquals(WorldChunkPipeline.RequestStatus.ACCEPTED, pipeline.request(position));
            await(() -> pipeline.completedCount() == 1);

            assertFalse(world.isChunkLoaded(position),
                    "worker completion must not mutate the world before the game tick");
            scheduler.tick();

            assertTrue(world.isChunkLoaded(position));
            assertEquals(1L, pipeline.loadedChunkCount());
            assertEquals(WorldChunkPipeline.RequestStatus.ALREADY_LOADED, pipeline.request(position));
        } finally {
            pipeline.stop();
        }
    }

    @Test
    void appliesOnlyConfiguredNumberOfChunksPerTick() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 64));
        TickScheduler scheduler = new TickScheduler();
        AsyncChunkGenerationService generation = new AsyncChunkGenerationService(
                generator(), world.height(), 0L, 2, 4, 4
        );
        WorldChunkPipeline pipeline = new WorldChunkPipeline(
                world, scheduler, generation, 1, failure -> fail(failure)
        );
        pipeline.start();
        try {
            ChunkPosition first = new ChunkPosition(0, 0);
            ChunkPosition second = new ChunkPosition(1, 0);
            assertEquals(WorldChunkPipeline.RequestStatus.ACCEPTED, pipeline.request(first));
            assertEquals(WorldChunkPipeline.RequestStatus.ACCEPTED, pipeline.request(second));
            await(() -> pipeline.completedCount() == 2);

            scheduler.tick();
            assertEquals(1, world.loadedChunkCount());
            assertEquals(1, pipeline.completedCount());

            scheduler.tick();
            assertEquals(2, world.loadedChunkCount());
            assertEquals(0, pipeline.completedCount());
            assertEquals(2L, pipeline.loadedChunkCount());
        } finally {
            pipeline.stop();
        }
    }

    @Test
    void asyncChunkRequestCompletesOnlyAfterGameTickCommitAndDeduplicatesWaiters() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 64));
        TickScheduler scheduler = new TickScheduler();
        AsyncChunkGenerationService generation = new AsyncChunkGenerationService(
                generator(), world.height(), 123L, 1, 4, 4
        );
        WorldChunkPipeline pipeline = new WorldChunkPipeline(
                world, scheduler, generation, 2, failure -> fail(failure)
        );
        pipeline.start();
        try {
            ChunkPosition position = new ChunkPosition(2, -3);
            java.util.concurrent.CompletableFuture<Boolean> first = pipeline.requestAsync(position);
            java.util.concurrent.CompletableFuture<Boolean> second = pipeline.requestAsync(position);
            assertSame(first, second);
            await(() -> pipeline.completedCount() == 1);
            assertFalse(first.isDone(), "worker completion must wait for the game-thread commit");

            scheduler.tick();
            assertTrue(first.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(world.isChunkLoaded(position));
            assertTrue(pipeline.requestAsync(position).get(1, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            pipeline.stop();
        }
    }

    @Test
    void asyncChunkRequestCompletesFalseWhenCancelledOrStopped() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 64));
        TickScheduler scheduler = new TickScheduler();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        ChunkGenerator blocked = (position, height, seed) -> {
            started.countDown();
            try {
                release.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", interrupted);
            }
            return generator().generate(position, height, seed);
        };
        AsyncChunkGenerationService generation = new AsyncChunkGenerationService(
                blocked, world.height(), 0L, 1, 2, 2
        );
        WorldChunkPipeline pipeline = new WorldChunkPipeline(
                world, scheduler, generation, 1, failure -> fail(failure)
        );
        pipeline.start();
        try {
            ChunkPosition position = new ChunkPosition(0, 0);
            java.util.concurrent.CompletableFuture<Boolean> future = pipeline.requestAsync(position);
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(pipeline.cancel(position));
            assertFalse(future.get(1, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pipeline.stop();
        }
    }

    @Test
    void reportsGenerationFailuresWithoutStoppingThePipeline() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 64));
        TickScheduler scheduler = new TickScheduler();
        AtomicInteger failures = new AtomicInteger();
        AsyncChunkGenerationService generation = new AsyncChunkGenerationService(
                (position, height, seed) -> { throw new IllegalArgumentException("invalid terrain"); },
                world.height(), 0L, 1, 2, 2
        );
        WorldChunkPipeline pipeline = new WorldChunkPipeline(
                world, scheduler, generation, 1, failure -> {
                    assertEquals("invalid terrain", failure.getMessage());
                    failures.incrementAndGet();
                }
        );
        pipeline.start();
        try {
            assertEquals(WorldChunkPipeline.RequestStatus.ACCEPTED,
                    pipeline.request(new ChunkPosition(3, 3)));
            await(() -> pipeline.completedCount() == 1);
            scheduler.tick();

            assertEquals(1, failures.get());
            assertEquals(0, world.loadedChunkCount());
            assertEquals(WorldChunkPipeline.RequestStatus.ACCEPTED,
                    pipeline.request(new ChunkPosition(3, 3)));
        } finally {
            pipeline.stop();
        }
    }

    @Test
    void completesAsyncWaiterWhenFailureHandlerThrows() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 64));
        TickScheduler scheduler = new TickScheduler();
        AsyncChunkGenerationService generation = new AsyncChunkGenerationService(
                (position, height, seed) -> { throw new IllegalStateException("generation failed"); },
                world.height(), 1L, 1, 2, 2
        );
        WorldChunkPipeline pipeline = new WorldChunkPipeline(
                world, scheduler, generation, 2,
                failure -> { throw new IllegalStateException("failure reporter failed"); }
        );
        pipeline.start();
        try {
            java.util.concurrent.CompletableFuture<Boolean> waiter =
                    pipeline.requestAsync(new ChunkPosition(2, -3));
            await(() -> generation.completedCount() == 1);

            assertThrows(IllegalStateException.class, scheduler::tick);
            assertFalse(waiter.isCompletedExceptionally());
            assertFalse(waiter.join(), "failed generation must resolve the waiter as unavailable");
            assertEquals(0, pipeline.pendingCount());
        } finally {
            pipeline.stop();
        }
    }

    @Test
    void completesAsyncWaiterWhenGeneratorThrowsFatalError() throws Exception {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 64));
        TickScheduler scheduler = new TickScheduler();
        AsyncChunkGenerationService generation = new AsyncChunkGenerationService(
                (position, height, seed) -> { throw new AssertionError("fatal generator failure"); },
                world.height(), 1L, 1, 2, 2
        );
        java.util.concurrent.atomic.AtomicReference<RuntimeException> reported =
                new java.util.concurrent.atomic.AtomicReference<>();
        WorldChunkPipeline pipeline = new WorldChunkPipeline(
                world, scheduler, generation, 2, reported::set
        );
        pipeline.start();
        try {
            java.util.concurrent.CompletableFuture<Boolean> waiter =
                    pipeline.requestAsync(new ChunkPosition(-4, 7));
            await(() -> generation.completedCount() == 1);

            scheduler.tick();
            assertFalse(waiter.isCompletedExceptionally());
            assertFalse(waiter.join(), "fatal generation failure must resolve as unavailable");
            assertNotNull(reported.get());
            assertInstanceOf(AssertionError.class, reported.get().getCause());
            assertEquals(0, pipeline.pendingCount());
        } finally {
            pipeline.stop();
        }
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
            while (!check.ready()) {
                Thread.sleep(2L);
            }
        });
    }

    @FunctionalInterface
    private interface Check {
        boolean ready();
    }
}
