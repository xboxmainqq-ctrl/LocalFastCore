package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NexoraChunkDataCodecTest {
    @Test
    void chunkRequestMatchesTheAndroidWireLayout() {
        byte[] expected = ByteBuffer.allocate(15)
                .put(new byte[] {'N','X','R','A',1,0,5})
                .putInt(-2).putInt(7).array();
        assertArrayEquals(expected, NexoraChunkDataCodec.encodeChunkRequest(-2, 7));
        assertEquals(new NexoraChunkDataCodec.ChunkRequest(-2, 7),
                NexoraChunkDataCodec.decodeChunkRequest(ByteBuffer.wrap(expected)));
    }

    @Test
    void chunkVolumeRoundTripsThreeDimensionalBlocks() {
        NexoraChunkData expected = new NexoraChunkData(3, -4, List.of(
                new NexoraChunkData.Block(0, 60, 0, "minecraft:bedrock"),
                new NexoraChunkData.Block(0, 64, 0, "minecraft:grass_block"),
                new NexoraChunkData.Block(15, 63, 15, "minecraft:stone")));
        assertEquals(expected, NexoraChunkDataCodec.decodeChunkData(
                ByteBuffer.wrap(NexoraChunkDataCodec.encodeChunkData(expected))));
    }

    @Test
    void volumeIncludesEveryNonAirLayerInLoadedChunk() {
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        world.setBlock(new BlockPosition(0, 60, 0), new BlockState("minecraft:bedrock"));
        world.setBlock(new BlockPosition(0, 63, 0), new BlockState("minecraft:dirt"));
        world.setBlock(new BlockPosition(0, 64, 0), new BlockState("minecraft:grass_block"));
        world.setBlock(new BlockPosition(1, 63, 0), new BlockState("minecraft:stone"));

        NexoraChunkData chunk = NexoraChunkData.volume(world, 0, 0);

        assertEquals(4, chunk.blocks().size());
        assertEquals(new NexoraChunkData.Block(0, 60, 0, "minecraft:bedrock"),
                chunk.blocks().get(0));
        assertEquals(new NexoraChunkData.Block(0, 64, 0, "minecraft:grass_block"),
                chunk.blocks().get(2));
    }

    @Test
    void rejectsDuplicateBlocksAndTrailingBytes() {
        assertThrows(IllegalArgumentException.class, () -> new NexoraChunkData(0, 0, List.of(
                new NexoraChunkData.Block(1, 2, 3, "minecraft:stone"),
                new NexoraChunkData.Block(1, 2, 3, "minecraft:dirt"))));
        byte[] encoded = NexoraChunkDataCodec.encodeChunkData(new NexoraChunkData(0, 0, List.of()));
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class,
                () -> NexoraChunkDataCodec.decodeChunkData(ByteBuffer.wrap(trailing)));
    }

    @Test
    void rejectsMoreThanMaximumBlocks() {
        assertThrows(IllegalArgumentException.class, () ->
                new NexoraChunkData(0, 0, java.util.Collections.nCopies(
                        NexoraChunkData.MAX_BLOCKS + 1,
                        new NexoraChunkData.Block(0, 1, 0, "minecraft:stone"))));
    }
}
