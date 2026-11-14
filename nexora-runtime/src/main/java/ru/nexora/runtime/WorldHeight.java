package ru.nexora.runtime;

/** Inclusive minimum and exclusive maximum Y coordinate for a world. */
public record WorldHeight(int minY, int maxYExclusive) {
    public WorldHeight {
        if (maxYExclusive <= minY) {
            throw new IllegalArgumentException("World height must contain at least one block layer");
        }
    }

    public boolean contains(int y) {
        return y >= minY && y < maxYExclusive;
    }

    public int height() {
        return Math.subtractExact(maxYExclusive, minY);
    }
}
