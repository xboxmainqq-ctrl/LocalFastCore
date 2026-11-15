package ru.nexora.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Registry for built-in and server-extension chunk generators. */
public final class WorldGeneratorRegistry {
    private final Map<String, Supplier<? extends ChunkGenerator>> factories = new LinkedHashMap<>();

    public WorldGeneratorRegistry register(String id, Supplier<? extends ChunkGenerator> factory) {
        String normalized = normalize(id);
        Objects.requireNonNull(factory, "factory");
        if (factories.putIfAbsent(normalized, factory) != null) {
            throw new IllegalArgumentException("Duplicate world generator: " + normalized);
        }
        return this;
    }

    public ChunkGenerator create(String id) {
        Supplier<? extends ChunkGenerator> factory = factories.get(normalize(id));
        if (factory == null) {
            throw new IllegalArgumentException("Unknown world generator '" + id
                    + "'. Available generators: " + String.join(", ", factories.keySet()));
        }
        return Objects.requireNonNull(factory.get(),
                "Generator factory returned null for " + id);
    }

    public boolean contains(String id) {
        return factories.containsKey(normalize(id));
    }

    public static WorldGeneratorRegistry builtIn() {
        return new WorldGeneratorRegistry()
                .register("overworld", OverworldChunkGenerator::new);
    }

    private static String normalize(String id) {
        Objects.requireNonNull(id, "generator id");
        String normalized = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid generator id: " + id);
        }
        return normalized;
    }
}
