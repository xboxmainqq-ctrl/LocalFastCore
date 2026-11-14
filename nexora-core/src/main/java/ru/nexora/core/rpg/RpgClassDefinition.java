package ru.nexora.core.rpg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, platform-neutral description of a selectable RPG class. */
public record RpgClassDefinition(
        String id,
        String displayName,
        String description,
        Map<String, Integer> baseAttributes,
        List<String> abilityIds
) {
    private static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
            "strength", "agility", "vitality", "intelligence", "wisdom");

    public RpgClassDefinition {
        id = normalizeId(id, "class id");
        displayName = requireText(displayName, "displayName", 64);
        description = requireText(description, "description", 256);
        Objects.requireNonNull(baseAttributes, "baseAttributes");
        Objects.requireNonNull(abilityIds, "abilityIds");

        Map<String, Integer> attributeCopy = new LinkedHashMap<>();
        baseAttributes.forEach((key, value) -> {
            String normalizedKey = Objects.requireNonNull(key, "attribute key")
                    .trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_ATTRIBUTES.contains(normalizedKey)) {
                throw new IllegalArgumentException("Unsupported base attribute: " + key);
            }
            if (value == null || value < 0 || value > 1000) {
                throw new IllegalArgumentException("Base attribute values must be between 0 and 1000");
            }
            if (attributeCopy.putIfAbsent(normalizedKey, value) != null) {
                throw new IllegalArgumentException("Duplicate base attribute: " + normalizedKey);
            }
        });
        baseAttributes = Collections.unmodifiableMap(attributeCopy);

        LinkedHashSet<String> uniqueAbilities = new LinkedHashSet<>();
        for (String abilityId : abilityIds) {
            String normalized = normalizeId(abilityId, "ability id");
            if (!uniqueAbilities.add(normalized)) {
                throw new IllegalArgumentException("Duplicate ability id: " + normalized);
            }
        }
        abilityIds = List.copyOf(uniqueAbilities);
    }

    private static String normalizeId(String value, String label) {
        Objects.requireNonNull(value, label);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException(label + " must be a 1-32 character slug");
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
}
