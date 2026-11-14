package ru.nexora.core.profile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Platform-neutral RPG progression rules.
 *
 * Experience is lifetime total XP. The profile repository owns persistence;
 * callers should invoke this service from a worker thread when using SQLite.
 */
public final class PlayerProgressionService {
    public static final int MAX_LEVEL = 100;
    public static final String UNSPENT_POINTS = "unspent_stat_points";

    private static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
            "strength", "agility", "vitality", "intelligence", "wisdom");

    private final PlayerProfileRepository profiles;

    public PlayerProgressionService(PlayerProfileRepository profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    public synchronized PlayerProfile loadOrCreate(UUID uniqueId, String currentName)
            throws IOException {
        Objects.requireNonNull(uniqueId, "uniqueId");
        Objects.requireNonNull(currentName, "currentName");
        Optional<PlayerProfile> stored = profiles.find(uniqueId);
        if (stored.isEmpty()) {
            PlayerProfile created = PlayerProfile.create(uniqueId, currentName);
            profiles.save(created);
            return created;
        }
        PlayerProfile profile = stored.get();
        if (!profile.lastKnownName().equals(currentName)) {
            profile = profile.withName(currentName);
            profiles.save(profile);
        }
        return profile;
    }

    public synchronized ProgressResult addExperience(UUID uniqueId, long amount)
            throws IOException {
        if (amount <= 0) {
            throw new IllegalArgumentException("Experience amount must be positive");
        }
        PlayerProfile current = requireProfile(uniqueId);
        int expectedCurrentLevel = levelForExperience(current.experience());
        if (current.level() != expectedCurrentLevel) {
            throw new IllegalStateException("Stored player level does not match total experience");
        }

        long maximumExperience = experienceRequiredForLevel(MAX_LEVEL - 1);
        long updatedExperience;
        try {
            updatedExperience = Math.min(maximumExperience,
                    Math.addExact(current.experience(), amount));
        } catch (ArithmeticException overflow) {
            updatedExperience = maximumExperience;
        }

        int updatedLevel = levelForExperience(updatedExperience);
        int gainedLevels = updatedLevel - current.level();
        Map<String, Integer> attributes = new LinkedHashMap<>(current.attributes());
        if (gainedLevels > 0) {
            int currentPoints = attributes.getOrDefault(UNSPENT_POINTS, 0);
            attributes.put(UNSPENT_POINTS, Math.addExact(currentPoints,
                    Math.multiplyExact(gainedLevels, 5)));
        }
        PlayerProfile updated = new PlayerProfile(current.uniqueId(),
                current.lastKnownName(), current.classId(), updatedLevel,
                updatedExperience, attributes);
        profiles.save(updated);
        long appliedExperience = updatedExperience - current.experience();
        return new ProgressResult(updated, appliedExperience, gainedLevels,
                updated.attributes().getOrDefault(UNSPENT_POINTS, 0));
    }

    public synchronized PlayerProfile chooseClass(UUID uniqueId, String classId)
            throws IOException {
        Objects.requireNonNull(classId, "classId");
        String normalized = classId.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("Class id must be a 1-32 character slug");
        }
        PlayerProfile updated = requireProfile(uniqueId).withClass(normalized);
        profiles.save(updated);
        return updated;
    }

    public synchronized PlayerProfile spendAttributePoint(UUID uniqueId, String attribute)
            throws IOException {
        Objects.requireNonNull(attribute, "attribute");
        String key = attribute.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_ATTRIBUTES.contains(key)) {
            throw new IllegalArgumentException("Unsupported RPG attribute: " + attribute);
        }
        PlayerProfile current = requireProfile(uniqueId);
        int points = current.attributes().getOrDefault(UNSPENT_POINTS, 0);
        if (points < 1) {
            throw new IllegalStateException("No unspent attribute points");
        }
        int value = current.attributes().getOrDefault(key, 0);
        PlayerProfile updated = current.withAttribute(UNSPENT_POINTS, points - 1)
                .withAttribute(key, Math.addExact(value, 1));
        profiles.save(updated);
        return updated;
    }

    /** XP threshold required to advance from the supplied level to the next one. */
    public static long experienceRequiredForLevel(int level) {
        if (level < 1 || level > MAX_LEVEL - 1) {
            throw new IllegalArgumentException("Level must be between 1 and " + (MAX_LEVEL - 1));
        }
        return 50L * level * (level + 1L);
    }

    private static int levelForExperience(long totalExperience) {
        int level = 1;
        while (level < MAX_LEVEL
                && totalExperience >= experienceRequiredForLevel(level)) {
            level++;
        }
        return level;
    }

    private PlayerProfile requireProfile(UUID uniqueId) throws IOException {
        Objects.requireNonNull(uniqueId, "uniqueId");
        return profiles.find(uniqueId)
                .orElseThrow(() -> new IllegalStateException(
                        "Player profile does not exist: " + uniqueId));
    }

    public record ProgressResult(PlayerProfile profile, long awardedExperience,
                                 int gainedLevels, int unspentAttributePoints) { }
}
