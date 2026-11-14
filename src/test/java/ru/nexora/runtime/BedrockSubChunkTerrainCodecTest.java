package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSubChunkTerrainCodecTest {
    @Test
    void writesVersionNineHeaderAndSingleRuntimeBlockLayer() {
        ChunkSection section = new ChunkSection();
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0));

        ByteBuffer in = BedrockSubChunkTerrainCodec.encode(section, -4, registry);
        assertEquals(9, Byte.toUnsignedInt(in.get()));
        assertEquals(1, Byte.toUnsignedInt(in.get()));
        assertEquals(-4, in.get());
        assertEquals(1, Byte.toUnsignedInt(in.get())); // uniform runtime air palette
        assertEquals(0, Byte.toUnsignedInt(in.get())); // empty block-actor TAG_End
        assertFalse(in.hasRemaining());
    }

    @Test
    void rejectsSubchunkYOutsideSignedByteRange() {
        ChunkSection section = new ChunkSection();
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSubChunkTerrainCodec.encode(section, 128, registry));
    }
}
