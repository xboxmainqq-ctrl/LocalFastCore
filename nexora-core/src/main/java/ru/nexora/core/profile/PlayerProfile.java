package ru.nexora.core.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-neutral persistent player state.
 *
 * The attributes map is intentionally extensible: RPG modules can define
 * their own characteristic keys without forcing the core to know every class.
 */
public record PlayerProfile(
        UUID uniqueId,
        String lastKnownName,
        Optional<String> classId,
        int level,
        long experience,
        Map<String, Integer> attributes
) {
    public PlayerProfile {
        Objects.requireNonNull(uniqueId, "uniqueId");
        Objects.requireNonNull(lastKnownName, "lastKnownName");
        Objects.requireNonNull(classId, "classId");
        Objects.requireNonNull(attributes, "attributes");

        if (lastKnownName.isBlank()) {
            throw new IllegalArgumentException("Player name must not be blank");
        }
        if (level < 1) {
            throw new IllegalArgumentException("Player level must be at least 1");
        }
        if (experience < 0) {
            throw new IllegalArgumentException("Experience must not be negative");
        }

        classId = classId.map(String::trim);
        if (classId.isPresent() && classId.get().isEmpty()) {
            throw new IllegalArgumentException("Class id must not be blank");
        }

        Map<String, Integer> copy = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Attribute keys must not be blank");
            }
            copy.put(key, Objects.requireNonNull(value, "attribute value"));
        });
        attributes = Collections.unmodifiableMap(copy);
    }

    public static PlayerProfile create(UUID uniqueId, String name) {
        return new PlayerProfile(uniqueId, name, Optional.empty(), 1, 0L, Map.of());
    }

    public PlayerProfile withName(String name) {
        return new PlayerProfile(uniqueId, name, classId, level, experience, attributes);
    }

    public PlayerProfile withClass(String newClassId) {
        Optional<String> nextClass = newClassId == null || newClassId.isBlank()
                ? Optional.empty() : Optional.of(newClassId);
        return new PlayerProfile(uniqueId, lastKnownName, nextClass, level, experience, attributes);
    }

    public PlayerProfile withProgress(int newLevel, long newExperience) {
        return new PlayerProfile(uniqueId, lastKnownName, classId, newLevel, newExperience, attributes);
    }

    public PlayerProfile withAttribute(String key, int value) {
        Map<String, Integer> updated = new LinkedHashMap<>(attributes);
        updated.put(key, value);
        return new PlayerProfile(uniqueId, lastKnownName, classId, level, experience, updated);
    }
}
