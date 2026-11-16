package ru.nexora.runtime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable world-name to Bedrock-name lookup. Bedrock runtime palette IDs are
 * deliberately excluded because they are negotiated per client and session.
 */
public final class BedrockBlockCompatibilityRegistry {
    private final Map<String, BedrockBlockMapping> mappings;

    public BedrockBlockCompatibilityRegistry(Collection<BedrockBlockMapping> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        Map<String, BedrockBlockMapping> copy = new LinkedHashMap<>();
        for (BedrockBlockMapping mapping : definitions) {
            Objects.requireNonNull(mapping, "mapping");
            if (copy.putIfAbsent(mapping.worldIdentifier(), mapping) != null) {
                throw new IllegalArgumentException("Duplicate world block mapping: " + mapping.worldIdentifier());
            }
        }
        mappings = Map.copyOf(copy);
    }

    /** Common blocks currently emitted by the NEXORA overworld generator. */
    public static BedrockBlockCompatibilityRegistry builtIn() {
        return new BedrockBlockCompatibilityRegistry(List.of(
                "minecraft:air", "minecraft:bedrock", "minecraft:stone", "minecraft:cobblestone", "minecraft:dirt", "minecraft:coarse_dirt",
                "minecraft:grass_block", "minecraft:podzol", "minecraft:sand", "minecraft:mud",
                "minecraft:snow_block", "minecraft:water", "minecraft:oak_log", "minecraft:oak_leaves",
                "minecraft:birch_log", "minecraft:birch_leaves", "minecraft:dark_oak_log",
                "minecraft:dark_oak_leaves", "minecraft:spruce_log", "minecraft:spruce_leaves",
                "minecraft:dead_bush", "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:dark_oak_log").stream().map(BedrockBlockCompatibilityRegistry::vanilla).toList());
    }

    private static BedrockBlockMapping vanilla(String identifier) {
        return new BedrockBlockMapping(identifier, identifier, false);
    }

    public Optional<BedrockBlockMapping> find(String worldIdentifier) {
        Objects.requireNonNull(worldIdentifier, "worldIdentifier");
        return Optional.ofNullable(mappings.get(worldIdentifier));
    }

    public BedrockBlockMapping require(String worldIdentifier) {
        return find(worldIdentifier).orElseThrow(() ->
                new IllegalArgumentException("No Bedrock block mapping registered for: " + worldIdentifier));
    }

    /**
     * Creates the adapter consumed by the existing Bedrock palette serializer.
     * NEXORA identifiers are translated before the version-specific runtime ID
     * lookup; block properties are preserved and must be valid for the target state.
     */
    public BedrockRuntimeBlockRegistry adapt(BedrockRuntimeBlockRegistry runtimeRegistry) {
        Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        return state -> {
            Objects.requireNonNull(state, "state");
            BedrockBlockMapping mapping = require(state.identifier());
            BlockState bedrockState = new BlockState(mapping.bedrockIdentifier(), state.properties());
            return runtimeRegistry.runtimeId(bedrockState);
        };
    }

    public boolean supports(String worldIdentifier) { return mappings.containsKey(worldIdentifier); }
    public int size() { return mappings.size(); }
    public Collection<BedrockBlockMapping> mappings() { return mappings.values(); }
}
