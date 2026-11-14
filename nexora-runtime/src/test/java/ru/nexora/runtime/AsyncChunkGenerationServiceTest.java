package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AsyncChunkGenerationServiceTest {
    @Test
    void generatesInBackgroundAndDeliversOnlyWhenDrained() throws Exception {
        FlatChunkGenerator generator = generator();
        AsyncChunkGenerationService service = new AsyncChunkGenerationService(
                generator, new WorldHeight(0, 64), 17L, 1, 4, 4
        );
        service.start();
        try {
            ChunkPosition position = new ChunkPosition(-2, 3);
            assertEquals(AsyncChunkGenerationService.RequestStatus.ACCEPTED, service.request(position));
            await(() -> service.completedCount() == 1);

            assertEquals(1, service.pendingCount());
            AtomicInteger delivered = new AtomicInteger();
            assertEquals(1, service.drainCompleted(1, result -> {
                assertTrue(result.succeeded());
                assertEquals(position, result.chunk().position());
                delivered.incrementAndGet();
            }));
            assertEquals(1, delivered.get());
            assertEquals(0, service.pendingCount());
            assertEquals(1L, service.generatedCount());
        } finally {
            service.stop();
        }
    }

    @Test
    void deduplicatesRequestsUntilCompletionIsConsumed() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ChunkGenerator generator = (position, height, seed) -> {
            awaitLatch(release);
            return generator().generate(position, height, seed);
        };
        AsyncChunkGenerationService service = new AsyncChunkGenerationService(
                generator, new WorldHeight(0, 64), 0L, 1, 2, 2
        );
        service.start();
        try {
            ChunkPosition position = new ChunkPosition(0, 0);
            assertEquals(AsyncChunkGenerationService.RequestStatus.ACCEPTED, service.request(position));
            assertEquals(AsyncChunkGenerationService.RequestStatus.ALREADY_PENDING, service.request(position));
            release.countDown();
            await(() -> service.completedCount() == 1);
            assertEquals(AsyncChunkGenerationService.RequestStatus.ALREADY_PENDING, service.request(position));
            service.drainCompleted(1, ignored -> { });
            assertEquals(AsyncChunkGenerationService.RequestStatus.ACCEPTED, service.request(position));
        } finally {
            release.countDown();
            service.stop();
        }
    }

    @Test
    void reportsQueueCapacityAndCanCancelQueuedWork() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        ChunkGenerator generator = (position, height, seed) -> {
            started.countDown();
            awaitLatch(release);
            return generator().generate(position, height, seed);
        };
        AsyncChunkGenerationService service = new AsyncChunkGenerationService(
                generator, new WorldHeight(0, 64), 0L, 1, 1, 2
        );
        service.start();
        try {
            ChunkPosition first = new ChunkPosition(0, 0);
            ChunkPosition second = new ChunkPosition(1, 0);
            ChunkPosition third = new ChunkPosition(2, 0);
            assertEquals(AsyncChunkGenerationService.RequestStatus.ACCEPTED, service.request(first));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(AsyncChunkGenerationService.RequestStatus.ACCEPTED, service.request(second));
            assertEquals(AsyncChunkGenerationService.RequestStatus.QUEUE_FULL, service.request(third));
            assertTrue(service.cancel(second));
            assertEquals(AsyncChunkGenerationService.RequestStatus.ACCEPTED, service.request(third));
        } finally {
            release.countDown();
            service.stop();
        }
    }

    @Test
    void publishesGeneratorFailuresAsResults() throws Exception {
        AsyncChunkGenerationService service = new AsyncChunkGenerationService(
                (position, height, seed) -> { throw new IllegalStateException("bad terrain"); },
                new WorldHeight(0, 64), 0L, 1, 2, 2
        );
        service.start();
        try {
            assertEquals(AsyncChunkGenerationService.RequestStatus.ACCEPTED,
                    service.request(new ChunkPosition(4, 5)));
            await(() -> service.completedCount() == 1);
            assertEquals(1, service.drainCompleted(1, result -> {
                assertFalse(result.succeeded());
                assertEquals("bad terrain", result.failure().getMessage());
            }));
            assertEquals(1L, service.failedCount());
        } finally {
            service.stop();
        }
    }

    @Test
    void rejectsInvalidCapacities() {
        assertThrows(IllegalArgumentException.class, () -> new AsyncChunkGenerationService(
                generator(), new WorldHeight(0, 64), 0L, 0, 1, 1
        ));
        assertThrows(IllegalArgumentException.class, () -> new AsyncChunkGenerationService(
                generator(), new WorldHeight(0, 64), 0L, 1, 0, 1
        ));
        assertThrows(IllegalArgumentException.class, () -> new AsyncChunkGenerationService(
                generator(), new WorldHeight(0, 64), 0L, 1, 1, 0
        ));
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

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Generation interrupted", interrupted);
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean ready();
    }
}
