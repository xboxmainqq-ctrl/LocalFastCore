package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bedrock network biome palette storage for one 16x16x16 section.
 *
 * <p>Biome palettes use numeric biome IDs (unsigned VarInt), while sharing
 * the network bit-packed storage layout used by block palettes.</p>
 */
public final class BedrockBiomePaletteCodec {
    public static final int BIOME_COUNT = 4096;
    private static final int MAX_SECTIONS = 128;
    private static final int[] WIDTHS = {1, 2, 3, 4, 5, 6, 8, 16};

    private BedrockBiomePaletteCodec() {
    }

    /** Encodes one section's 4096 biome IDs. Input is never retained or modified. */
    public static ByteBuffer encode(int[] biomeIds) {
        Objects.requireNonNull(biomeIds, "biomeIds");
        if (biomeIds.length != BIOME_COUNT) {
            throw new IllegalArgumentException("A biome section must contain exactly 4096 values");
        }

        Map<Integer, Integer> paletteIndices = new LinkedHashMap<>();
        List<Integer> palette = new ArrayList<>();
        int[] indices = new int[BIOME_COUNT];
        for (int i = 0; i < biomeIds.length; i++) {
            int id = biomeIds[i];
            if (id < 0) {
                throw new IllegalArgumentException("Biome IDs must be non-negative");
            }
            Integer paletteIndex = paletteIndices.get(id);
            if (paletteIndex == null) {
                paletteIndex = palette.size();
                paletteIndices.put(id, paletteIndex);
                palette.add(id);
            }
            indices[i] = paletteIndex;
        }

        int bits = bitsForPalette(palette.size());
        int blocksPerWord = bits == 0 ? 0 : 32 / bits;
        int wordCount = bits == 0 ? 0 : (BIOME_COUNT + blocksPerWord - 1) / blocksPerWord;
        ByteBuffer out = ByteBuffer.allocate(1 + wordCount * Integer.BYTES + 5 + palette.size() * 5);
        out.put((byte) ((bits << 1) | 1));

        if (bits != 0) {
            int mask = (1 << bits) - 1;
            for (int wordIndex = 0; wordIndex < wordCount; wordIndex++) {
                int word = 0;
                for (int slot = 0; slot < blocksPerWord; slot++) {
                    int valueIndex = wordIndex * blocksPerWord + slot;
                    if (valueIndex >= BIOME_COUNT) {
                        break;
                    }
                    word |= (indices[valueIndex] & mask) << (slot * bits);
                }
                BedrockBinary.writeLittleEndianInt32(out, word);
            }
            BedrockBinary.writeVarUInt32(out, palette.size());
        }

        // A uniform storage has no words or palette-size prefix.
        for (int id : palette) {
            BedrockBinary.writeVarUInt32(out, id);
        }
        out.flip();
        return out.asReadOnlyBuffer();
    }

    /**
     * Encodes consecutive section palettes. An identical palette to the
     * immediately previous section uses Bedrock's 0xFF reuse marker.
     */
    public static ByteBuffer encodeSections(List<int[]> sections) {
        Objects.requireNonNull(sections, "sections");
        if (sections.isEmpty() || sections.size() > MAX_SECTIONS) {
            throw new IllegalArgumentException("Biome section count must be between 1 and 128");
        }

        List<ByteBuffer> encoded = new ArrayList<>(sections.size());
        int capacity = 0;
        int[] previous = null;
        for (int index = 0; index < sections.size(); index++) {
            int[] current = Objects.requireNonNull(sections.get(index), "biome section");
            if (current.length != BIOME_COUNT) {
                throw new IllegalArgumentException("A biome section must contain exactly 4096 values");
            }
            ByteBuffer section;
            if (previous != null && Arrays.equals(previous, current)) {
                section = ByteBuffer.wrap(new byte[] {(byte) 0xFF}).asReadOnlyBuffer();
            } else {
                section = encode(current);
            }
            encoded.add(section);
            capacity = Math.addExact(capacity, section.remaining());
            previous = current;
        }

        ByteBuffer out = ByteBuffer.allocate(capacity);
        for (ByteBuffer section : encoded) {
            out.put(section);
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
        throw new IllegalArgumentException("Biome palette exceeds the 16-bit network limit");
    }
}
