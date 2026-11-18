package ru.nexora.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeTaskSchedulerTest {
    @Test
    void boundsRegistrationsAndReleasesCapacityOnCancellation() throws Exception {
        RuntimeTaskScheduler scheduler = new RuntimeTaskScheduler(1, Duration.ofSeconds(1));
        RuntimeTaskScheduler.TaskHandle first = scheduler.scheduleWithFixedDelay(
                RuntimeTaskScheduler.Lane.TICK, () -> {}, 1, 1, TimeUnit.DAYS);

        assertEquals(1, scheduler.registeredTasks(RuntimeTaskScheduler.Lane.TICK));
        assertThrows(IllegalStateException.class, () -> scheduler.scheduleWithFixedDelay(
                RuntimeTaskScheduler.Lane.TICK, () -> {}, 1, 1, TimeUnit.DAYS));

        assertTrue(first.cancel());
        assertEquals(0, scheduler.registeredTasks(RuntimeTaskScheduler.Lane.TICK));
        RuntimeTaskScheduler.TaskHandle second = scheduler.scheduleWithFixedDelay(
                RuntimeTaskScheduler.Lane.TICK, () -> {}, 1, 1, TimeUnit.DAYS);
        second.close();
        scheduler.close();
    }

    @Test
    void isolatesTickLaneFromBlockedPersistenceLane() throws Exception {
        RuntimeTaskScheduler scheduler = new RuntimeTaskScheduler(2, Duration.ofSeconds(1));
        CountDownLatch persistenceStarted = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);
        CountDownLatch tickRan = new CountDownLatch(1);

        scheduler.scheduleWithFixedDelay(RuntimeTaskScheduler.Lane.PERSISTENCE, () -> {
            persistenceStarted.countDown();
            try {
                releasePersistence.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }, 0, 1, TimeUnit.DAYS);
        scheduler.scheduleWithFixedDelay(RuntimeTaskScheduler.Lane.TICK,
                tickRan::countDown, 0, 1, TimeUnit.DAYS);

        assertTrue(persistenceStarted.await(2, TimeUnit.SECONDS));
        assertTrue(tickRan.await(2, TimeUnit.SECONDS));
        releasePersistence.countDown();
        scheduler.close();
    }

    @Test
    void releasesCapacityWhenPeriodicTaskFails() throws Exception {
        RuntimeTaskScheduler scheduler = new RuntimeTaskScheduler(1, Duration.ofSeconds(1));
        CountDownLatch failedTaskRan = new CountDownLatch(1);
        scheduler.scheduleAtFixedRate(RuntimeTaskScheduler.Lane.TICK, () -> {
            failedTaskRan.countDown();
            throw new IllegalStateException("tick failed");
        }, 0, 1, TimeUnit.MILLISECONDS);

        assertTrue(failedTaskRan.await(2, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        RuntimeTaskScheduler.LaneStats stats;
        do {
            stats = scheduler.stats(RuntimeTaskScheduler.Lane.TICK);
            if (scheduler.registeredTasks(RuntimeTaskScheduler.Lane.TICK) == 0
                    && stats.failedRuns() == 1) break;
            Thread.yield();
        } while (System.nanoTime() < deadline);
        assertEquals(0, scheduler.registeredTasks(RuntimeTaskScheduler.Lane.TICK));
        stats = scheduler.stats(RuntimeTaskScheduler.Lane.TICK);
        assertEquals(1, stats.completedRuns());
        assertEquals(1, stats.failedRuns());
        assertTrue(stats.totalExecutionNanos() >= 0);
        assertTrue(stats.maxExecutionNanos() >= stats.lastExecutionNanos());
        scheduler.close();
    }

    @Test
    void recordsExecutionBaselinesPerLaneWithoutMixingCounters() throws Exception {
        RuntimeTaskScheduler scheduler = new RuntimeTaskScheduler(2, Duration.ofSeconds(1));
        CountDownLatch tickRan = new CountDownLatch(3);
        CountDownLatch persistenceRan = new CountDownLatch(2);

        scheduler.scheduleAtFixedRate(RuntimeTaskScheduler.Lane.TICK,
                tickRan::countDown, 0, 1, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(RuntimeTaskScheduler.Lane.PERSISTENCE,
                persistenceRan::countDown, 0, 1, TimeUnit.MILLISECONDS);

        assertTrue(tickRan.await(2, TimeUnit.SECONDS));
        assertTrue(persistenceRan.await(2, TimeUnit.SECONDS));
        long metricsDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        RuntimeTaskScheduler.LaneStats tick;
        RuntimeTaskScheduler.LaneStats persistence;
        do {
            tick = scheduler.stats(RuntimeTaskScheduler.Lane.TICK);
            persistence = scheduler.stats(RuntimeTaskScheduler.Lane.PERSISTENCE);
            if (tick.completedRuns() >= 3 && persistence.completedRuns() >= 2) break;
            Thread.yield();
        } while (System.nanoTime() < metricsDeadline);

        assertTrue(tick.completedRuns() >= 3);
        assertTrue(persistence.completedRuns() >= 2);
        assertEquals(0, tick.failedRuns());
        assertEquals(0, persistence.failedRuns());
        assertTrue(tick.totalExecutionNanos() >= tick.maxExecutionNanos());
        assertTrue(tick.averageExecutionMillis() >= 0);
        assertTrue(tick.maxExecutionMillis() >= 0);
        scheduler.close();
    }

    @Test
    void closeStopsTasksAndRejectsFurtherScheduling() throws Exception {
        RuntimeTaskScheduler scheduler = new RuntimeTaskScheduler();
        AtomicInteger calls = new AtomicInteger();
        scheduler.scheduleAtFixedRate(RuntimeTaskScheduler.Lane.MONITORING,
                calls::incrementAndGet, 0, 5, TimeUnit.MILLISECONDS);
        Thread.sleep(30);
        scheduler.close();
        int stoppedAt = calls.get();
        Thread.sleep(20);

        assertEquals(stoppedAt, calls.get());
        assertThrows(IllegalStateException.class, () -> scheduler.scheduleAtFixedRate(
                RuntimeTaskScheduler.Lane.MONITORING, () -> {}, 0, 1, TimeUnit.SECONDS));
    }
}
