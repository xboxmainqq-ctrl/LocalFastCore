package ru.nexora.runtime;

import java.util.Objects;

/** Data-driven description of a NEXORA surface ecosystem. */
public record BiomeDefinition(
        int id,
        String key,
        String displayName,
        double targetTemperature,
        double targetMoisture,
        double targetElevation,
        double reliefMultiplier,
        double treeChance,
        BlockState surfaceBlock,
        BlockState subsurfaceBlock,
        BlockState trunkBlock,
        BlockState foliageBlock,
        int minTreeHeight,
        int maxTreeHeight
) {
    public BiomeDefinition {
        if (id < 0) throw new IllegalArgumentException("Biome ID must be non-negative");
        if (key == null || !key.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Biome key must contain lowercase letters, digits and underscores");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Biome display name must not be blank");
        }
        requireUnit(targetTemperature, "targetTemperature");
        requireUnit(targetMoisture, "targetMoisture");
        requireUnit(targetElevation, "targetElevation");
        if (!Double.isFinite(reliefMultiplier) || reliefMultiplier < 0.25 || reliefMultiplier > 2.5) {
            throw new IllegalArgumentException("reliefMultiplier must be between 0.25 and 2.5");
        }
        requireUnit(treeChance, "treeChance");
        Objects.requireNonNull(surfaceBlock, "surfaceBlock");
        Objects.requireNonNull(subsurfaceBlock, "subsurfaceBlock");
        Objects.requireNonNull(trunkBlock, "trunkBlock");
        Objects.requireNonNull(foliageBlock, "foliageBlock");
        if (minTreeHeight < 0 || maxTreeHeight < minTreeHeight || maxTreeHeight > 16) {
            throw new IllegalArgumentException("Tree heights must be between 0 and 16 and ordered");
        }
        if ((minTreeHeight == 0) != (maxTreeHeight == 0)) {
            throw new IllegalArgumentException("A treeless biome must use zero for both tree heights");
        }
    }

    private static void requireUnit(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
