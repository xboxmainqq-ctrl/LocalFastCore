package ru.nexora.runtime;

import java.util.Arrays;

/**
 * Compact biome storage for one 16x16x16 section. Uniform vertical columns
 * are stored as 256 values; per-block overrides expand to the full palette.
 */
final class BiomeSection {
    static final int DEFAULT_BIOME_ID = 1; // Bedrock plains in protocol 1.26.60.
    static final int CELL_COUNT = 4096;
    private int[] values;
    private int[] columns;

    int get(int localX, int localY, int localZ) {
        int index = index(localX, localY, localZ);
        if (values != null) return values[index];
        return columns == null ? DEFAULT_BIOME_ID : columns[columnIndex(localX, localZ)];
    }

    void set(int localX, int localY, int localZ, int biomeId) {
        requireBiomeId(biomeId);
        int index = index(localX, localY, localZ);
        if (values == null) {
            int column = columnIndex(localX, localZ);
            int current = columns == null ? DEFAULT_BIOME_ID : columns[column];
            if (current == biomeId) return;
            expandColumns();
        }
        values[index] = biomeId;
        compactIfDefault();
    }

    void setColumn(int localX, int localZ, int minLocalY, int maxLocalYExclusive, int biomeId) {
        requireBiomeId(biomeId);
        if (minLocalY < 0 || maxLocalYExclusive > 16 || minLocalY > maxLocalYExclusive) {
            throw new IndexOutOfBoundsException("Local biome Y range must be within [0, 16]");
        }
        int column = columnIndex(localX, localZ);
        if (values != null) {
            for (int y = minLocalY; y < maxLocalYExclusive; y++) {
                values[index(localX, y, localZ)] = biomeId;
            }
            compactIfDefault();
            return;
        }
        if (columns == null) {
            if (biomeId == DEFAULT_BIOME_ID) return;
            columns = new int[256];
            Arrays.fill(columns, DEFAULT_BIOME_ID);
        }
        columns[column] = biomeId;
        if (biomeId == DEFAULT_BIOME_ID && allDefault(columns)) columns = null;
    }

    boolean isDefault() {
        return values == null && columns == null;
    }

    /** Returns a detached array in Bedrock XZY order (Y is the fastest axis). */
    int[] snapshotXzy() {
        if (values != null) return values.clone();
        int[] snapshot = new int[CELL_COUNT];
        if (columns == null) {
            Arrays.fill(snapshot, DEFAULT_BIOME_ID);
            return snapshot;
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int biomeId = columns[columnIndex(x, z)];
                for (int y = 0; y < 16; y++) snapshot[index(x, y, z)] = biomeId;
            }
        }
        return snapshot;
    }

    private void expandColumns() {
        values = new int[CELL_COUNT];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int biomeId = columns == null ? DEFAULT_BIOME_ID : columns[columnIndex(x, z)];
                for (int y = 0; y < 16; y++) values[index(x, y, z)] = biomeId;
            }
        }
    }

    private void compactIfDefault() {
        if (values == null) return;
        for (int value : values) {
            if (value != DEFAULT_BIOME_ID) return;
        }
        values = null;
        columns = null;
    }

    private static boolean allDefault(int[] values) {
        for (int value : values) if (value != DEFAULT_BIOME_ID) return false;
        return true;
    }

    private static void requireBiomeId(int biomeId) {
        if (biomeId < 0) throw new IllegalArgumentException("Biome ID must be non-negative");
    }

    private static int columnIndex(int x, int z) {
        if (x < 0 || x > 15 || z < 0 || z > 15) {
            throw new IndexOutOfBoundsException("Local biome coordinates must be in [0, 15]");
        }
        return x * 16 + z;
    }

    private static int index(int x, int y, int z) {
        if ((x | y | z) < 0 || x > 15 || y > 15 || z > 15) {
            throw new IndexOutOfBoundsException("Local biome coordinates must be in [0, 15]");
        }
        return (x * 16 + z) * 16 + y;
    }
}
