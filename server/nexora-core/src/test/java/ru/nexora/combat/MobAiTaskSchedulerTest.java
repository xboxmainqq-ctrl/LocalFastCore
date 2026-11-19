package ru.nexora.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class MobAiTaskSchedulerTest {
    @Test
    void highPriorityAndRequiredTasksAlwaysRun() {
        MobAiTaskScheduler scheduler = new MobAiTaskScheduler();
        assertEquals(MobAiTaskScheduler.Admission.RUN,
                scheduler.admit(MobAiTaskScheduler.Task.COMBAT,
                        MobAiLoadController.Priority.LOW, true, 999_999, 1));
        assertEquals(MobAiTaskScheduler.Admission.RUN,
                scheduler.admit(MobAiTaskScheduler.Task.NAVIGATION,
                        MobAiLoadController.Priority.HIGH, false, 999_999, 1));
        assertEquals(MobAiTaskScheduler.Admission.RUN,
                scheduler.admit(MobAiTaskScheduler.Task.COMBAT,
                        MobAiLoadController.Priority.HIGH, false, 0, 0));
    }

    @Test
    void expensiveNormalNavigationCanBeDeferredWhileCheapIdleRuns() {
        MobAiTaskScheduler scheduler = new MobAiTaskScheduler();
        assertEquals(MobAiTaskScheduler.Admission.DEFER,
                scheduler.admit(MobAiTaskScheduler.Task.NAVIGATION,
                        MobAiLoadController.Priority.NORMAL, false, 10, 1));
        assertEquals(MobAiTaskScheduler.Admission.RUN,
                scheduler.admit(MobAiTaskScheduler.Task.IDLE,
                        MobAiLoadController.Priority.NORMAL, false, 0, 1));
        MobAiTaskScheduler.Stats stats = scheduler.stats();
        assertEquals(1, stats.deferrals().get(MobAiTaskScheduler.Task.NAVIGATION));
        assertEquals(1, stats.runs().get(MobAiTaskScheduler.Task.IDLE));
    }

    @Test
    void zeroBudgetDefersOptionalWorkEvenBeforeAnyTimeIsConsumed() {
        MobAiTaskScheduler scheduler = new MobAiTaskScheduler();

        assertEquals(MobAiTaskScheduler.Admission.DEFER,
                scheduler.admit(MobAiTaskScheduler.Task.MOVEMENT,
                        MobAiLoadController.Priority.NORMAL, false, 0, 0));

        MobAiTaskScheduler.Stats stats = scheduler.stats();
        assertEquals(1, stats.deferrals().get(MobAiTaskScheduler.Task.MOVEMENT));
        assertEquals(0, stats.runs().get(MobAiTaskScheduler.Task.MOVEMENT));
    }

    @Test
    void eachRejectedAdmissionIsCountedExactlyOnce() {
        MobAiTaskScheduler scheduler = new MobAiTaskScheduler();

        assertEquals(MobAiTaskScheduler.Admission.DEFER,
                scheduler.admit(MobAiTaskScheduler.Task.MOVEMENT,
                        MobAiLoadController.Priority.NORMAL, false, 50, 10));

        assertEquals(1, scheduler.stats().deferrals().get(MobAiTaskScheduler.Task.MOVEMENT));
    }

    @Test
    void taskCountersRemainExactUnderConcurrentUpdates() throws Exception {
        MobAiTaskScheduler scheduler = new MobAiTaskScheduler();
        int workers = 8;
        int incrementsPerWorker = 2_000;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                tasks.add(executor.submit(() -> {
                    for (int i = 0; i < incrementsPerWorker; i++) {
                        scheduler.recordRun(MobAiTaskScheduler.Task.IDLE);
                        scheduler.recordDeferral(MobAiTaskScheduler.Task.NAVIGATION);
                    }
                }));
            }
            for (Future<?> task : tasks) task.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals((long) workers * incrementsPerWorker,
                scheduler.stats().runs().get(MobAiTaskScheduler.Task.IDLE));
        assertEquals((long) workers * incrementsPerWorker,
                scheduler.stats().deferrals().get(MobAiTaskScheduler.Task.NAVIGATION));
    }

    @Test
    void taskCountersSaturateInsteadOfWrapping() {
        assertEquals(1L, MobAiTaskScheduler.saturatedAdd(0, 1));
        assertEquals(Long.MAX_VALUE,
                MobAiTaskScheduler.saturatedAdd(Long.MAX_VALUE - 1, 1));
        assertEquals(Long.MAX_VALUE,
                MobAiTaskScheduler.saturatedAdd(Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class,
                () -> MobAiTaskScheduler.saturatedAdd(-1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> MobAiTaskScheduler.saturatedAdd(1, -1));
    }
}
