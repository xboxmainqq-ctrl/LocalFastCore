package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobStuckTrackerTest {
    @Test
    void requestsRecoveryAfterThreeStationarySamples() {
        MobStuckTracker tracker = new MobStuckTracker();

        assertEquals(0, tracker.observe(1, 0.5, 65, 0.5, 1_000));
        assertEquals(0, tracker.observe(1, 0.5, 65, 0.5, 1_050));
        assertEquals(0, tracker.observe(1, 0.5, 65, 0.5, 1_100));
        assertEquals(1, tracker.observe(1, 0.5, 65, 0.5, 1_150));
    }

    @Test
    void rateLimitsRecoveryAndAlternatesAttemptsAfterCooldown() {
        MobStuckTracker tracker = new MobStuckTracker();
        tracker.observe(2, 0.5, 65, 0.5, 2_000);
        tracker.observe(2, 0.5, 65, 0.5, 2_050);
        tracker.observe(2, 0.5, 65, 0.5, 2_100);

        assertEquals(1, tracker.observe(2, 0.5, 65, 0.5, 2_150));
        assertEquals(1, tracker.observe(2, 0.5, 65, 0.5, 2_900));
        assertEquals(2, tracker.observe(2, 0.5, 65, 0.5, 3_150));
    }

    @Test
    void movementResetsStallCounterAndRecoveryAttempt() {
        MobStuckTracker tracker = new MobStuckTracker();
        tracker.observe(3, 0.5, 65, 0.5, 4_000);
        tracker.observe(3, 0.5, 65, 0.5, 4_050);
        tracker.observe(3, 0.5, 65, 0.5, 4_100);
        assertEquals(1, tracker.observe(3, 0.5, 65, 0.5, 4_150));

        assertEquals(0, tracker.observe(3, 0.8, 65, 0.5, 4_200));
        assertEquals(0, tracker.observe(3, 0.8, 65, 0.5, 4_250));
        assertEquals(0, tracker.observe(3, 0.8, 65, 0.5, 4_300));
        assertEquals(1, tracker.observe(3, 0.8, 65, 0.5, 4_350));
    }

    @Test
    void keepsOnlyConfiguredNumberOfMobs() {
        MobStuckTracker tracker = new MobStuckTracker(2);
        tracker.observe(1, 0, 65, 0, 0);
        tracker.observe(2, 0, 65, 0, 0);
        tracker.observe(3, 0, 65, 0, 0);

        assertEquals(2, tracker.size());
        assertEquals(0, tracker.observe(1, 0, 65, 0, 1));
    }

    @Test
    void rejectsInvalidSamples() {
        MobStuckTracker tracker = new MobStuckTracker();
        assertThrows(IllegalArgumentException.class,
                () -> tracker.observe(0, 0, 65, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> tracker.observe(1, Double.NaN, 65, 0, 0));
    }
}
