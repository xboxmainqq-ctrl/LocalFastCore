package ru.nexora.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobDespawnTrackerTest {
    @Test
    void despawnsOnlyAfterHostileMobStaysBeyondRadiusForGracePeriod() {
        MobDespawnTracker tracker = new MobDespawnTracker();
        double far = (MobDespawnTracker.DESPAWN_RADIUS + 1)
                * (MobDespawnTracker.DESPAWN_RADIUS + 1);

        assertFalse(tracker.shouldDespawn(1, true, far, 1_000));
        assertFalse(tracker.shouldDespawn(1, true, far, 30_999));
        assertTrue(tracker.shouldDespawn(1, true, far, 31_000));
    }

    @Test
    void nearbyPlayerOrNonHostileMobClearsDespawnTimer() {
        MobDespawnTracker tracker = new MobDespawnTracker();
        double far = 200 * 200;

        assertFalse(tracker.shouldDespawn(2, true, far, 1_000));
        assertFalse(tracker.shouldDespawn(2, true, 10, 20_000));
        assertFalse(tracker.shouldDespawn(2, true, far, 20_001));
        assertFalse(tracker.shouldDespawn(3, false, far, 1_000));
        assertEquals(1, tracker.stats().tracked());
    }

    @Test
    void clockRollbackRestartsGracePeriodAndCapacityIsBounded() {
        MobDespawnTracker tracker = new MobDespawnTracker(1);
        double far = 200 * 200;

        assertFalse(tracker.shouldDespawn(1, true, far, 10_000));
        assertFalse(tracker.shouldDespawn(1, true, far, 5_000));
        assertFalse(tracker.shouldDespawn(2, true, far, 5_001));
        assertEquals(1, tracker.stats().tracked());
        assertEquals(1, tracker.stats().evictions());
        assertThrows(IllegalArgumentException.class,
                () -> tracker.shouldDespawn(0, true, far, 0));
    }
}
