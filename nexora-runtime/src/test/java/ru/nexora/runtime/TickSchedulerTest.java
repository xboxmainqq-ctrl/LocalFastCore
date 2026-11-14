package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TickSchedulerTest {
    @Test
    void runsTasksOnTheirDueTickInRegistrationOrder() {
        TickScheduler scheduler = new TickScheduler();
        List<String> calls = new ArrayList<>();

        scheduler.schedule(2, () -> calls.add("first"));
        scheduler.schedule(1, () -> calls.add("next-tick"));
        scheduler.schedule(2, () -> calls.add("second"));

        scheduler.tick();
        assertEquals(List.of("next-tick"), calls);
        scheduler.tick();

        assertEquals(List.of("next-tick", "first", "second"), calls);
        assertEquals(2, scheduler.currentTick());
    }

    @Test
    void repeatingTaskRunsAtFixedTickIntervalsUntilCancelled() {
        TickScheduler scheduler = new TickScheduler();
        List<Long> ticks = new ArrayList<>();
        TickScheduler.TaskHandle handle =
                scheduler.scheduleRepeating(2, 3, () -> ticks.add(scheduler.currentTick()));

        for (int i = 0; i < 9; i++) {
            scheduler.tick();
        }

        assertEquals(List.of(2L, 5L, 8L), ticks);
        assertTrue(handle.cancel());
        assertTrue(handle.isCancelled());
        assertTrue(handle.isDone());

        scheduler.tick();
        assertEquals(List.of(2L, 5L, 8L), ticks);
        assertFalse(handle.cancel());
    }

    @Test
    void oneShotTaskBecomesDoneAfterExecution() {
        TickScheduler scheduler = new TickScheduler();
        TickScheduler.TaskHandle handle = scheduler.schedule(1, () -> { });

        assertFalse(handle.isDone());
        scheduler.tick();

        assertTrue(handle.isDone());
        assertFalse(handle.isCancelled());
        assertFalse(handle.cancel());
    }

    @Test
    void cancelledTaskDoesNotExecute() {
        TickScheduler scheduler = new TickScheduler();
        List<String> calls = new ArrayList<>();
        TickScheduler.TaskHandle handle = scheduler.schedule(1, () -> calls.add("unexpected"));

        assertTrue(handle.cancel());
        scheduler.tick();

        assertTrue(calls.isEmpty());
        assertTrue(handle.isDone());
    }

    @Test
    void taskCanScheduleAnotherTaskForTheFollowingTick() {
        TickScheduler scheduler = new TickScheduler();
        List<String> calls = new ArrayList<>();

        scheduler.schedule(1, () -> {
            calls.add("parent");
            scheduler.schedule(1, () -> calls.add("child"));
        });

        scheduler.tick();
        assertEquals(List.of("parent"), calls);
        scheduler.tick();
        assertEquals(List.of("parent", "child"), calls);
    }

    @Test
    void continuesOtherDueTasksAndReportsTaskFailures() {
        TickScheduler scheduler = new TickScheduler();
        List<String> calls = new ArrayList<>();
        scheduler.schedule(1, () -> {
            calls.add("failed");
            throw new IllegalStateException("task failure");
        });
        scheduler.schedule(1, () -> calls.add("survived"));

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, scheduler::tick);

        assertEquals("task failure", failure.getMessage());
        assertEquals(List.of("failed", "survived"), calls);
        assertEquals(1, scheduler.currentTick());
    }

    @Test
    void rejectsInvalidDelaysPeriodsAndNullActions() {
        TickScheduler scheduler = new TickScheduler();

        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(0, () -> { }));
        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(-1, () -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.scheduleRepeating(1, 0, () -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.scheduleRepeating(0, 1, () -> { }));
        assertThrows(NullPointerException.class, () -> scheduler.schedule(1, null));
    }
}
