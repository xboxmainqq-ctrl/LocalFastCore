package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerExperienceTest {
    @Test
    void calculatesVanillaLevelBoundaries() {
        assertEquals(new PlayerExperience.Progress(0, 0.0f, 0),
                PlayerExperience.fromTotalPoints(0));
        assertEquals(7, PlayerExperience.pointsToNextLevel(0));
        assertEquals(7, PlayerExperience.fromTotalPoints(7).level());
        assertEquals(42, PlayerExperience.pointsToNextLevel(16));
        assertEquals(112, PlayerExperience.pointsToNextLevel(30));
        assertEquals(352, PlayerExperience.pointsAtLevelStart(16));
        assertEquals(1395, PlayerExperience.pointsAtLevelStart(30));
    }

    @Test
    void computesProgressWithinCurrentLevel() {
        int start = PlayerExperience.pointsAtLevelStart(10);
        PlayerExperience.Progress progress = PlayerExperience.fromTotalPoints(start + 5);
        assertEquals(10, progress.level());
        assertEquals(5.0f / PlayerExperience.pointsToNextLevel(10), progress.progress(), 0.0001f);
    }

    @Test
    void addsPointsWithoutOverflowAndRejectsInvalidValues() {
        assertEquals(15, PlayerExperience.addPoints(10, 5));
        assertEquals(2_000_000_000, PlayerExperience.addPoints(2_000_000_000, 100));
        assertThrows(IllegalArgumentException.class, () -> PlayerExperience.fromTotalPoints(-1));
        assertThrows(IllegalArgumentException.class, () -> PlayerExperience.addPoints(1, -1));
    }
}
