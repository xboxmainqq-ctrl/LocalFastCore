package ru.nexora.runtime.rpg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RpgProgressionTest {
    @Test
    void awardsPartialExperienceWithoutLeveling() {
        RpgProgression.AwardResult result =
                RpgProgression.award(RpgProgress.newCharacter(), 40);

        assertEquals(new RpgProgress(1, 40, 0), result.progress());
        assertEquals(0, result.levelsGained());
        assertEquals(40, result.acceptedExperience());
    }

    @Test
    void levelsUpAndGrantsAttributePoints() {
        RpgProgression.AwardResult result =
                RpgProgression.award(RpgProgress.newCharacter(), 250);

        assertEquals(new RpgProgress(2, 150, 5), result.progress());
        assertEquals(1, result.levelsGained());
        assertEquals(250, result.acceptedExperience());
    }

    @Test
    void handlesMultipleLevelsInOneAward() {
        RpgProgression.AwardResult result =
                RpgProgression.award(RpgProgress.newCharacter(), 450);

        assertEquals(3, result.progress().level());
        assertEquals(175, result.progress().experienceIntoLevel());
        assertEquals(10, result.progress().unspentAttributePoints());
        assertEquals(2, result.levelsGained());
    }

    @Test
    void capsExperienceAtMaximumLevel() {
        RpgProgress start = new RpgProgress(99, 0, 490);
        RpgProgression.AwardResult result = RpgProgression.award(start, Long.MAX_VALUE);

        assertEquals(new RpgProgress(100, 0, 495), result.progress());
        assertEquals(1, result.levelsGained());
        assertTrue(result.acceptedExperience() > 0);
        assertTrue(result.acceptedExperience() < Long.MAX_VALUE);
    }

    @Test
    void rejectsNegativeExperienceAndInvalidLevels() {
        assertThrows(IllegalArgumentException.class,
                () -> RpgProgression.award(RpgProgress.newCharacter(), -1));
        assertThrows(IllegalArgumentException.class,
                () -> RpgProgression.experienceRequired(100));
        assertThrows(IllegalArgumentException.class,
                () -> new RpgProgress(101, 0, 0));
    }
}
