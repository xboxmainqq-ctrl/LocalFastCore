package ru.nexora.runtime;

/**
 * Seeded macro-landform model for NEXORA RPG worlds.
 *
 * <p>The field separates continental shelves, eroded lowlands, foothills,
 * long mountain systems, narrow ridgelines and broad glacial/river valleys.
 * Domain warping breaks up obvious grid-like noise patterns. Every value is
 * sampled in absolute world coordinates, so chunk borders are seamless.</p>
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

        // Large, gently warped continental plates establish coastlines and inland regions.
        double warpX = noise(x, z, seed, 1_050, 0x4a73d9b1L) * 210.0
                + noise(x, z, seed, 390, 0x2e91c7a5L) * 34.0;
        double warpZ = noise(x, z, seed, 1_050, 0x7c2f91e5L) * 210.0
                + noise(x, z, seed, 390, 0x6b38d2f1L) * 34.0;
        double sampleX = x + warpX;
        double sampleZ = z + warpZ;

        double continentalness = noise(sampleX, sampleZ, seed, 2_400, 0x13c6a2f7L);
        double shelf = smoothstep(-0.62, 0.16, continentalness);
        double inlandMass = smoothstep(-0.08, 0.68, continentalness);

        // Low-frequency rolling fields form plains, basins and broad uplands.
        double rolling = noise(sampleX, sampleZ, seed, 520, 0x5b8d31e9L);
        double hills = noise(sampleX, sampleZ, seed, 210, 0x2f14c8a3L);
        double microRelief = noise(sampleX, sampleZ, seed, 72, 0x19d4e6b3L);
        double erosion = (noise(sampleX, sampleZ, seed, 880, 0x6e9a42d5L) + 1.0) * 0.5;

        // Mountain belts are broad regions; a separate ridged field cuts peaks and spines into them.
        double mountainBelt = (noise(sampleX, sampleZ, seed, 1_420, 0x31f0b7c9L) + 1.0) * 0.5;
        double ridgeField = noise(sampleX, sampleZ, seed, 470, 0x72c41e8bL);
        double secondaryRidge = noise(sampleX, sampleZ, seed, 185, 0x54a8d3e7L);
        double ridge = 1.0 - Math.abs(ridgeField);
        ridge = ridge * ridge * (0.72 + 0.28 * (1.0 - Math.abs(secondaryRidge)));
        double mountainMask = smoothstep(0.66, 0.94, mountainBelt);
        double mountainMass = ridge * mountainMask;

        // Erosion carves wide valleys between ridges without making artificial flat plateaus.
        double valleyField = 1.0 - Math.abs(noise(sampleX, sampleZ, seed, 330, 0x6a1f9c43L));
        double valleyMask = smoothstep(0.58, 0.94, valleyField);
        double valleyDepth = valleyMask * (3.0 + erosion * 5.0);

        double foothills = mountainMask * (1.0 - ridge) * 15.0;
        double continentalBase = 25.0 + shelf * 39.0 + inlandMass * 10.0;
        double lowlandRelief = rolling * 7.5 + hills * 3.2 + microRelief * 1.15;
        double mountainRelief = mountainMass * (48.0 + erosion * 24.0) * reliefMultiplier;
        double rawHeight = continentalBase + lowlandRelief + foothills * reliefMultiplier
                + mountainRelief - valleyDepth;

        // A small, deterministic coastal terrace softens the transition into the sea.
        double coast = smoothstep(0.12, 0.48, continentalness);
        rawHeight += coast * (1.0 - coast) * 3.5;
        int bounded = (int) Math.round(rawHeight);
        return Math.max(worldHeight.minY() + 2,
                Math.min(worldHeight.maxYExclusive() - 2, bounded));
    }

    private static double noise(double x, double z, long seed, int scale, long salt) {
        double sampleX = x / scale;
        double sampleZ = z / scale;
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
