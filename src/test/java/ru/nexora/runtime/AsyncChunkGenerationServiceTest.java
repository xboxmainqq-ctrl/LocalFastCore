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
            assertEquals(1L, service.metrics().acceptedRequests());
            assertEquals(0L, service.metrics().deduplicatedRequests());
            assertEquals(0, service.metrics().pendingCount());
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
            assertEquals(1L, service.metrics().acceptedRequests());
            assertEquals(1L, service.metrics().deduplicatedRequests());
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
            assertEquals(2L, service.metrics().acceptedRequests());
            assertEquals(1L, service.metrics().rejectedRequests());
            assertTrue(service.cancel(second));
            assertEquals(AsyncChunkGenerationService.RequestStatus.ACCEPTED, service.request(third));
        } finally {
            release.countDown();
            service.stop();
        }
    }

    @Test
    void appliesBackpressureInsteadOfDroppingWhenCompletionQueueIsFull() throws Exception {
        AtomicInteger generated = new AtomicInteger();
        AsyncChunkGenerationService service = new AsyncChunkGenerationService(
                (position, height, seed) -> {
                    generated.incrementAndGet();
                    return generator().generate(position, height, seed);
                },
                new WorldHeight(0, 64), 0L, 1, 4, 1
        );
        service.start();
        try {
            ChunkPosition first = new ChunkPosition(0, 0);
            ChunkPosition second = new ChunkPosition(1, 0);
            assertEquals(AsyncChunkGenerationService.RequestStatus.ACCEPTED, service.request(first));
            await(() -> service.completedCount() == 1);

            assertEquals(AsyncChunkGenerationService.RequestStatus.ACCEPTED, service.request(second));
            await(() -> generated.get() == 2);
            // The second worker result must wait for queue capacity rather than
            // being discarded while the first result occupies the only slot.
            assertEquals(0L, service.droppedResults());
            assertEquals(2, service.pendingCount());
            assertEquals(1, service.completedCount());

            assertEquals(1, service.drainCompleted(1, ignored -> { }));
            await(() -> service.completedCount() == 1);
            assertEquals(1, service.drainCompleted(1, ignored -> { }));
            assertEquals(0, service.pendingCount());
            assertEquals(0L, service.droppedResults());
        } finally {
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
            assertEquals(1L, service.metrics().failedCount());
        } finally {
            service.stop();
        }
    }

    @Test
    void releasesPendingReservationWhenGeneratorThrowsError() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AsyncChunkGenerationService service = new AsyncChunkGenerationService(
                (position, height, seed) -> {
                    attempts.incrementAndGet();
                    throw new AssertionError("fatal generator failure");
                },
                new WorldHeight(0, 64), 0L, 1, 2, 2
        );
        service.start();
        try {
            ChunkPosition position = new ChunkPosition(7, -3);
            assertEquals(AsyncChunkGenerationService.RequestStatus.ACCEPTED, service.request(position));
            await(() -> service.pendingCount() == 0);

            // The failed task must not poison the in-flight map.
            assertEquals(AsyncChunkGenerationService.RequestStatus.ACCEPTED, service.request(position));
            await(() -> service.pendingCount() == 0);
            assertEquals(2, attempts.get());
            assertEquals(0, service.completedCount());
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
