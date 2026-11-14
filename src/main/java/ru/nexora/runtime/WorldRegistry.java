package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe registry of worlds owned by the standalone runtime.
 *
 * <p>World identifiers are captured at registration time. The registry keeps
 * insertion order so startup, diagnostics, and administrative listings remain
 * deterministic.</p>
 */
public final class WorldRegistry {
    private final Map<String, World> worlds = new LinkedHashMap<>();

    public synchronized void register(World world) {
        Objects.requireNonNull(world, "world");
        String id = Objects.requireNonNull(world.id(), "world.id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("World id must not be blank");
        }
        if (worlds.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate world id: " + id);
        }
        worlds.put(id, world);
    }

    public synchronized Optional<World> get(String id) {
        return Optional.ofNullable(worlds.get(normalizeId(id)));
    }

    public synchronized boolean unregister(String id) {
        return worlds.remove(normalizeId(id)) != null;
    }

    public synchronized List<World> worlds() {
        return List.copyOf(new ArrayList<>(worlds.values()));
    }

    public synchronized List<String> ids() {
        return List.copyOf(worlds.keySet());
    }

    public synchronized int size() {
        return worlds.size();
    }

    public synchronized boolean isEmpty() {
        return worlds.isEmpty();
    }

    private static String normalizeId(String id) {
        Objects.requireNonNull(id, "id");
        String normalized = id.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("World id must not be blank");
        }
        return normalized;
    }
}
