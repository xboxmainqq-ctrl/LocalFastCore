package ru.nexora.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobFuseTrackerTest {
    @Test
    void fuseMustRemainInRangeForFullDuration() {
        MobFuseTracker tracker = new MobFuseTracker();

        assertFalse(tracker.isReadyToDetonate(1, true, 1_000, 1_500));
        assertFalse(tracker.isReadyToDetonate(1, true, 2_499, 1_500));
        assertTrue(tracker.isReadyToDetonate(1, true, 2_500, 1_500));
        assertEquals(0, tracker.trackedFuses());
    }

    @Test
    void leavingRangeResetsFuseInsteadOfResumingOldProgress() {
        MobFuseTracker tracker = new MobFuseTracker();

        assertFalse(tracker.isReadyToDetonate(1, true, 1_000, 1_500));
        assertFalse(tracker.isReadyToDetonate(1, false, 2_400, 1_500));
        assertFalse(tracker.isReadyToDetonate(1, true, 2_500, 1_500));
        assertTrue(tracker.isReadyToDetonate(1, true, 4_000, 1_500));
    }

    @Test
    void clearingForUnloadedMobRequiresFreshFuseAfterReload() {
        MobFuseTracker tracker = new MobFuseTracker();

        assertFalse(tracker.isReadyToDetonate(7, true, 10_000, 1_500));
        tracker.clear(7);
        assertFalse(tracker.isReadyToDetonate(7, true, 20_000, 1_500));
        assertEquals(1, tracker.trackedFuses());
        assertTrue(tracker.isReadyToDetonate(7, true, 21_500, 1_500));
    }

    @Test
    void clockRollbackRestartsFuseAndInvalidObservationsAreRejected() {
        MobFuseTracker tracker = new MobFuseTracker();

        assertFalse(tracker.isReadyToDetonate(2, true, 5_000, 1_500));
        assertFalse(tracker.isReadyToDetonate(2, true, 4_000, 1_500));
        assertFalse(tracker.isReadyToDetonate(2, true, 5_499, 1_500));
        assertTrue(tracker.isReadyToDetonate(2, true, 5_500, 1_500));

        assertThrows(IllegalArgumentException.class,
                () -> tracker.isReadyToDetonate(0, true, 0, 1_500));
        assertThrows(IllegalArgumentException.class,
                () -> tracker.isReadyToDetonate(1, true, -1, 1_500));
        assertThrows(IllegalArgumentException.class,
                () -> tracker.isReadyToDetonate(1, true, 0, 0));
    }

    @Test
    void fuseIsConsumedAfterDetonationAndMustBePrimedAgain() {
        MobFuseTracker tracker = new MobFuseTracker();

        assertFalse(tracker.isReadyToDetonate(3, true, 1_000, 1_500));
        assertTrue(tracker.isReadyToDetonate(3, true, 2_500, 1_500));
        assertFalse(tracker.isReadyToDetonate(3, true, 2_501, 1_500));
        assertEquals(1, tracker.trackedFuses());
        assertTrue(tracker.isReadyToDetonate(3, true, 4_001, 1_500));
    }

    @Test
    void leastRecentlyUsedFuseIsEvictedAtCapacity() {
        MobFuseTracker tracker = new MobFuseTracker();

        for (long id = 1; id <= MobFuseTracker.MAX_TRACKED_FUSES; id++) {
            assertFalse(tracker.isReadyToDetonate(id, true, 1_000, 1_500));
        }
        assertFalse(tracker.isReadyToDetonate(1, true, 1_100, 1_500));
        assertFalse(tracker.isReadyToDetonate(
                MobFuseTracker.MAX_TRACKED_FUSES + 1L, true, 1_100, 1_500));
        assertEquals(MobFuseTracker.MAX_TRACKED_FUSES, tracker.trackedFuses());

        // The access above refreshed id 1, so id 2 must have been evicted.
        assertFalse(tracker.isReadyToDetonate(1, true, 2_500, 1_500));
        assertFalse(tracker.isReadyToDetonate(2, true, 2_500, 1_500));
        assertEquals(MobFuseTracker.MAX_TRACKED_FUSES, tracker.trackedFuses());
    }
}
