package ru.nexora.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorldChunkLoadServiceTest {
    @Test
    void performsStorageReadsOnWorkersAndDeduplicatesInFlightCoordinates() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        WorldChunkLoadService service = new WorldChunkLoadService((x, z) -> {
            reads.incrementAndGet();
            started.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            }
            return Optional.of(new WorldChunk(x, z));
        }, 1, 2);
        try {
            var first = service.request(7, -3);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var duplicate = service.request(7, -3);
            assertSame(first, duplicate, "Concurrent requests for one chunk share a future");
            assertEquals(1, reads.get());
            assertEquals(1, service.activeWorkers());
            release.countDown();
            WorldChunk loaded = first.get(5, TimeUnit.SECONDS).orElseThrow();
            assertEquals(7, loaded.chunkX());
            assertEquals(-3, loaded.chunkZ());
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void boundsWorkerQueueAndReportsBackpressureWithoutRunningUnboundedTasks() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        WorldChunkLoadService service = new WorldChunkLoadService((x, z) -> {
            if (x == 1) {
                started.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
            }
            return Optional.of(new WorldChunk(x, z));
        }, 1, 1);
        try {
            var active = service.request(1, 0);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var queued = service.request(2, 0);
            var rejected = service.request(3, 0);
            assertEquals(1, service.activeWorkers());
            assertEquals(1, service.queuedLoads());
            assertEquals(0, service.remainingQueueCapacity());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> rejected.get(1, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, failure.getCause());
            release.countDown();
            assertTrue(active.get(5, TimeUnit.SECONDS).isPresent());
            assertTrue(queued.get(5, TimeUnit.SECONDS).isPresent());
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void rejectsInvalidWorkerAndQueueBudgets() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorldChunkLoadService((x, z) -> Optional.empty(), 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldChunkLoadService((x, z) -> Optional.empty(), 1, 0));
    }
}
