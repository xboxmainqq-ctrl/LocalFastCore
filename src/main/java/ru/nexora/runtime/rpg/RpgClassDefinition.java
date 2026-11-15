package ru.nexora.runtime.rpg;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, renderer-independent definition of a selectable RPG class. */
public record RpgClassDefinition(
        String id,
        String displayName,
        String description,
        RpgStats baseStats,
        int baseHealth,
        int baseMana) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public RpgClassDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(baseStats, "baseStats");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("RPG class id must be namespaced: " + id);
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("RPG class display name must not be blank");
        }
        if (description.length() > 2_000) {
            throw new IllegalArgumentException("RPG class description is too long");
        }
        if (baseHealth < 1 || baseHealth > 100_000) {
            throw new IllegalArgumentException("Base health must be between 1 and 100000");
        }
        if (baseMana < 0 || baseMana > 100_000) {
            throw new IllegalArgumentException("Base mana must be between 0 and 100000");
        }
    }
}
