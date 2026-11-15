package ru.nexora.runtime;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Deterministic registry for content definitions such as blocks, items and entities.
 *
 * <p>Identifiers are stable namespaced keys (for example {@code nexora:marble}).
 * Numeric protocol/runtime IDs are deliberately kept outside this registry.</p>
 *
 * @param <T> immutable or otherwise safely published content definition
 */
public final class NamespacedRegistry<T> {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private final String contentKind;
    private final Map<String, T> entries = new TreeMap<>();
    private boolean frozen;

    public NamespacedRegistry(String contentKind) {
        Objects.requireNonNull(contentKind, "contentKind");
        String normalized = contentKind.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Content kind must not be blank");
        }
        this.contentKind = normalized;
    }

    /**
     * Registers a definition. Replacing an existing ID is intentionally not
     * supported here; pack override resolution must happen before registration.
     */
    public synchronized void register(String identifier, T definition) {
        ensureMutable();
        String id = validateIdentifier(identifier);
        Objects.requireNonNull(definition, "definition");
        if (entries.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate " + contentKind + " identifier: " + id);
        }
        entries.put(id, definition);
    }

    public synchronized Optional<T> find(String identifier) {
        return Optional.ofNullable(entries.get(validateIdentifier(identifier)));
    }

    public synchronized T require(String identifier) {
        String id = validateIdentifier(identifier);
        T definition = entries.get(id);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown " + contentKind + " identifier: " + id);
        }
        return definition;
    }

    /** Prevents further registration and returns an immutable, sorted snapshot. */
    public synchronized Map<String, T> freeze() {
        frozen = true;
        return Collections.unmodifiableMap(new TreeMap<>(entries));
    }

    /** Returns an immutable, sorted snapshot without freezing registration. */
    public synchronized Map<String, T> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(entries));
    }

    public synchronized boolean isFrozen() {
        return frozen;
    }

    public synchronized int size() {
        return entries.size();
    }

    public String contentKind() {
        return contentKind;
    }

    private void ensureMutable() {
        if (frozen) {
            throw new IllegalStateException(contentKind + " registry is frozen");
        }
    }

    private String validateIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    contentKind + " identifier must be namespaced: " + identifier);
        }
        return identifier;
    }
}
