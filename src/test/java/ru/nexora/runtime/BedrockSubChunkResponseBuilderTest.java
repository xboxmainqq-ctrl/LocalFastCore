package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSubChunkResponseBuilderTest {
    private static final BlockState STONE = new BlockState("minecraft:stone");

    @Test
    void buildsTerrainAndAllAirResponsesFromLoadedWorld() {
        InMemoryWorld world = new InMemoryWorld("test", new WorldHeight(-64, 320));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0),
                Map.of(new BlockPosition(1, 0, 1), STONE)));
        var request = new BedrockSubChunkRequestPacketCodec.Request(
                0,
                List.of(
                        new BedrockSubChunkRequestPacketCodec.Offset(0, 0, 0),
                        new BedrockSubChunkRequestPacketCodec.Offset(0, 1, 0)),
                new BedrockSubChunkRequestPacketCodec.SubChunkPosition(0, 0, 0));
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(
                BlockState.AIR, 0,
                STONE, 1
        ));

        List<BedrockGamePacket> packets =
                BedrockSubChunkResponseBuilder.buildPackets(world, request, 0, registry);
        assertEquals(1, packets.size());
        assertEquals(174, packets.get(0).header().packetId());

        ByteBuffer in = packets.get(0).payloadBuffer();
        assertFalse(readBoolean(in));
        assertEquals(0, BedrockBinary.readVarInt32(in));
        assertEquals(0, BedrockBinary.readLittleEndianInt32(in));
        assertEquals(0, BedrockBinary.readLittleEndianInt32(in));
        assertEquals(0, BedrockBinary.readLittleEndianInt32(in));
        assertEquals(2, BedrockBinary.readVarUInt32(in));

        assertEquals(1, in.get());
        assertEquals(0, in.get());
        assertEquals(0, in.get());
        assertEquals(1, Byte.toUnsignedInt(in.get()));
        int dataLength = BedrockBinary.readVarUInt32(in);
        byte[] data = new byte[dataLength];
        in.get(data);
        ByteBuffer terrain = ByteBuffer.wrap(data);
        assertEquals(9, Byte.toUnsignedInt(terrain.get()));
        assertEquals(2, Byte.toUnsignedInt(terrain.get()));
        assertEquals(0, terrain.get());
        assertEquals(3, Byte.toUnsignedInt(terrain.get()));
        for (int i = 0; i < 128; i++) BedrockBinary.readLittleEndianInt32(terrain);
        assertEquals(2, BedrockBinary.readVarInt32(terrain));
        BedrockBinary.readVarInt32(terrain);
        BedrockBinary.readVarInt32(terrain);
        assertEquals(1, Byte.toUnsignedInt(terrain.get())); // empty liquid layer
        assertEquals(0, BedrockBinary.readVarInt32(terrain));
        assertEquals(0, Byte.toUnsignedInt(terrain.get())); // block actor TAG_End
        assertFalse(terrain.hasRemaining());
        assertEquals(0, Byte.toUnsignedInt(in.get()));
        assertEquals(0, Byte.toUnsignedInt(in.get()));

        assertEquals(0, in.get());
        assertEquals(1, in.get());
        assertEquals(0, in.get());
        assertEquals(6, Byte.toUnsignedInt(in.get()));
        assertEquals(0, BedrockBinary.readVarUInt32(in));
        assertEquals(0, Byte.toUnsignedInt(in.get()));
        assertEquals(0, Byte.toUnsignedInt(in.get()));
        assertFalse(in.hasRemaining());
    }

    @Test
    void returnsFluidOnlySubchunksAsSuccessfulTerrain() {
        InMemoryWorld world = new InMemoryWorld("test", new WorldHeight(-64, 320));
        world.setFluid(new BlockPosition(0, 0, 0), new BlockState("minecraft:water"));
        var request = new BedrockSubChunkRequestPacketCodec.Request(
                0, List.of(new BedrockSubChunkRequestPacketCodec.Offset(0, 0, 0)),
                new BedrockSubChunkRequestPacketCodec.SubChunkPosition(0, 0, 0));
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(
                BlockState.AIR, 0,
                new BlockState("minecraft:water"), 1));

        BedrockGamePacket response = BedrockSubChunkResponseBuilder.buildPackets(
                world, request, 0, registry).get(0);
        assertEquals(1, firstResult(response)); // Success, not SuccessAllAir
    }

    @Test
    void reportsWrongDimensionAndMissingChunks() {
        InMemoryWorld world = new InMemoryWorld("test", new WorldHeight(-64, 320));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0),
                Map.of(new BlockPosition(0, 0, 0), STONE)));
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0, STONE, 1));

        var wrongDimension = new BedrockSubChunkRequestPacketCodec.Request(
                1, List.of(new BedrockSubChunkRequestPacketCodec.Offset(0, 0, 0)),
                new BedrockSubChunkRequestPacketCodec.SubChunkPosition(0, 0, 0));
        var missingChunk = new BedrockSubChunkRequestPacketCodec.Request(
                0, List.of(new BedrockSubChunkRequestPacketCodec.Offset(1, 0, 0)),
                new BedrockSubChunkRequestPacketCodec.SubChunkPosition(0, 0, 0));

        assertEquals(3, firstResult(BedrockSubChunkResponseBuilder.buildPackets(
                world, wrongDimension, 0, registry).get(0)));
        assertEquals(2, firstResult(BedrockSubChunkResponseBuilder.buildPackets(
                world, missingChunk, 0, registry).get(0)));
    }


    @Test
    void reportsSubchunkYOutsideWorldHeight() {
        InMemoryWorld world = new InMemoryWorld("test", new WorldHeight(-64, 320));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0),
                Map.of(new BlockPosition(0, 0, 0), STONE)));
        var request = new BedrockSubChunkRequestPacketCodec.Request(
                0, List.of(new BedrockSubChunkRequestPacketCodec.Offset(0, 100, 0)),
                new BedrockSubChunkRequestPacketCodec.SubChunkPosition(0, 0, 0));
        var registry = BedrockRuntimeBlockRegistry.of(Map.of(BlockState.AIR, 0, STONE, 1));

        assertEquals(5, firstResult(BedrockSubChunkResponseBuilder.buildPackets(
                world, request, 0, registry).get(0)));
    }

    @Test
    void rejectsRequestsLargerThanOneBoundedResponseBatch() {
        var offsets = java.util.Collections.nCopies(65,
                new BedrockSubChunkRequestPacketCodec.Offset(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new BedrockSubChunkRequestPacketCodec.Request(
                        0, offsets,
                        new BedrockSubChunkRequestPacketCodec.SubChunkPosition(0, 0, 0)));
    }

    private static int firstResult(BedrockGamePacket packet) {
        ByteBuffer in = packet.payloadBuffer();
        readBoolean(in);
        BedrockBinary.readVarInt32(in);
        in.position(in.position() + 12);
        assertTrue(BedrockBinary.readVarUInt32(in) >= 1);
        in.position(in.position() + 3);
        return Byte.toUnsignedInt(in.get());
    }

    private static int readEntryCount(BedrockGamePacket packet) {
        ByteBuffer in = packet.payloadBuffer();
        readBoolean(in);
        BedrockBinary.readVarInt32(in);
        in.position(in.position() + 12);
        return BedrockBinary.readVarUInt32(in);
    }

    private static boolean readBoolean(ByteBuffer input) {
        int value = Byte.toUnsignedInt(input.get());
        assertTrue(value <= 1);
        return value != 0;
    }
    @Test
    void groupsNegativeAndPositiveWorldCoordinatesIntoCorrectSections() {
        BlockState dirt = new BlockState("minecraft:dirt");
        Map<Integer, ChunkSection> sections = BedrockSubChunkResponseBuilder.groupBlocksBySection(
                Map.of(
                        new BlockPosition(-1, -1, -17), STONE,
                        new BlockPosition(16, 16, 16), dirt
                ));

        assertEquals(2, sections.size());
        assertEquals(STONE, sections.get(-1).get(15, 15, 15));
        assertEquals(dirt, sections.get(1).get(0, 0, 0));
        assertTrue(sections.get(-1).get(0, 0, 0).isAir());
    }

    @Test
    void emptyChunkSnapshotProducesNoSectionAllocations() {
        assertTrue(BedrockSubChunkResponseBuilder.groupBlocksBySection(Map.of()).isEmpty());
    }

}
