package ru.nexora.network;

import java.util.Objects;

/** Observer interest area used to scope network-visible gameplay work. */
public record InterestArea(int centerChunkX, int centerChunkZ, int radiusChunks) {
    public InterestArea {
        if (radiusChunks < 0) throw new IllegalArgumentException("radiusChunks < 0");
    }

    public boolean contains(int chunkX, int chunkZ) {
        return Math.abs(chunkX - centerChunkX) <= radiusChunks
                && Math.abs(chunkZ - centerChunkZ) <= radiusChunks;
    }
}
