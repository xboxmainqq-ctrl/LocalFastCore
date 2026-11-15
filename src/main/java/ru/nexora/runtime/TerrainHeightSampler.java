package ru.nexora.runtime;

/**
 * Seeded macro-landform field for NEXORA. It combines continental elevation,
 * warped rolling terrain, broad basins and masked ridged mountain systems.
 * Every sample uses absolute world coordinates, so chunk borders share the
 * same underlying terrain field without neighbor lookups.
 */
final class TerrainHeightSampler {
    private static final int MIN_HEIGHT = 8;

    private TerrainHeightSampler() {
    }

    static int sample(int x, int z, long seed, WorldHeight worldHeight, double reliefMultiplier) {
        if (worldHeight.height() < MIN_HEIGHT) {
            throw new IllegalArgumentException("Terrain sampling requires at least 8 vertical blocks");
        }
        if (!Double.isFinite(reliefMultiplier) || reliefMultiplier < 0.25 || reliefMultiplier > 2.5) {
            throw new IllegalArgumentException("reliefMultiplier must be between 0.25 and 2.5");
        }

        double warpX = noise(x, z, seed, 720, 0x4a73d9b1L) * 118.0;
        double warpZ = noise(x, z, seed, 720, 0x7c2f91e5L) * 118.0;
        int warpedX = (int) Math.round(x + warpX);
        int warpedZ = (int) Math.round(z + warpZ);

        double continent = noise(warpedX, warpedZ, seed, 1_650, 0x13c6a2f7L);
        double rolling = noise(warpedX, warpedZ, seed, 390, 0x5b8d31e9L);
        double detail = noise(warpedX, warpedZ, seed, 86, 0x2f14c8a3L);
        double basinField = noise(warpedX, warpedZ, seed, 940, 0x6e9a42d5L);
        double mountainField = noise(warpedX, warpedZ, seed, 510, 0x31f0b7c9L);
        double mountainMaskField = noise(warpedX, warpedZ, seed, 1_180, 0x72c41e8bL);

        double ridge = 1.0 - Math.abs(mountainField);
        ridge = ridge * ridge;
        double mountainMask = smoothstep(0.12, 0.72, (mountainMaskField + 1.0) * 0.5);
        double mountainMass = ridge * mountainMask;
        double basin = 1.0 - Math.abs(basinField);

        double rawHeight = 63.0
                + continent * 17.0
                + rolling * 8.5
                + detail * 1.8
                + mountainMass * 38.0 * reliefMultiplier
                - basin * 4.5;
        int bounded = (int) Math.round(rawHeight);
        return Math.max(worldHeight.minY() + 2,
                Math.min(worldHeight.maxYExclusive() - 2, bounded));
    }

    private static double noise(int x, int z, long seed, int scale, long salt) {
        double sampleX = (double) x / scale;
        double sampleZ = (double) z / scale;
        int x0 = (int) Math.floor(sampleX);
        int z0 = (int) Math.floor(sampleZ);
        double tx = fade(sampleX - x0);
        double tz = fade(sampleZ - z0);
        double a = lattice(seed, salt, x0, z0);
        double b = lattice(seed, salt, x0 + 1, z0);
        double c = lattice(seed, salt, x0, z0 + 1);
        double d = lattice(seed, salt, x0 + 1, z0 + 1);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
    }

    private static double lattice(long seed, long salt, int x, int z) {
        long value = seed ^ salt ^ ((long) x * 0x9e3779b97f4a7c15L)
                ^ ((long) z * 0xc2b2ae3d27d4eb4fL);
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return ((value >>> 11) * 0x1.0p-52) - 1.0;
    }

    private static double fade(double value) {
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double start, double end, double amount) {
        return start + (end - start) * amount;
    }
}
