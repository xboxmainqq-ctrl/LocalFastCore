package ru.nexora.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.ArrayList;
import java.util.List;

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
    void oneBusySessionCannotOccupyAllWorkersAheadOfAnotherSession() throws Exception {
        CountDownLatch firstSessionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSession = new CountDownLatch(1);
        CountDownLatch secondSessionStarted = new CountDownLatch(1);
        AtomicInteger firstSessionReads = new AtomicInteger();
        WorldChunkLoadService service = new WorldChunkLoadService((x, z) -> {
            if (x < 10) {
                firstSessionReads.incrementAndGet();
                if (x == 1) {
                    firstSessionStarted.countDown();
                    try {
                        assertTrue(releaseFirstSession.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", interrupted);
                    }
                }
            } else {
                secondSessionStarted.countDown();
            }
            return Optional.of(new WorldChunk(x, z));
        }, 2, 8);
        try {
            var first = service.request("session-a", 1, 0);
            assertTrue(firstSessionStarted.await(5, TimeUnit.SECONDS));
            var second = service.request("session-a", 2, 0);
            var third = service.request("session-a", 3, 0);
            var otherPlayer = service.request("session-b", 20, 0);

            assertTrue(secondSessionStarted.await(2, TimeUnit.SECONDS),
                    "The second worker must serve another session instead of draining session-a");
            assertEquals(1, firstSessionReads.get(),
                    "Only one storage read from a session may be active at a time");
            assertFalse(second.isDone());
            assertFalse(third.isDone());
            assertTrue(otherPlayer.get(2, TimeUnit.SECONDS).isPresent());

            releaseFirstSession.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isPresent());
            assertTrue(second.get(5, TimeUnit.SECONDS).isPresent());
            assertTrue(third.get(5, TimeUnit.SECONDS).isPresent());
        } finally {
            releaseFirstSession.countDown();
            service.close();
        }
    }

    @Test
    void roundRobinSchedulerHandles128SessionsWithoutPerSessionReadBursts() throws Exception {
        int sessionCount = 128;
        int readsPerSession = 4;
        ConcurrentMap<Integer, AtomicInteger> activeBySession = new ConcurrentHashMap<>();
        ConcurrentMap<Integer, AtomicInteger> peakBySession = new ConcurrentHashMap<>();
        AtomicInteger completedReads = new AtomicInteger();
        WorldChunkLoadService service = new WorldChunkLoadService((x, z) -> {
            int session = Math.floorDiv(x, 10);
            int active = activeBySession.computeIfAbsent(session, ignored -> new AtomicInteger())
                    .incrementAndGet();
            peakBySession.computeIfAbsent(session, ignored -> new AtomicInteger())
                    .accumulateAndGet(active, Math::max);
            try {
                Thread.sleep(1L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            } finally {
                activeBySession.get(session).decrementAndGet();
            }
            completedReads.incrementAndGet();
            return Optional.of(new WorldChunk(x, z));
        }, 2, sessionCount * readsPerSession);
        try {
            List<java.util.concurrent.CompletableFuture<Optional<WorldChunk>>> futures =
                    new ArrayList<>(sessionCount * readsPerSession);
            for (int session = 0; session < sessionCount; session++) {
                for (int offset = 0; offset < readsPerSession; offset++) {
                    futures.add(service.request("player-" + session, session * 10 + offset, 0));
                }
            }
            for (var future : futures) assertTrue(future.get(15, TimeUnit.SECONDS).isPresent());
            assertEquals(sessionCount * readsPerSession, completedReads.get());
            assertEquals(sessionCount, peakBySession.size());
            assertTrue(peakBySession.values().stream().allMatch(peak -> peak.get() == 1),
                    "No player's storage requests may run concurrently with another request from that player");
            assertEquals(0, service.queuedLoads());
        } finally {
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
