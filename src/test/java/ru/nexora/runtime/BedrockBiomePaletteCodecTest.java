package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockBiomePaletteCodecTest {
    @Test
    void encodesUniformPaletteWithoutWordsOrPaletteCount() {
        int[] biomes = new int[BedrockBiomePaletteCodec.BIOME_COUNT];
        Arrays.fill(biomes, 7);

        ByteBuffer encoded = BedrockBiomePaletteCodec.encode(biomes);

        assertEquals(2, encoded.remaining());
        assertEquals(1, Byte.toUnsignedInt(encoded.get()));
        assertEquals(7, BedrockBinary.readVarUInt32(encoded));
        assertFalse(encoded.hasRemaining());
    }

    @Test
    void packsNonUniformBiomeIndicesAndWritesNumericPalette() {
        int[] biomes = new int[BedrockBiomePaletteCodec.BIOME_COUNT];
        Arrays.fill(biomes, 2);
        biomes[0] = 1;
        biomes[1] = 3;

        ByteBuffer encoded = BedrockBiomePaletteCodec.encode(biomes);

        assertEquals(3, Byte.toUnsignedInt(encoded.get())); // 1 bit + network flag
        assertEquals(128 * Integer.BYTES, encoded.remaining() - 3);
        int firstWord = BedrockBinary.readLittleEndianInt32(encoded);
        assertEquals(0b10, firstWord & 0b11);
        assertEquals(0b01, (firstWord >>> 1) & 0b1);
        for (int i = 1; i < 128; i++) {
            BedrockBinary.readLittleEndianInt32(encoded);
        }
        assertEquals(3, BedrockBinary.readVarUInt32(encoded));
        assertEquals(1, BedrockBinary.readVarUInt32(encoded));
        assertEquals(2, BedrockBinary.readVarUInt32(encoded));
        assertEquals(3, BedrockBinary.readVarUInt32(encoded));
        assertFalse(encoded.hasRemaining());
    }

    @Test
    void reusesAnIdenticalPreviousSectionWithTheBedrockMarker() {
        int[] first = new int[BedrockBiomePaletteCodec.BIOME_COUNT];
        Arrays.fill(first, 4);
        int[] second = first.clone();
        second[100] = 9;

        ByteBuffer encoded = BedrockBiomePaletteCodec.encodeSections(List.of(first, first, second));

        assertEquals(1, Byte.toUnsignedInt(encoded.get()));
        assertEquals(4, BedrockBinary.readVarUInt32(encoded));
        assertEquals(0xFF, Byte.toUnsignedInt(encoded.get()));
        assertEquals(3, Byte.toUnsignedInt(encoded.get())); // two-value palette
    }

    @Test
    void rejectsInvalidSectionShapeNegativeIdsAndExcessiveSectionCount() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBiomePaletteCodec.encode(new int[63]));
        int[] invalid = new int[BedrockBiomePaletteCodec.BIOME_COUNT];
        invalid[3] = -1;
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBiomePaletteCodec.encode(invalid));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBiomePaletteCodec.encodeSections(
                        java.util.Collections.nCopies(129, new int[BedrockBiomePaletteCodec.BIOME_COUNT])));
    }
}
