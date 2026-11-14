package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bedrock network paletted block storage for a 16x16x16 section.
 *
 * <p>Network palettes contain global runtime IDs, not NEXORA block names.
 * The caller must provide the exact version-matched runtime registry.</p>
 */
public final class BedrockPalettedStorageCodec {
    private static final int BLOCK_COUNT = 4096;
    private static final int[] WIDTHS = {1, 2, 3, 4, 5, 6, 8, 16};

    private BedrockPalettedStorageCodec() {
    }

    public static ByteBuffer encode(ChunkSection section, BedrockRuntimeBlockRegistry registry) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(registry, "registry");

        int[] runtimeIds = new int[BLOCK_COUNT];
        Map<Integer, Integer> paletteIndices = new LinkedHashMap<>();
        Map<Integer, BlockState> statesByRuntimeId = new LinkedHashMap<>();
        List<Integer> palette = new ArrayList<>();

        // Bedrock's network storage uses XZY order: Y is the fastest axis.
        int cursor = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    BlockState state = section.get(x, y, z);
                    int runtimeId = registry.runtimeId(state);
                    BlockState previousState = statesByRuntimeId.putIfAbsent(runtimeId, state);
                    if (previousState != null && !previousState.equals(state)) {
                        throw new IllegalArgumentException(
                                "Block runtime ID collision between " + previousState + " and " + state);
                    }
                    runtimeIds[cursor++] = runtimeId;
                    if (!paletteIndices.containsKey(runtimeId)) {
                        paletteIndices.put(runtimeId, palette.size());
                        palette.add(runtimeId);
                    }
                }
            }
        }

        int bits = bitsForPalette(palette.size());
        int wordCount = bits == 0 ? 0 : (BLOCK_COUNT + (32 / bits) - 1) / (32 / bits);
        ByteBuffer out = ByteBuffer.allocate(1 + wordCount * Integer.BYTES + 5 + palette.size() * 5);
        out.put((byte) ((bits << 1) | 1)); // runtime/network palette flag

        if (bits != 0) {
            int blocksPerWord = 32 / bits;
            int mask = (1 << bits) - 1;
            for (int wordIndex = 0; wordIndex < wordCount; wordIndex++) {
                int word = 0;
                for (int slot = 0; slot < blocksPerWord; slot++) {
                    int blockIndex = wordIndex * blocksPerWord + slot;
                    if (blockIndex >= BLOCK_COUNT) {
                        break;
                    }
                    int paletteIndex = paletteIndices.get(runtimeIds[blockIndex]);
                    word |= (paletteIndex & mask) << (slot * bits);
                }
                BedrockBinary.writeLittleEndianInt32(out, word);
            }
        }

        if (bits == 0) {
            // Uniform storage has an implicit one-entry palette: write only
            // its signed runtime ID, without a palette-size prefix.
            BedrockBinary.writeVarInt32(out, palette.get(0));
        } else {
            BedrockBinary.writeVarInt32(out, palette.size());
            for (int runtimeId : palette) {
                BedrockBinary.writeVarInt32(out, runtimeId);
            }
        }

        out.flip();
        return out.asReadOnlyBuffer();
    }

    private static int bitsForPalette(int paletteSize) {
        if (paletteSize <= 1) {
            return 0;
        }
        for (int width : WIDTHS) {
            if ((1 << width) >= paletteSize) {
                return width;
            }
        }
        throw new IllegalArgumentException("Block palette exceeds the 16-bit network limit");
    }
}
