package ru.nexora.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validated metadata for an independently distributable NEXORA pack.
 *
 * <p>This class parses metadata only. It deliberately does not extract archives,
 * execute pack code, or grant capabilities; those operations require separate
 * policy and trust decisions.</p>
 */
public final class ResourcePackManifest {
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?");
    private static final Pattern CAPABILITY = Pattern.compile("[a-z][a-z0-9_.-]*");

    public enum Type { RESOURCE, CONTENT, THEME }

    public record Dependency(String id, String version) {
        public Dependency {
            id = validateId(id);
            version = validateVersion(version, "dependency version");
        }
    }

    private final String id;
    private final String version;
    private final String apiVersion;
    private final Type type;
    private final List<Dependency> dependencies;
    private final Set<String> capabilities;

    public ResourcePackManifest(String id, String version, String apiVersion, Type type,
                                List<Dependency> dependencies, Set<String> capabilities) {
        this.id = validateId(id);
        this.version = validateVersion(version, "version");
        this.apiVersion = validateVersion(apiVersion, "api_version");
        this.type = Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(capabilities, "capabilities");

        List<Dependency> dependencyCopy = new ArrayList<>();
        Set<String> dependencyIds = new LinkedHashSet<>();
        for (Dependency dependency : dependencies) {
            Dependency checked = Objects.requireNonNull(dependency, "dependency");
            if (checked.id().equals(this.id)) {
                throw new IllegalArgumentException("A pack cannot depend on itself: " + this.id);
            }
            if (!dependencyIds.add(checked.id())) {
                throw new IllegalArgumentException("Duplicate pack dependency: " + checked.id());
            }
            dependencyCopy.add(checked);
        }
        this.dependencies = List.copyOf(dependencyCopy);

        Set<String> capabilityCopy = new LinkedHashSet<>();
        for (String capability : capabilities) {
            if (capability == null || !CAPABILITY.matcher(capability).matches()) {
                throw new IllegalArgumentException("Invalid pack capability: " + capability);
            }
            if (!capabilityCopy.add(capability)) {
                throw new IllegalArgumentException("Duplicate pack capability: " + capability);
            }
        }
        this.capabilities = Collections.unmodifiableSet(capabilityCopy);
    }

    /** Parses a UTF-8 JSON manifest using the NEXORA pack metadata schema. */
    public static ResourcePackManifest parse(String json) {
        Objects.requireNonNull(json, "json");
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Pack manifest must be a JSON object");
            }
            String id = null, version = null, apiVersion = null, typeName = null;
            List<Dependency> dependencies = new ArrayList<>();
            Set<String> capabilities = new LinkedHashSet<>();
            Set<String> fields = new LinkedHashSet<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw new IllegalArgumentException("Expected a manifest field name");
                }
                String field = parser.currentName();
                if (!fields.add(field)) {
                    throw new IllegalArgumentException("Duplicate manifest field: " + field);
                }
                JsonToken value = parser.nextToken();
                switch (field) {
                    case "id" -> id = readString(parser, value, field);
                    case "version" -> version = readString(parser, value, field);
                    case "api_version" -> apiVersion = readString(parser, value, field);
                    case "type" -> typeName = readString(parser, value, field);
                    case "dependencies" -> readDependencies(parser, value, dependencies);
                    case "capabilities" -> readCapabilities(parser, value, capabilities);
                    default -> throw new IllegalArgumentException("Unknown manifest field: " + field);
                }
            }
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("Trailing data after pack manifest");
            }
            for (String required : List.of("id", "version", "api_version", "type")) {
                if (!fields.contains(required)) {
                    throw new IllegalArgumentException("Missing required manifest field: " + required);
                }
            }
            Type type;
            try {
                type = Type.valueOf(typeName.toUpperCase(java.util.Locale.ROOT));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Unknown pack type: " + typeName, invalid);
            }
            return new ResourcePackManifest(id, version, apiVersion, type, dependencies, capabilities);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("Malformed pack manifest JSON", malformed);
        }
    }

    private static void readDependencies(JsonParser parser, JsonToken token, List<Dependency> out)
            throws IOException {
        if (token != JsonToken.START_ARRAY) throw new IllegalArgumentException("dependencies must be an array");
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Each dependency must be an object");
            }
            String id = null, version = null;
            Set<String> fields = new LinkedHashSet<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw new IllegalArgumentException("Expected dependency field name");
                }
                String field = parser.currentName();
                if (!fields.add(field)) throw new IllegalArgumentException("Duplicate dependency field: " + field);
                JsonToken value = parser.nextToken();
                switch (field) {
                    case "id" -> id = readString(parser, value, "dependency id");
                    case "version" -> version = readString(parser, value, "dependency version");
                    default -> throw new IllegalArgumentException("Unknown dependency field: " + field);
                }
            }
            if (id == null || version == null) {
                throw new IllegalArgumentException("Dependency requires id and version");
            }
            out.add(new Dependency(id, version));
        }
    }

    private static void readCapabilities(JsonParser parser, JsonToken token, Set<String> out)
            throws IOException {
        if (token != JsonToken.START_ARRAY) throw new IllegalArgumentException("capabilities must be an array");
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw new IllegalArgumentException("Each capability must be a string");
            }
            if (!out.add(parser.getText())) {
                throw new IllegalArgumentException("Duplicate pack capability: " + parser.getText());
            }
        }
    }

    private static String readString(JsonParser parser, JsonToken token, String field) throws IOException {
        if (token != JsonToken.VALUE_STRING) throw new IllegalArgumentException(field + " must be a string");
        return parser.getText();
    }

    private static String validateId(String value) {
        Objects.requireNonNull(value, "id");
        if (!ID.matcher(value).matches()) throw new IllegalArgumentException("Invalid namespaced pack ID: " + value);
        return value;
    }

    private static String validateVersion(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!VERSION.matcher(value).matches()) throw new IllegalArgumentException("Invalid " + field + ": " + value);
        return value;
    }

    public String id() { return id; }
    public String version() { return version; }
    public String apiVersion() { return apiVersion; }
    public Type type() { return type; }
    public List<Dependency> dependencies() { return dependencies; }
    public Set<String> capabilities() { return capabilities; }
}
