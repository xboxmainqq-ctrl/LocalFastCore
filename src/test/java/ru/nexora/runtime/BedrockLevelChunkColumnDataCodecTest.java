package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BedrockLevelChunkColumnDataCodecTest {
    @Test
    void writesTerrainSectionsThenMatchingBiomePalettesAndColumnTerminators() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(-16, 16));
        ChunkPosition chunk = new ChunkPosition(0, 0);
        BlockState stone = new BlockState("minecraft:stone");
        world.setBlock(new BlockPosition(1, 0, 1), stone);
        world.setBiome(new BlockPosition(0, -1, 0), 7);
        BedrockRuntimeBlockRegistry registry = BedrockRuntimeBlockRegistry.of(
                Map.of(BlockState.AIR, 0, stone, 1));

        BedrockLevelChunkColumnDataCodec.EncodedColumn encoded =
                BedrockLevelChunkColumnDataCodec.encode(world, chunk, registry);

        assertEquals(2, encoded.subChunkCount());

        ChunkSection lowerAir = new ChunkSection();
        ChunkSection upper = new ChunkSection();
        upper.set(1, 0, 1, stone);
        ByteBuffer expectedLower = BedrockSubChunkTerrainCodec.encodeSection(lowerAir, -1, registry);
        ByteBuffer expectedUpper = BedrockSubChunkTerrainCodec.encodeSection(upper, 0, registry);
        ByteBuffer expectedBiomes = BedrockBiomePaletteCodec.encodeSections(java.util.List.of(
                world.biomesInSection(chunk, -1),
                world.biomesInSection(chunk, 0)));

        ByteBuffer expected = ByteBuffer.allocate(
                expectedLower.remaining() + expectedUpper.remaining()
                        + expectedBiomes.remaining() + 2);
        expected.put(expectedLower).put(expectedUpper).put(expectedBiomes);
        expected.put((byte) 0); // no border blocks
        expected.put((byte) 0); // no block entities
        expected.flip();

        assertArrayEquals(toBytes(expected), encoded.serializedData());
    }

    @Test
    void includesFluidOnlySectionsInTheFullColumn() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 16));
        ChunkPosition chunk = new ChunkPosition(1, -2);
        BlockState water = new BlockState("minecraft:water");
        world.setFluid(new BlockPosition(18, 4, -31), water);
        BedrockRuntimeBlockRegistry registry = BedrockRuntimeBlockRegistry.of(Map.of(
                BlockState.AIR, 0,
                water, 2));

        BedrockLevelChunkColumnDataCodec.EncodedColumn encoded =
                BedrockLevelChunkColumnDataCodec.encode(world, chunk, registry);

        assertEquals(1, encoded.subChunkCount());
        ByteBuffer in = ByteBuffer.wrap(encoded.serializedData());
        assertEquals(9, Byte.toUnsignedInt(in.get()));
        assertEquals(2, Byte.toUnsignedInt(in.get()));
        assertEquals(0, in.get());
        assertEquals(1, Byte.toUnsignedInt(in.get())); // block layer: uniform air
        assertEquals(0, BedrockBinary.readVarInt32(in));
        assertEquals(3, Byte.toUnsignedInt(in.get())); // liquid layer: water + air
        for (int i = 0; i < 128; i++) BedrockBinary.readLittleEndianInt32(in);
        assertEquals(2, BedrockBinary.readVarInt32(in));
        assertEquals(0, BedrockBinary.readVarInt32(in)); // air first
        assertEquals(2, BedrockBinary.readVarInt32(in)); // water
        assertEquals(1, Byte.toUnsignedInt(in.get())); // uniform plains biome
        assertEquals(1, BedrockBinary.readVarUInt32(in));
        assertEquals(0, Byte.toUnsignedInt(in.get())); // no border blocks
        assertEquals(0, Byte.toUnsignedInt(in.get())); // no block entities
        assertFalse(in.hasRemaining());
    }

    @Test
    void rejectsUnloadedChunksAndKeepsEmptyLoadedChunkPayloadMinimal() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 16));
        BedrockRuntimeBlockRegistry registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0));
        ChunkPosition chunk = new ChunkPosition(2, 3);

        assertThrows(IllegalArgumentException.class,
                () -> BedrockLevelChunkColumnDataCodec.encode(world, chunk, registry));

        assertTrue(world.loadChunk(new GeneratedChunk(chunk, Map.of())));
        BedrockLevelChunkColumnDataCodec.EncodedColumn encoded =
                BedrockLevelChunkColumnDataCodec.encode(world, chunk, registry);
        assertEquals(0, encoded.subChunkCount());
        assertArrayEquals(new byte[] {0, 0}, encoded.serializedData());
    }

    private static byte[] toBytes(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
