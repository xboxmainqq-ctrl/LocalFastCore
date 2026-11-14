package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact 16x16x16 block section using a local palette.
 *
 * <p>Air is palette entry zero. One byte is used per block until the palette
 * exceeds 256 entries; the backing array is then widened to unsigned shorts.
 * This keeps common sections compact without imposing a fixed block-state cap
 * of 256.</p>
 */
final class ChunkSection {
    private static final int BLOCK_COUNT = 16 * 16 * 16;
    private final List<BlockState> palette = new ArrayList<>();
    private final Map<BlockState, Integer> paletteIndices = new HashMap<>();
    private byte[] byteIndices = new byte[BLOCK_COUNT];
    private short[] shortIndices;
    private int nonAirCount;

    ChunkSection() {
        palette.add(BlockState.AIR);
        paletteIndices.put(BlockState.AIR, 0);
    }

    BlockState get(int localX, int localY, int localZ) {
        int index = index(localX, localY, localZ);
        int paletteIndex = shortIndices == null
                ? Byte.toUnsignedInt(byteIndices[index])
                : Short.toUnsignedInt(shortIndices[index]);
        return palette.get(paletteIndex);
    }

    void set(int localX, int localY, int localZ, BlockState state) {
        int blockIndex = index(localX, localY, localZ);
        int oldPaletteIndex = shortIndices == null
                ? Byte.toUnsignedInt(byteIndices[blockIndex])
                : Short.toUnsignedInt(shortIndices[blockIndex]);
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
                if (existing == 256) {
                    widen();
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

    private void widen() {
        shortIndices = new short[BLOCK_COUNT];
        for (int i = 0; i < byteIndices.length; i++) {
            shortIndices[i] = (short) Byte.toUnsignedInt(byteIndices[i]);
        }
        byteIndices = null;
    }

    private void writeIndex(int blockIndex, int paletteIndex) {
        if (shortIndices == null) {
            byteIndices[blockIndex] = (byte) paletteIndex;
        } else {
            shortIndices[blockIndex] = (short) paletteIndex;
        }
    }

    private static int index(int x, int y, int z) {
        if ((x | y | z) < 0 || x > 15 || y > 15 || z > 15) {
            throw new IndexOutOfBoundsException("Local block coordinates must be in [0, 15]");
        }
        return (y << 8) | (z << 4) | x;
    }
}
