package ru.nexora.runtime;

import java.util.Arrays;

/** Compact block-resolution biome data for one 16x16x16 world section. */
final class BiomeSection {
    static final int DEFAULT_BIOME_ID = 1; // Bedrock plains in protocol 1.26.60.
    static final int CELL_COUNT = 4096;
    private int uniformBiomeId = DEFAULT_BIOME_ID;
    private int[] values;

    int get(int localX, int localY, int localZ) {
        return values == null ? uniformBiomeId : values[index(localX, localY, localZ)];
    }

    void set(int localX, int localY, int localZ, int biomeId) {
        if (biomeId < 0) {
            throw new IllegalArgumentException("Biome ID must be non-negative");
        }
        int index = index(localX, localY, localZ);
        if (values == null) {
            if (biomeId == uniformBiomeId) {
                return;
            }
            values = new int[CELL_COUNT];
            Arrays.fill(values, uniformBiomeId);
        }
        values[index] = biomeId;
        int first = values[0];
        for (int i = 1; i < values.length; i++) {
            if (values[i] != first) {
                return;
            }
        }
        uniformBiomeId = first;
        values = null;
    }

    boolean isDefault() {
        return values == null && uniformBiomeId == DEFAULT_BIOME_ID;
    }

    /** Returns a detached array in Bedrock XZY order (Y is the fastest axis). */
    int[] snapshotXzy() {
        int[] snapshot = new int[CELL_COUNT];
        if (values == null) {
            Arrays.fill(snapshot, uniformBiomeId);
            return snapshot;
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    snapshot[index(x, y, z)] = values[index(x, y, z)];
                }
            }
        }
        return snapshot;
    }

    private static int index(int x, int y, int z) {
        if ((x | y | z) < 0 || x > 15 || y > 15 || z > 15) {
            throw new IndexOutOfBoundsException("Local biome coordinates must be in [0, 15]");
        }
        return (x * 16 + z) * 16 + y;
    }
}
