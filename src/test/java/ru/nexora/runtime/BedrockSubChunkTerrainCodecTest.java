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
    void serializesWaterInTheSeparateLiquidLayer() {
        ChunkSection blocks = new ChunkSection();
        ChunkSection fluids = new ChunkSection();
        BlockState water = new BlockState("minecraft:water");
        fluids.set(0, 0, 0, water);
        var registry = BedrockRuntimeBlockRegistry.hashing();

        ByteBuffer in = BedrockSubChunkTerrainCodec.encode(blocks, fluids, 0, registry);
        assertEquals(9, Byte.toUnsignedInt(in.get()));
        assertEquals(2, Byte.toUnsignedInt(in.get()));
        assertEquals(0, in.get());
        assertEquals(1, Byte.toUnsignedInt(in.get())); // solid layer is uniform air
        assertEquals(registry.runtimeId(BlockState.AIR), BedrockBinary.readVarInt32(in));
        assertEquals(3, Byte.toUnsignedInt(in.get())); // 1-bit fluid palette
        for (int i = 0; i < 128; i++) BedrockBinary.readLittleEndianInt32(in);
        assertEquals(2, BedrockBinary.readVarInt32(in));
        assertEquals(registry.runtimeId(water), BedrockBinary.readVarInt32(in));
        assertEquals(registry.runtimeId(BlockState.AIR), BedrockBinary.readVarInt32(in));
        assertEquals(0, Byte.toUnsignedInt(in.get())); // block actor TAG_End
        assertFalse(in.hasRemaining());
    }

    @Test
    void supportsWaterloggedSolidBlockWithFluidInSecondLayer() {
        ChunkSection blocks = new ChunkSection();
        ChunkSection fluids = new ChunkSection();
        blocks.set(0, 0, 0, new BlockState("minecraft:oak_stairs", java.util.Map.of(
                "waterlogged", "true")));
        fluids.set(0, 0, 0, new BlockState("minecraft:water"));
        var registry = BedrockRuntimeBlockRegistry.hashing();

        assertDoesNotThrow(() -> BedrockSubChunkTerrainCodec.encode(blocks, fluids, 0, registry));
    }

    @Test
    void rejectsFluidStoredInSolidBlockLayer() {
        ChunkSection blocks = new ChunkSection();
        blocks.set(0, 0, 0, new BlockState("minecraft:water"));
        var registry = BedrockRuntimeBlockRegistry.hashing();
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSubChunkTerrainCodec.encode(blocks, 0, registry));
    }

    @Test
    void rejectsSubchunkYOutsideSignedByteRange() {
        ChunkSection section = new ChunkSection();
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockSubChunkTerrainCodec.encode(section, 128, registry));
    }
}
