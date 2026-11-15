package ru.nexora.runtime.rpg;

/** Persistent-value model for a character's level progress. */
public record RpgProgress(int level, long experienceIntoLevel, int unspentAttributePoints) {
    public RpgProgress {
        if (level < 1 || level > RpgProgression.MAX_LEVEL) {
            throw new IllegalArgumentException("Level must be between 1 and " + RpgProgression.MAX_LEVEL);
        }
        if (experienceIntoLevel < 0) {
            throw new IllegalArgumentException("Experience cannot be negative");
        }
        if (unspentAttributePoints < 0) {
            throw new IllegalArgumentException("Unspent attribute points cannot be negative");
        }
        if (level == RpgProgression.MAX_LEVEL && experienceIntoLevel != 0) {
            throw new IllegalArgumentException("Maximum-level characters cannot hold level-up experience");
        }
    }

    public static RpgProgress newCharacter() {
        return new RpgProgress(1, 0, 0);
    }
}
