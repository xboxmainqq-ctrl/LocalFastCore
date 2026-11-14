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
