package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact 16x16x16 block section using a local palette.
 *
 * <p>Air is palette entry zero. Four bits are used per block while the
 * palette has at most 16 entries, widening to one byte and then unsigned
 * shorts only when required.</p>
 */
final class ChunkSection {
    private static final int BLOCK_COUNT = 16 * 16 * 16;
    private final List<BlockState> palette = new ArrayList<>();
    private final Map<BlockState, Integer> paletteIndices = new HashMap<>();
    private byte[] nibbleIndices = new byte[BLOCK_COUNT / 2];
    private byte[] byteIndices;
    private short[] shortIndices;
    private int nonAirCount;

    ChunkSection() {
        palette.add(BlockState.AIR);
        paletteIndices.put(BlockState.AIR, 0);
    }

    BlockState get(int localX, int localY, int localZ) {
        return palette.get(readIndex(index(localX, localY, localZ)));
    }

    void set(int localX, int localY, int localZ, BlockState state) {
        int blockIndex = index(localX, localY, localZ);
        int oldPaletteIndex = readIndex(blockIndex);
        if (oldPaletteIndex == 0 && state.isAir()) {
            return;
        }

        int newPaletteIndex;
        if (state.isAir()) {
            newPaletteIndex = 0;
        } else {
            Integer existing = paletteIndices.get(state);
            if (existing == null) {
                if (palette.size() >= 65_536) {
                    throw new IllegalStateException("Chunk section palette exceeds 65536 states");
                }
                existing = palette.size();
                palette.add(state);
                paletteIndices.put(state, existing);
                if (existing == 16) {
                    widenToBytes();
                } else if (existing == 256) {
                    widenToShorts();
                }
            }
            newPaletteIndex = existing;
        }

        if (oldPaletteIndex == newPaletteIndex) {
            return;
        }
        writeIndex(blockIndex, newPaletteIndex);
        if (oldPaletteIndex == 0) {
            nonAirCount++;
        } else if (newPaletteIndex == 0) {
            nonAirCount--;
        }
    }

    boolean isEmpty() {
        return nonAirCount == 0;
    }

    private int readIndex(int blockIndex) {
        if (shortIndices != null) {
            return Short.toUnsignedInt(shortIndices[blockIndex]);
        }
        if (byteIndices != null) {
            return Byte.toUnsignedInt(byteIndices[blockIndex]);
        }
        int packed = Byte.toUnsignedInt(nibbleIndices[blockIndex >>> 1]);
        return (blockIndex & 1) == 0 ? packed & 0x0F : packed >>> 4;
    }

    private void widenToBytes() {
        byte[] oldNibbles = nibbleIndices;
        byte[] widened = new byte[BLOCK_COUNT];
        for (int i = 0; i < BLOCK_COUNT; i++) {
            int packed = Byte.toUnsignedInt(oldNibbles[i >>> 1]);
            widened[i] = (byte) ((i & 1) == 0 ? packed & 0x0F : packed >>> 4);
        }
        byteIndices = widened;
        nibbleIndices = null;
    }

    private void widenToShorts() {
        byte[] oldBytes = byteIndices;
        short[] widened = new short[BLOCK_COUNT];
        for (int i = 0; i < BLOCK_COUNT; i++) {
            widened[i] = (short) Byte.toUnsignedInt(oldBytes[i]);
        }
        shortIndices = widened;
        byteIndices = null;
    }

    private void writeIndex(int blockIndex, int paletteIndex) {
        if (shortIndices != null) {
            shortIndices[blockIndex] = (short) paletteIndex;
        } else if (byteIndices != null) {
            byteIndices[blockIndex] = (byte) paletteIndex;
        } else {
            int byteIndex = blockIndex >>> 1;
            int old = Byte.toUnsignedInt(nibbleIndices[byteIndex]);
            nibbleIndices[byteIndex] = (byte) ((blockIndex & 1) == 0
                    ? (old & 0xF0) | paletteIndex
                    : (old & 0x0F) | (paletteIndex << 4));
        }
    }

    private static int index(int x, int y, int z) {
        if ((x | y | z) < 0 || x > 15 || y > 15 || z > 15) {
            throw new IndexOutOfBoundsException("Local block coordinates must be in [0, 15]");
        }
        return (y << 8) | (z << 4) | x;
    }
}
