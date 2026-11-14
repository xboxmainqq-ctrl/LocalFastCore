package ru.nexora.core.rpg;

import java.util.Locale;
import java.util.Objects;

/** Immutable metadata for an RPG ability; execution is supplied by a module. */
public record RpgAbilityDefinition(
        String id,
        String displayName,
        String description,
        long cooldownTicks,
        int manaCost,
        TargetMode targetMode
) {
    public RpgAbilityDefinition {
        id = normalizeId(id);
        displayName = requireText(displayName, "displayName", 64);
        description = requireText(description, "description", 256);
        if (cooldownTicks < 0 || cooldownTicks > 20_000_000L) {
            throw new IllegalArgumentException("cooldownTicks must be between 0 and 20000000");
        }
        if (manaCost < 0 || manaCost > 100_000) {
            throw new IllegalArgumentException("manaCost must be between 0 and 100000");
        }
        Objects.requireNonNull(targetMode, "targetMode");
    }

    private static String normalizeId(String value) {
        Objects.requireNonNull(value, "id");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("Ability id must be a 1-32 character slug");
        }
        return normalized;
    }

    private static String requireText(String value, String label, int maxLength) {
        Objects.requireNonNull(value, label);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + " must contain 1-" + maxLength + " characters");
        }
        return normalized;
    }

    public enum TargetMode {
        SELF,
        PLAYER,
        ENTITY,
        BLOCK,
        AREA
    }
}
