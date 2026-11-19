package ru.nexora.combat;

import org.junit.jupiter.api.Test;

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
}
