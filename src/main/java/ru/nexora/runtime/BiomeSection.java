package ru.nexora.runtime;

import java.util.Arrays;

/** Compact block-resolution biome data for one 16x16x16 world section. */
final class BiomeSection {
    static final int DEFAULT_BIOME_ID = 1; // Bedrock plains in protocol 1.26.60.
    static final int CELL_COUNT = 4096;
    private int[] values;
    private int nonDefaultCount;

    int get(int localX, int localY, int localZ) {
        return values == null ? DEFAULT_BIOME_ID : values[index(localX, localY, localZ)];
    }

    void set(int localX, int localY, int localZ, int biomeId) {
        if (biomeId < 0) {
            throw new IllegalArgumentException("Biome ID must be non-negative");
        }
        int index = index(localX, localY, localZ);
        if (values == null) {
            if (biomeId == DEFAULT_BIOME_ID) {
                return;
            }
            values = new int[CELL_COUNT];
            Arrays.fill(values, DEFAULT_BIOME_ID);
        }

        int previous = values[index];
        if (previous == biomeId) {
            return;
        }
        values[index] = biomeId;
        if (previous == DEFAULT_BIOME_ID) {
            nonDefaultCount++;
        } else if (biomeId == DEFAULT_BIOME_ID) {
            nonDefaultCount--;
            if (nonDefaultCount == 0) {
                values = null;
            }
        }
    }

    boolean isDefault() {
        return values == null;
    }

    /** Returns a detached array in Bedrock XZY order (Y is the fastest axis). */
    int[] snapshotXzy() {
        if (values == null) {
            int[] defaults = new int[CELL_COUNT];
            Arrays.fill(defaults, DEFAULT_BIOME_ID);
            return defaults;
        }
        return values.clone();
    }

    private static int index(int x, int y, int z) {
        if ((x | y | z) < 0 || x > 15 || y > 15 || z > 15) {
            throw new IndexOutOfBoundsException("Local biome coordinates must be in [0, 15]");
        }
        return (x * 16 + z) * 16 + y;
    }
}
