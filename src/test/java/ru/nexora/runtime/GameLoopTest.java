package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GameLoopTest {
    @Test
    void advancesSchedulerAndStopsCleanly() throws Exception {
        TickScheduler scheduler = new TickScheduler();
        CountDownLatch reached = new CountDownLatch(3);
        scheduler.scheduleRepeating(1, 1, reached::countDown);
        GameLoop loop = new GameLoop(100, 3, scheduler);

        loop.start();
        assertTrue(reached.await(2, TimeUnit.SECONDS));
        loop.stop();

        long stoppedAt = loop.executedTicks();
        assertFalse(loop.isRunning());
        Thread.sleep(40);
        assertEquals(stoppedAt, loop.executedTicks());
        assertDoesNotThrow(loop::stop);
    }

    @Test
    void rejectsInvalidConfigurationAndDuplicateStart() throws Exception {
        TickScheduler scheduler = new TickScheduler();
        assertThrows(IllegalArgumentException.class, () -> new GameLoop(0, 1, scheduler));
        assertThrows(IllegalArgumentException.class, () -> new GameLoop(20, 0, scheduler));
        assertThrows(NullPointerException.class, () -> new GameLoop(20, 1, null));

        GameLoop loop = new GameLoop(20, 2, scheduler);
        loop.start();
        assertThrows(IllegalStateException.class, loop::start);
        loop.stop();
    }

    @Test
    void reportsTaskFailuresAndKeepsTheLoopAlive() throws Exception {
        TickScheduler scheduler = new TickScheduler();
        CountDownLatch failureReported = new CountDownLatch(1);
        AtomicInteger laterTaskRuns = new AtomicInteger();
        scheduler.schedule(1, () -> {
            throw new IllegalStateException("scheduled failure");
        });
        scheduler.scheduleRepeating(1, 1, laterTaskRuns::incrementAndGet);

        GameLoop loop = new GameLoop(100, 2, scheduler, failure -> failureReported.countDown());
        loop.start();

        assertTrue(failureReported.await(2, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (laterTaskRuns.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        loop.stop();

        assertTrue(laterTaskRuns.get() >= 2);
        assertEquals("scheduled failure", loop.lastFailure().getMessage());
        assertTrue(loop.executedTicks() >= 2);
    }

    @Test
    void canBeStoppedFromOneOfItsOwnScheduledTasks() throws Exception {
        TickScheduler scheduler = new TickScheduler();
        java.util.concurrent.atomic.AtomicReference<GameLoop> loopRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch stopRequested = new CountDownLatch(1);
        GameLoop loop = new GameLoop(100, 2, scheduler);
        loopRef.set(loop);
        scheduler.schedule(1, () -> {
            try {
                loopRef.get().stop();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            stopRequested.countDown();
        });

        loop.start();
        assertTrue(stopRequested.await(2, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (loop.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        assertFalse(loop.isRunning());
        assertDoesNotThrow(loop::stop);
    }

    @Test
    void exposesStableModuleIdentityAndCounters() throws Exception {
        GameLoop loop = new GameLoop(60, 4, new TickScheduler());
        assertEquals("game-loop", loop.id());
        assertEquals(0, loop.executedTicks());
        assertEquals(0, loop.droppedBacklogs());
        loop.start();
        Thread.sleep(80);
        loop.stop();
        assertTrue(loop.executedTicks() >= 1);
        TickMetrics.Snapshot metrics = loop.metrics();
        assertEquals(loop.executedTicks(), metrics.tickCount());
        assertTrue(metrics.totalDurationNanos() >= metrics.lastDurationNanos());
        assertTrue(metrics.maxDurationNanos() >= metrics.lastDurationNanos());
        assertTrue(metrics.overBudgetTicks() <= metrics.tickCount());
    }
}
