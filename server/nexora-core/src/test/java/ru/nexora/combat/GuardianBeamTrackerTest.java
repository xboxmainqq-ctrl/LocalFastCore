package ru.nexora.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuardianBeamTrackerTest {
    @Test
    void requiresContinuousLineOfSightAndSameTargetForCharge() {
        GuardianBeamTracker tracker = new GuardianBeamTracker();

        assertEquals(new GuardianBeamTracker.Result(true, false, 0.0),
                tracker.tick(10L, "player-a", true, 1_000L));
        assertEquals(new GuardianBeamTracker.Result(true, false, 0.5),
                tracker.tick(10L, "player-a", true, 1_750L));
        assertEquals(new GuardianBeamTracker.Result(false, false, 0.0),
                tracker.tick(10L, "player-a", false, 1_800L));
        assertEquals(new GuardianBeamTracker.Result(true, false, 0.0),
                tracker.tick(10L, "player-a", true, 1_850L));
        assertEquals(new GuardianBeamTracker.Result(true, false, 0.0),
                tracker.tick(10L, "player-b", true, 2_000L));
        assertEquals(new GuardianBeamTracker.Result(false, true, 1.0),
                tracker.tick(10L, "player-b", true, 3_500L));
    }

    @Test
    void firesAfterChargeThenWaitsForRecoveryAndChargesAgain() {
        GuardianBeamTracker tracker = new GuardianBeamTracker();

        tracker.tick(20L, "target", true, 10_000L);
        assertEquals(new GuardianBeamTracker.Result(false, true, 1.0),
                tracker.tick(20L, "target", true, 11_500L));
        assertEquals(new GuardianBeamTracker.Result(false, false, 0.0),
                tracker.tick(20L, "target", true, 14_000L));
        assertEquals(new GuardianBeamTracker.Result(true, false, 0.0),
                tracker.tick(20L, "target", true, 16_500L));
        assertEquals(new GuardianBeamTracker.Result(false, true, 1.0),
                tracker.tick(20L, "target", true, 18_000L));
    }

    @Test
    void clearDropsChargeAndTrackerBoundsItsState() {
        GuardianBeamTracker tracker = new GuardianBeamTracker();
        tracker.tick(30L, "target", true, 100L);
        tracker.clear(30L);
        assertEquals(new GuardianBeamTracker.Result(true, false, 0.0),
                tracker.tick(30L, "target", true, 200L));
    }

    @Test
    void rejectsInvalidGuardianIdsAndTime() {
        GuardianBeamTracker tracker = new GuardianBeamTracker();
        assertThrows(IllegalArgumentException.class,
                () -> tracker.tick(0L, "target", true, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> tracker.tick(1L, "target", true, -1L));
    }
}
