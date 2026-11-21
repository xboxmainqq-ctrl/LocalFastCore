package ru.nexora.modules;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable identity, compatibility and permission declaration for a module. */
public record ModuleDescriptor(
        String id,
        String version,
        int apiVersion,
        Set<String> dependencies,
        Set<ModuleCapability> requiredCapabilities
) {
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9_.-]{1,63}");

    public ModuleDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid module id: " + id);
        }
        if (version.isBlank()) throw new IllegalArgumentException("Module version must not be blank");
        if (apiVersion < 1) throw new IllegalArgumentException("Module API version must be positive");
        dependencies = Set.copyOf(dependencies);
        requiredCapabilities = Set.copyOf(requiredCapabilities);
        if (dependencies.contains(id)) throw new IllegalArgumentException("Module cannot depend on itself: " + id);
    }
}
