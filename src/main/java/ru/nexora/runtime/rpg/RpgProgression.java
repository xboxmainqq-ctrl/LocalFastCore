package ru.nexora.runtime.rpg;

import java.util.Objects;

/** Pure server-side XP and level-up rules; safe to test without a running server. */
public final class RpgProgression {
    public static final int MAX_LEVEL = 100;
    public static final int ATTRIBUTE_POINTS_PER_LEVEL = 5;

    private RpgProgression() {
    }

    /** XP needed to advance from the supplied level to the next one. */
    public static long experienceRequired(int level) {
        if (level < 1 || level >= MAX_LEVEL) {
            throw new IllegalArgumentException("A level-up threshold exists only for levels 1-" + (MAX_LEVEL - 1));
        }
        long offset = level - 1L;
        return 100L + 50L * offset + 25L * offset * offset;
    }

    /**
     * Awards XP and applies every earned level on the server.
     * XP beyond the maximum level is not accepted.
     */
    public static AwardResult award(RpgProgress current, long amount) {
        Objects.requireNonNull(current, "current");
        if (amount < 0) throw new IllegalArgumentException("XP award cannot be negative");
        if (amount == 0 || current.level() == MAX_LEVEL) {
            return new AwardResult(current, 0, 0);
        }

        int level = current.level();
        int points = current.unspentAttributePoints();
        long progress = current.experienceIntoLevel();
        long remaining = amount;
        int levelsGained = 0;

        while (remaining > 0 && level < MAX_LEVEL) {
            long needed = experienceRequired(level) - progress;
            if (remaining < needed) {
                progress += remaining;
                remaining = 0;
            } else {
                remaining -= needed;
                level++;
                levelsGained++;
                points += ATTRIBUTE_POINTS_PER_LEVEL;
                progress = 0;
            }
        }

        long accepted = amount - remaining;
        return new AwardResult(new RpgProgress(level, progress, points), levelsGained, accepted);
    }

    public record AwardResult(RpgProgress progress, int levelsGained, long acceptedExperience) {
        public AwardResult {
            Objects.requireNonNull(progress, "progress");
            if (levelsGained < 0 || acceptedExperience < 0) {
                throw new IllegalArgumentException("Award result values cannot be negative");
            }
        }
    }
}
