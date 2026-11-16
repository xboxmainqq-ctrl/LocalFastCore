package ru.nexora.world;

import java.util.Arrays;
import java.util.Objects;

/** Sparse-at-chunk-level 16x16x16 block section; local storage follows Bedrock's Y-fast order. */
public final class ChunkSection {
    public static final int EDGE = 16;
    public static final int VOLUME = EDGE * EDGE * EDGE;

    private final byte[] blocks = new byte[VOLUME];

    public BlockType get(int localX, int localY, int localZ) {
        return BlockType.byInternalId(Byte.toUnsignedInt(blocks[index(localX, localY, localZ)]));
    }

    public void set(int localX, int localY, int localZ, BlockType type) {
        blocks[index(localX, localY, localZ)] =
                (byte) Objects.requireNonNull(type, "type").internalId();
    }

    public boolean isEmpty() {
        for (byte block : blocks) {
            if (block != 0) return false;
        }
        return true;
    }

    public byte[] copyRawBlocks() {
        return blocks.clone();
    }

    public void loadRawBlocks(byte[] raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.length != VOLUME) throw new IllegalArgumentException("A chunk section must contain 4096 blocks");
        for (byte value : raw) BlockType.byInternalId(Byte.toUnsignedInt(value));
        System.arraycopy(raw, 0, blocks, 0, VOLUME);
    }

    public void clear() {
        Arrays.fill(blocks, (byte) 0);
    }

    private static int index(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= EDGE || y >= EDGE || z >= EDGE) {
            throw new IndexOutOfBoundsException("Local block coordinates must be in 0..15");
        }
        return (x << 8) | (z << 4) | y;
    }
}
