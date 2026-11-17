package ru.nexora.world;

/** Vanilla-compatible player XP level/progress calculations. */
public final class PlayerExperience {
    public static final int MAX_LEVEL = 24_791;
    private static final int MAX_TOTAL_POINTS = 2_000_000_000;

    private PlayerExperience() { }

    public static Progress fromTotalPoints(int totalPoints) {
        if (totalPoints < 0 || totalPoints > MAX_TOTAL_POINTS) {
            throw new IllegalArgumentException("Experience points must be 0..2,000,000,000");
        }
        int level = 0;
        long remaining = totalPoints;
        while (level < MAX_LEVEL) {
            int required = pointsToNextLevel(level);
            if (remaining < required) break;
            remaining -= required;
            level++;
        }
        int next = pointsToNextLevel(level);
        return new Progress(level, (float) remaining / next, totalPoints);
    }

    public static Progress fromTotalMilliPoints(long totalMilliPoints) {
        if (totalMilliPoints < 0 || totalMilliPoints > (long) MAX_TOTAL_POINTS * 1000L) {
            throw new IllegalArgumentException("Experience must be within supported range");
        }
        int level = 0;
        long remainingMilli = totalMilliPoints;
        while (level < MAX_LEVEL) {
            long requiredMilli = (long) pointsToNextLevel(level) * 1000L;
            if (remainingMilli < requiredMilli) break;
            remainingMilli -= requiredMilli;
            level++;
        }
        float progress = (float) (remainingMilli / 1000.0 / pointsToNextLevel(level));
        return new Progress(level, progress, (int) (totalMilliPoints / 1000L));
    }

    public static int pointsToNextLevel(int level) {
        if (level < 0 || level > MAX_LEVEL) throw new IllegalArgumentException("Invalid XP level");
        if (level <= 15) return 2 * level + 7;
        if (level <= 30) return 5 * level - 38;
        return 9 * level - 158;
    }

    public static int pointsAtLevelStart(int level) {
        if (level < 0 || level > MAX_LEVEL) throw new IllegalArgumentException("Invalid XP level");
        long points;
        if (level <= 16) points = (long) level * level + 6L * level;
        else if (level <= 31) points = (5L * level * level - 81L * level + 720L) / 2L;
        else points = (9L * level * level - 325L * level + 4440L) / 2L;
        return (int) Math.min(MAX_TOTAL_POINTS, points);
    }

    /** Deducts levels while retaining the same fractional progress through the current level. */
    public static long removeLevelsFromTotalMilliPoints(long totalMilliPoints, int levels) {
        if (levels < 0) throw new IllegalArgumentException("levels cannot be negative");
        if (totalMilliPoints < 0 || totalMilliPoints > (long) MAX_TOTAL_POINTS * 1000L) {
            throw new IllegalArgumentException("Experience must be within supported range");
        }
        if (levels == 0 || totalMilliPoints == 0) return totalMilliPoints;

        int currentLevel = fromTotalMilliPoints(totalMilliPoints).level();
        int targetLevel = Math.max(0, currentLevel - levels);
        if (targetLevel == currentLevel) return totalMilliPoints;

        long currentLevelStart = (long) pointsAtLevelStart(currentLevel) * 1000L;
        long progressMilli = totalMilliPoints - currentLevelStart;
        long currentLevelSpan = (long) pointsToNextLevel(currentLevel) * 1000L;
        long targetLevelSpan = (long) pointsToNextLevel(targetLevel) * 1000L;
        long targetProgressMilli = progressMilli * targetLevelSpan / currentLevelSpan;
        return (long) pointsAtLevelStart(targetLevel) * 1000L + targetProgressMilli;
    }

    public static int addPoints(int currentTotal, int points) {
        if (currentTotal < 0 || currentTotal > MAX_TOTAL_POINTS || points < 0) {
            throw new IllegalArgumentException("Invalid XP adjustment");
        }
        return (int) Math.min(MAX_TOTAL_POINTS, (long) currentTotal + points);
    }

    public record Progress(int level, float progress, int totalPoints) {
        public Progress {
            if (level < 0 || !Float.isFinite(progress) || progress < 0 || progress >= 1
                    || totalPoints < 0) throw new IllegalArgumentException("Invalid XP progress");
        }
    }
}
