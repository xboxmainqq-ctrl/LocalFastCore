package ru.nexora.core.rpg;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe registry for class modules.
 * Duplicate IDs are rejected so a plugin cannot silently replace another
 * module's class definition.
 */
public final class RpgClassRegistry {
    private final ConcurrentMap<String, RpgClassDefinition> classes = new ConcurrentHashMap<>();

    public void register(RpgClassDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        RpgClassDefinition previous = classes.putIfAbsent(definition.id(), definition);
        if (previous != null) {
            throw new IllegalStateException("RPG class is already registered: " + definition.id());
        }
    }

    public Optional<RpgClassDefinition> find(String classId) {
        if (classId == null || classId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(classes.get(classId.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    public RpgClassDefinition require(String classId) {
        return find(classId).orElseThrow(() ->
                new IllegalArgumentException("Unknown RPG class: " + classId));
    }

    public List<RpgClassDefinition> all() {
        return classes.values().stream()
                .sorted(Comparator.comparing(RpgClassDefinition::displayName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public int size() {
        return classes.size();
    }
}
