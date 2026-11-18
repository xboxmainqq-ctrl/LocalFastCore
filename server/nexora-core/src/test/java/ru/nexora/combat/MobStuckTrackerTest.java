package ru.nexora.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobStuckTrackerTest {
    @Test
    void reportsMobOnlyAfterSustainedBlockedMovement() {
        MobStuckTracker tracker = new MobStuckTracker();

        assertNull(tracker.recordBlockedAttempt(7, 1, 64, 1, 1_000));
        assertNull(tracker.recordBlockedAttempt(7, 1, 64, 1, 2_000));
        var stuck = tracker.recordBlockedAttempt(7, 1, 64, 1, 3_000);

        assertNotNull(stuck);
        assertEquals(7, stuck.runtimeId());
        assertEquals(2_000, stuck.durationMillis());
        assertEquals(3, stuck.attempts());
        assertEquals(1, tracker.stats(3_000).stuck());
    }

    @Test
    void movementProgressResetsTheStuckTimer() {
        MobStuckTracker tracker = new MobStuckTracker();
        tracker.recordBlockedAttempt(9, 0, 64, 0, 1_000);
        tracker.recordBlockedAttempt(9, 0, 64, 0, 2_000);

        assertNull(tracker.recordBlockedAttempt(9, 0.2, 64, 0, 2_100));
        assertNull(tracker.recordBlockedAttempt(9, 0.2, 64, 0, 3_000));
        assertNull(tracker.recordBlockedAttempt(9, 0.2, 64, 0, 4_000));
        assertNotNull(tracker.recordBlockedAttempt(9, 0.2, 64, 0, 4_100));
    }

    @Test
    void clearRemovesHistoryAfterSuccessfulMovementOrIdle() {
        MobStuckTracker tracker = new MobStuckTracker();
        tracker.recordBlockedAttempt(11, 0, 64, 0, 1_000);
        tracker.clear(11);

        assertEquals(0, tracker.stats(5_000).tracked());
        assertEquals(0, tracker.stats(5_000).stuck());
    }

    @Test
    void trackerIsBoundedAndReportsEvictions() {
        MobStuckTracker tracker = new MobStuckTracker(2);
        tracker.recordBlockedAttempt(1, 0, 64, 0, 1_000);
        tracker.recordBlockedAttempt(2, 0, 64, 0, 1_000);
        tracker.recordBlockedAttempt(3, 0, 64, 0, 1_000);

        assertEquals(2, tracker.stats(1_000).tracked());
        assertEquals(1, tracker.stats(1_000).evictions());
        assertTrue(tracker.stuck(1_000, 5).isEmpty());
    }

    @Test
    void inactiveObservationsExpireAndDoNotProduceFalseStuckReports() {
        MobStuckTracker tracker = new MobStuckTracker();
        tracker.recordBlockedAttempt(15, 0, 64, 0, 1_000);
        tracker.recordBlockedAttempt(15, 0, 64, 0, 2_000);
        assertNotNull(tracker.recordBlockedAttempt(15, 0, 64, 0, 3_000));

        assertTrue(tracker.stuck(4_001, 5).isEmpty());
        assertEquals(0, tracker.stats(4_001).tracked());
        assertEquals(0, tracker.stats(4_001).stuck());

        assertNull(tracker.recordBlockedAttempt(15, 0, 64, 0, 4_100));
        assertNull(tracker.recordBlockedAttempt(15, 0, 64, 0, 5_100));
        assertNotNull(tracker.recordBlockedAttempt(15, 0, 64, 0, 6_100));
    }

    @Test
    void rejectsInvalidObservations() {
        MobStuckTracker tracker = new MobStuckTracker();
        assertThrows(IllegalArgumentException.class,
                () -> tracker.recordBlockedAttempt(0, 0, 64, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> tracker.recordBlockedAttempt(1, Double.NaN, 64, 0, 1));
    }
}
