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
