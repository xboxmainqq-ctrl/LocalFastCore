package ru.nexora.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobFallTrackerTest {
    @Test
    void safeFallsDoNotDealDamageAndLongFallsDo() {
        MobFallTracker tracker = new MobFallTracker();

        tracker.update(1, 70, false, false);
        var safe = tracker.update(1, 67, true, false);
        assertEquals(3.0, safe.fallDistance(), 0.0001);
        assertEquals(0, safe.damage());

        tracker.update(2, 80, false, false);
        var hard = tracker.update(2, 75, true, false);
        assertEquals(5.0, hard.fallDistance(), 0.0001);
        assertEquals(2, hard.damage());
        assertEquals(2, tracker.stats().completedFalls());
        assertEquals(2, tracker.stats().totalDamage());

        tracker.update(3, 80, false, false);
        var fractional = tracker.update(3, 76.5, true, false);
        assertEquals(1, fractional.damage(),
                "Vanilla-style fall damage rounds any positive excess distance up");
    }

    @Test
    void tracksHighestPointAndIgnoresFlyingMobFalls() {
        MobFallTracker tracker = new MobFallTracker();
        tracker.update(7, 70, false, false);
        tracker.update(7, 72, false, false);
        assertEquals(5.0, tracker.update(7, 67, true, false).fallDistance(), 0.0001);

        tracker.update(8, 100, false, false);
        assertEquals(0, tracker.update(8, 60, true, true).damage());
        assertEquals(0, tracker.stats().airborne());
    }

    @Test
    void trackerIsBoundedAndCanClearDespawnedMobs() {
        MobFallTracker tracker = new MobFallTracker(2);
        tracker.update(1, 80, false, false);
        tracker.update(2, 80, false, false);
        tracker.update(3, 80, false, false);

        assertEquals(2, tracker.stats().airborne());
        assertEquals(1, tracker.stats().evictions());
        tracker.clear(2);
        assertEquals(1, tracker.stats().airborne());
    }

    @Test
    void rejectsInvalidObservations() {
        MobFallTracker tracker = new MobFallTracker();
        assertThrows(IllegalArgumentException.class, () -> tracker.update(0, 65, false, false));
        assertThrows(IllegalArgumentException.class, () -> tracker.update(1, Double.NaN, false, false));
        assertThrows(IllegalArgumentException.class, () -> new MobFallTracker(0));
    }
}
