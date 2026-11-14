package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPalettedStorageCodecTest {
    private static final BlockState STONE = new BlockState("minecraft:stone");

    @Test
    void encodesUniformAirPaletteWithoutIndexWordsOrPaletteArray() {
        ChunkSection section = new ChunkSection();
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0));

        ByteBuffer encoded = BedrockPalettedStorageCodec.encode(section, registry);
        assertEquals(1, Byte.toUnsignedInt(encoded.get())); // zero bits, runtime flag set
        assertFalse(encoded.hasRemaining());
    }

    @Test
    void encodesTwoStatePaletteUsingBedrockXzyIndexOrder() {
        ChunkSection section = new ChunkSection();
        section.set(1, 0, 0, STONE);
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(
                BlockState.AIR, 0,
                STONE, 1
        ));

        ByteBuffer in = BedrockPalettedStorageCodec.encode(section, registry);
        assertEquals(3, Byte.toUnsignedInt(in.get())); // 1 bit + runtime flag

        int[] words = new int[128]; // 4096 entries / 32 entries per word
        for (int i = 0; i < words.length; i++) {
            words[i] = BedrockBinary.readLittleEndianInt32(in);
        }
        assertEquals(1, words[8] & 1); // (x=1,z=0,y=0) => network index 256

        assertEquals(2, BedrockBinary.readVarInt32(in));
        assertEquals(0, BedrockBinary.readVarInt32(in));
        assertEquals(1, BedrockBinary.readVarInt32(in));
        assertFalse(in.hasRemaining());
    }

    @Test
    void rejectsMissingRuntimeIdsInsteadOfGuessing() {
        ChunkSection section = new ChunkSection();
        section.set(0, 0, 0, STONE);
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPalettedStorageCodec.encode(section, registry));
    }

    @Test
    void runtimeRegistryRequiresAirAndNonnegativeIds() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockRuntimeBlockRegistry.of(Map.of(STONE, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, -1)));
    }
}
