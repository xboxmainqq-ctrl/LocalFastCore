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
