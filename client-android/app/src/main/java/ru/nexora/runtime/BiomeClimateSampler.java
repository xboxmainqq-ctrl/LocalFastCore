package ru.nexora.runtime;

/**
 * Seeded climate field. Large-scale temperature and moisture fields are sampled
 * in absolute world coordinates so adjacent chunks agree at their borders.
 */
public final class BiomeClimateSampler {
    private BiomeClimateSampler() {
    }

    public static ClimateSample sample(int x, int z, long seed, int surfaceY) {
        double thermalRegion = noise(x, z, seed, 768, 0x2f6e2b1L);
        double thermalDetail = noise(x, z, seed, 256, 0x71c3a59L);
        double moistureRegion = noise(x, z, seed, 640, 0x5a17d39L);
        double moistureDetail = noise(x, z, seed, 224, 0x3c79ac4L);

        double elevation = clamp01((surfaceY - 38.0) / 65.0);
        double temperature = 0.50 + thermalRegion * 0.34 + thermalDetail * 0.12
                - Math.max(0.0, elevation - 0.48) * 0.48;
        double moisture = 0.50 + moistureRegion * 0.34 + moistureDetail * 0.16;
        return new ClimateSample(clamp01(temperature), clamp01(moisture), elevation);
    }

    private static double noise(int x, int z, long seed, int scale, long salt) {
        double sx = (double) x / scale;
        double sz = (double) z / scale;
        int x0 = (int) Math.floor(sx);
        int z0 = (int) Math.floor(sz);
        double tx = fade(sx - x0);
        double tz = fade(sz - z0);
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

    private static double lerp(double a, double b, double amount) {
        return a + (b - a) * amount;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
