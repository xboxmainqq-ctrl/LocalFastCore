package ru.nexora.runtime;

/** Immutable block coordinates in a world. */
public record BlockPosition(int x, int y, int z) {
    public ChunkPosition chunkPosition() {
        return new ChunkPosition(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }

    public int localX() {
        return Math.floorMod(x, 16);
    }

    public int localZ() {
        return Math.floorMod(z, 16);
    }
}
