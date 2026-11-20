package ru.nexora.runtime;

/** Normalized climate and terrain inputs used to select a surface biome. */
public record ClimateSample(double temperature, double moisture, double elevation) {
    public ClimateSample {
        requireUnit(temperature, "temperature");
        requireUnit(moisture, "moisture");
        requireUnit(elevation, "elevation");
    }

    private static void requireUnit(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
