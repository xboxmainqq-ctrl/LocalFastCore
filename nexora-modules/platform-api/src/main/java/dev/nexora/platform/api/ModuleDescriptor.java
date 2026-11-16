package dev.nexora.platform.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable identity and compatibility metadata for a NEXORA module.
 */
public record ModuleDescriptor(
        String id,
        String name,
        String version,
        String apiVersion,
        List<String> dependencies
) {
    private static final Pattern ID_PATTERN =
            Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");

    public ModuleDescriptor {
        id = requireText(id, "id");
        name = requireText(name, "name");
        version = requireText(version, "version");
        apiVersion = requireText(apiVersion, "apiVersion");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid module id: " + id);
        }

        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        Set<String> uniqueDependencies = new HashSet<>();
        for (String dependency : dependencies) {
            String value = requireText(dependency, "dependency");
            if (!ID_PATTERN.matcher(value).matches()) {
                throw new IllegalArgumentException("Invalid dependency id: " + value);
            }
            if (id.equals(value)) {
                throw new IllegalArgumentException("A module cannot depend on itself: " + id);
            }
            if (!uniqueDependencies.add(value)) {
                throw new IllegalArgumentException("Duplicate dependency: " + value);
            }
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }
}
