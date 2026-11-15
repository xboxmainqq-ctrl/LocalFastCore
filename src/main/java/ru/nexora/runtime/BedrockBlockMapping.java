package ru.nexora.runtime;
import java.util.Objects;

/** Explicit mapping from a world block identifier to a Bedrock block identifier. */
public record BedrockBlockMapping(String worldIdentifier, String bedrockIdentifier, boolean requiresResourcePack) {
    public BedrockBlockMapping {
        worldIdentifier = validate(worldIdentifier, "worldIdentifier");
        bedrockIdentifier = validate(bedrockIdentifier, "bedrockIdentifier");
    }
    private static String validate(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException(field + " must be a namespaced identifier: " + value);
        }
        return value;
    }
}
