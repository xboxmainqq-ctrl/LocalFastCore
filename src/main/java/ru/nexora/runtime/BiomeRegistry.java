package ru.nexora.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable registry of NEXORA biome definitions and climate-based selection. */
public final class BiomeRegistry {
    public static final int MISTWOOD_ID = 1001;
    public static final int GLASSGRASS_STEPPE_ID = 1002;
    public static final int EMBERFEN_ID = 1003;
    public static final int FROSTVEIL_ID = 1004;
    public static final int SKYGLASS_HIGHLANDS_ID = 1005;
    public static final int SALTWIND_BASIN_ID = 1006;
    public static final int BLOOMVEIL_GROVE_ID = 1007;
    public static final int ASHEN_WASTES_ID = 1008;
    public static final int MOONFROST_TUNDRA_ID = 1009;

    private final Map<Integer, BiomeDefinition> byId;
    private final Map<String, BiomeDefinition> byKey;

    public BiomeRegistry(Collection<BiomeDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        Map<Integer, BiomeDefinition> ids = new LinkedHashMap<>();
        Map<String, BiomeDefinition> keys = new LinkedHashMap<>();
        for (BiomeDefinition definition : definitions) {
            Objects.requireNonNull(definition, "biome definition");
            if (ids.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate biome ID: " + definition.id());
            }
            if (keys.putIfAbsent(definition.key(), definition) != null) {
                throw new IllegalArgumentException("Duplicate biome key: " + definition.key());
            }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Biome registry must not be empty");
        byId = Map.copyOf(ids);
        byKey = Map.copyOf(keys);
    }

    public static BiomeRegistry builtIn() {
        return new BiomeRegistry(java.util.List.of(
                new BiomeDefinition(MISTWOOD_ID, "mistwood", "Mistwood",
                        0.58, 0.80, 0.43, 1.05, 0.045,
                        state("minecraft:podzol"), state("minecraft:dirt"),
                        state("minecraft:oak_log"), state("minecraft:oak_leaves"), 4, 6),
                new BiomeDefinition(GLASSGRASS_STEPPE_ID, "glassgrass_steppe", "Glassgrass Steppe",
                        0.72, 0.32, 0.40, 0.85, 0.004,
                        state("minecraft:grass_block"), state("minecraft:dirt"),
                        state("minecraft:birch_log"), state("minecraft:birch_leaves"), 3, 4),
                new BiomeDefinition(EMBERFEN_ID, "emberfen", "Emberfen",
                        0.86, 0.88, 0.27, 0.62, 0.020,
                        state("minecraft:mud"), state("minecraft:mud"),
                        state("minecraft:dark_oak_log"), state("minecraft:dark_oak_leaves"), 3, 5),
                new BiomeDefinition(FROSTVEIL_ID, "frostveil", "Frostveil",
                        0.15, 0.57, 0.57, 1.18, 0.012,
                        state("minecraft:snow_block"), state("minecraft:dirt"),
                        state("minecraft:spruce_log"), state("minecraft:spruce_leaves"), 4, 7),
                new BiomeDefinition(SKYGLASS_HIGHLANDS_ID, "skyglass_highlands", "Skyglass Highlands",
                        0.31, 0.43, 0.82, 1.55, 0.014,
                        state("minecraft:stone"), state("minecraft:stone"),
                        state("minecraft:spruce_log"), state("minecraft:spruce_leaves"), 3, 5),
                new BiomeDefinition(SALTWIND_BASIN_ID, "saltwind_basin", "Saltwind Basin",
                        0.90, 0.12, 0.34, 0.72, 0.0,
                        state("minecraft:sand"), state("minecraft:sand"),
                        state("minecraft:dead_bush"), state("minecraft:dead_bush"), 0, 0),
                new BiomeDefinition(BLOOMVEIL_GROVE_ID, "bloomveil_grove", "Bloomveil Grove",
                        0.64, 0.97, 0.24, 0.82, 0.065,
                        state("minecraft:moss_block"), state("minecraft:rooted_dirt"),
                        state("minecraft:cherry_log"), state("minecraft:cherry_leaves"), 5, 8),
                new BiomeDefinition(ASHEN_WASTES_ID, "ashen_wastes", "Ashen Wastes",
                        0.97, 0.25, 0.57, 1.22, 0.0,
                        state("minecraft:basalt"), state("minecraft:tuff"),
                        state("minecraft:dead_bush"), state("minecraft:dead_bush"), 0, 0),
                new BiomeDefinition(MOONFROST_TUNDRA_ID, "moonfrost_tundra", "Moonfrost Tundra",
                        0.03, 0.85, 0.85, 1.42, 0.008,
                        state("minecraft:packed_ice"), state("minecraft:snow_block"),
                        state("minecraft:spruce_log"), state("minecraft:spruce_leaves"), 3, 5)
        ));
    }

    public BiomeDefinition get(int id) {
        return byId.get(id);
    }

    public BiomeDefinition get(String key) {
        return byKey.get(key);
    }

    public Collection<BiomeDefinition> definitions() {
        return byId.values();
    }

    public BiomeDefinition select(ClimateSample climate) {
        Objects.requireNonNull(climate, "climate");
        BiomeDefinition best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (BiomeDefinition candidate : byId.values()) {
            double temperature = climate.temperature() - candidate.targetTemperature();
            double moisture = climate.moisture() - candidate.targetMoisture();
            double elevation = climate.elevation() - candidate.targetElevation();
            double score = temperature * temperature * 1.35
                    + moisture * moisture
                    + elevation * elevation * 1.15;
            if (score < bestScore || (score == bestScore && (best == null || candidate.id() < best.id()))) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Smoothly blends the terrain influence of nearby biome climates. Biome
     * labels remain discrete, but the underlying landform does not jump at
     * biome borders.
     */
    public double blendedReliefMultiplier(ClimateSample climate) {
        Objects.requireNonNull(climate, "climate");
        double weightedRelief = 0.0;
        double totalWeight = 0.0;
        for (BiomeDefinition candidate : byId.values().stream()
                .sorted(java.util.Comparator.comparingInt(BiomeDefinition::id)).toList()) {
            double temperature = climate.temperature() - candidate.targetTemperature();
            double moisture = climate.moisture() - candidate.targetMoisture();
            double elevation = climate.elevation() - candidate.targetElevation();
            double score = temperature * temperature * 1.35
                    + moisture * moisture
                    + elevation * elevation * 1.15;
            double weight = 1.0 / ((score + 0.035) * (score + 0.035));
            weightedRelief += candidate.reliefMultiplier() * weight;
            totalWeight += weight;
        }
        return weightedRelief / totalWeight;
    }

    private static BlockState state(String identifier) {
        return new BlockState(identifier);
    }
}
