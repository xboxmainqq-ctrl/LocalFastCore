package ru.nexora.inventory;

import java.util.Map;
import java.util.Objects;

/**
 * Central durability policy. Durability is deliberately disabled by default.
 * When enabled, successful melee attacks wear the held weapon and melee hits
 * wear equipped armor; Unbreaking is respected and damage is persisted.
 */
public record DurabilitySettings(boolean enabled) {
    public static final String ENVIRONMENT_VARIABLE = "NEXORA_DURABILITY_ENABLED";

    public static DurabilitySettings defaults() {
        return new DurabilitySettings(false);
    }

    public static DurabilitySettings fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configured = environment.get(ENVIRONMENT_VARIABLE);
        if (configured == null || configured.isBlank()) return defaults();
        return new DurabilitySettings(parseBoolean(configured));
    }

    private static boolean parseBoolean(String value) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException(
                    ENVIRONMENT_VARIABLE + " must be true/false, 1/0, yes/no or on/off");
        };
    }
}
