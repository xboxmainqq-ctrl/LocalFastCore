package ru.nexora.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Immutable block type and its protocol-independent state properties. */
public record BlockState(String identifier, Map<String, String> properties) {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public static final BlockState AIR = new BlockState("minecraft:air", Map.of());

    public BlockState {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(properties, "properties");
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Block identifier must be namespaced: " + identifier);
        }
        TreeMap<String, String> copy = new TreeMap<>();
        properties.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                throw new IllegalArgumentException("Block property keys and values must not be blank");
            }
            copy.put(key, value);
        });
        properties = Map.copyOf(copy);
    }

    public BlockState(String identifier) {
        this(identifier, Map.of());
    }

    public boolean isAir() {
        return identifier.equals("minecraft:air")
                || identifier.equals("minecraft:cave_air")
                || identifier.equals("minecraft:void_air");
    }
}
