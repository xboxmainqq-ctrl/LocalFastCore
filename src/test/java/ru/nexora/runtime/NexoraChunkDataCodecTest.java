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
    void chunkDataRoundTripsSparseSurfaceColumns() {
        NexoraChunkData expected = new NexoraChunkData(3, -4, List.of(
                new NexoraChunkData.Column(0, 64, "minecraft:grass_block"),
                new NexoraChunkData.Column(255, 70, "minecraft:stone")));
        assertEquals(expected, NexoraChunkDataCodec.decodeChunkData(
                ByteBuffer.wrap(NexoraChunkDataCodec.encodeChunkData(expected))));
    }

    @Test
    void topSurfaceUsesHighestNonAirBlockInEachColumn() {
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        world.setBlock(new BlockPosition(0, 60, 0), new BlockState("minecraft:dirt"));
        world.setBlock(new BlockPosition(0, 64, 0), new BlockState("minecraft:grass_block"));
        world.setBlock(new BlockPosition(1, 63, 0), new BlockState("minecraft:stone"));

        NexoraChunkData chunk = NexoraChunkData.topSurface(world, 0, 0);

        assertEquals(2, chunk.columns().size());
        assertEquals(new NexoraChunkData.Column(0, 64, "minecraft:grass_block"),
                chunk.columns().get(0));
        assertEquals(new NexoraChunkData.Column(1, 63, "minecraft:stone"),
                chunk.columns().get(1));
    }

    @Test
    void rejectsDuplicateColumnsAndTrailingBytes() {
        assertThrows(IllegalArgumentException.class, () -> new NexoraChunkData(0, 0, List.of(
                new NexoraChunkData.Column(1, 2, "minecraft:stone"),
                new NexoraChunkData.Column(1, 3, "minecraft:dirt"))));
        byte[] encoded = NexoraChunkDataCodec.encodeChunkData(
                new NexoraChunkData(0, 0, List.of()));
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class,
                () -> NexoraChunkDataCodec.decodeChunkData(ByteBuffer.wrap(trailing)));
    }
}
