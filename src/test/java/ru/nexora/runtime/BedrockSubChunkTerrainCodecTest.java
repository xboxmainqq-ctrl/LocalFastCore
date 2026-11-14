package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSubChunkTerrainCodecTest {
    @Test
    void writesVersionNineHeaderWithBlockAndEmptyLiquidLayers() {
        ChunkSection section = new ChunkSection();
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0));

        ByteBuffer in = BedrockSubChunkTerrainCodec.encode(section, -4, registry);
        assertEquals(9, Byte.toUnsignedInt(in.get()));
        assertEquals(2, Byte.toUnsignedInt(in.get())); // block + liquid storage layers
        assertEquals(-4, in.get());
        assertEquals(1, Byte.toUnsignedInt(in.get())); // block layer: uniform air
        assertEquals(0, BedrockBinary.readVarInt32(in)); // implicit air runtime ID
        assertEquals(1, Byte.toUnsignedInt(in.get())); // liquid layer: uniform empty
        assertEquals(0, BedrockBinary.readVarInt32(in)); // implicit air runtime ID
        assertEquals(0, Byte.toUnsignedInt(in.get())); // empty block-actor TAG_End
        assertFalse(in.hasRemaining());
    }

    @Test
    void rejectsWaterUntilWorldFluidLayerIsImplemented() {
        ChunkSection section = new ChunkSection();
        section.set(0, 0, 0, new BlockState("minecraft:water"));
        var registry = BedrockRuntimeBlockRegistry.hashing();
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSubChunkTerrainCodec.encode(section, 0, registry));
    }

    @Test
    void rejectsWaterloggedBlocksUntilFluidLayerIsStoredSeparately() {
        ChunkSection section = new ChunkSection();
        section.set(0, 0, 0, new BlockState("minecraft:oak_stairs", java.util.Map.of(
                "waterlogged", "true")));
        var registry = BedrockRuntimeBlockRegistry.hashing();
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSubChunkTerrainCodec.encode(section, 0, registry));
    }

    @Test
    void rejectsSubchunkYOutsideSignedByteRange() {
        ChunkSection section = new ChunkSection();
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSubChunkTerrainCodec.encode(section, 128, registry));
    }
}
