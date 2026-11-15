package ru.nexora.client;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.Assert.*;

public class NexoraAndroidProtocolCodecTest {

    @Test
    public void encodesBlockActionsAndDecodesAuthoritativeBlockUpdates() {
        byte[] request = NexoraAndroidProtocolCodec.encodeBlockAction(
                false, -2, 64, 3, "");
        assertEquals(21, request.length);
        assertEquals(14, Byte.toUnsignedInt(request[6]));
        assertEquals(1, Byte.toUnsignedInt(request[7]));
        ByteBuffer requestValues = ByteBuffer.wrap(request);
        requestValues.position(8);
        assertEquals(-2, requestValues.getInt());
        assertEquals(64, requestValues.getInt());
        assertEquals(3, requestValues.getInt());

        byte[] blockId = "minecraft:stone".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer update = ByteBuffer.allocate(20 + blockId.length);
        update.put(new byte[] {'N','X','R','A',1,3,15});
        update.putInt(-2).putInt(64).putInt(3).put((byte) blockId.length).put(blockId);
        NexoraAndroidProtocolCodec.BlockUpdate decoded =
                NexoraAndroidProtocolCodec.decodeBlockUpdate(update.array());
        assertEquals(-2, decoded.x());
        assertEquals(64, decoded.y());
        assertEquals(3, decoded.z());
        assertEquals("minecraft:stone", decoded.blockId());
    }

    @Test
    public void clientHelloMatchesTheServerGoldenVector() {
        byte[] expected = new byte[] {
                'N', 'X', 'R', 'A', 1, 2, 1, 11,
                'a', 'n', 'd', 'r', 'o', 'i', 'd', '-', 'd', 'e', 'v',
                10, (byte) 0xd0, (byte) 0x98, (byte) 0xd0, (byte) 0xb3,
                (byte) 0xd1, (byte) 0x80, (byte) 0xd0, (byte) 0xbe,
                (byte) 0xd0, (byte) 0xba, 0
        };
        assertArrayEquals(expected, NexoraAndroidProtocolCodec.encodeClientHello("android-dev"));
    }

    @Test
    public void encodesClientHelloWithExpectedMagicAndVersion() {
        byte[] encoded = NexoraAndroidProtocolCodec.encodeClientHello("android-dev");
        assertEquals(78, Byte.toUnsignedInt(encoded[0]));
        assertEquals(88, Byte.toUnsignedInt(encoded[1]));
        assertEquals(82, Byte.toUnsignedInt(encoded[2]));
        assertEquals(65, Byte.toUnsignedInt(encoded[3]));
        assertEquals(1, Byte.toUnsignedInt(encoded[4]));
        assertEquals(3, Byte.toUnsignedInt(encoded[5]));
        assertEquals(1, Byte.toUnsignedInt(encoded[6]));
    }

    @Test
    public void encodesMovementWithProtocolOnePointTwo() {
        byte[] packet = NexoraAndroidProtocolCodec.encodePlayerMove(1.25, 65.0, -2.5);
        assertEquals(31, packet.length);
        assertEquals(1, Byte.toUnsignedInt(packet[4]));
        assertEquals(2, Byte.toUnsignedInt(packet[5]));
        assertEquals(7, Byte.toUnsignedInt(packet[6]));
        ByteBuffer values = ByteBuffer.wrap(packet);
        values.position(7);
        assertEquals(1.25, values.getDouble(), 0.0001);
        assertEquals(65.0, values.getDouble(), 0.0001);
        assertEquals(-2.5, values.getDouble(), 0.0001);
    }

    @Test
    public void decodesServerHello() {
        UUID id = UUID.fromString("f8d2c8e0-a2c4-4a87-a69d-02e7d8dff921");
        ByteBuffer bytes = ByteBuffer.allocate(27);
        bytes.put(new byte[] {'N','X','R','A',1,3,2});
        bytes.putLong(id.getMostSignificantBits());
        bytes.putLong(id.getLeastSignificantBits());
        bytes.putInt(1024 * 1024);

        NexoraAndroidProtocolCodec.ServerResponse response =
                NexoraAndroidProtocolCodec.decodeServerResponse(bytes.array());
        assertTrue(response.accepted());
        assertEquals(id.toString(), response.serverId());
        assertEquals(1024 * 1024, response.maxMessageBytes());
    }

    @Test
    public void decodesServerRejection() {
        byte[] reason = "Unsupported version".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer bytes = ByteBuffer.allocate(9 + reason.length);
        bytes.put(new byte[] {'N','X','R','A',1,3,3,1,(byte) reason.length});
        bytes.put(reason);
        NexoraAndroidProtocolCodec.ServerResponse response =
                NexoraAndroidProtocolCodec.decodeServerResponse(bytes.array());
        assertFalse(response.accepted());
        assertEquals(1, response.rejectionCode());
        assertEquals("Unsupported version", response.rejectionReason());
    }

    @Test
    public void decodesWorldInfoFromServer() {
        byte[] worldId = "world".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer bytes = ByteBuffer.allocate(7 + 1 + worldId.length + 28);
        bytes.put(new byte[] {'N','X','R','A',1,3,4});
        bytes.put((byte) worldId.length).put(worldId);
        bytes.putInt(-64).putInt(320);
        bytes.putInt(0).putInt(65).putInt(0);
        bytes.putInt(20).putInt(1);
        NexoraAndroidProtocolCodec.WorldInfo world =
                NexoraAndroidProtocolCodec.decodeWorldInfo(bytes.array());
        assertEquals("world", world.worldId());
        assertEquals(-64, world.minY());
        assertEquals(320, world.maxYExclusive());
        assertEquals(65, world.spawnY());
        assertEquals(20, world.tickRate());
        assertEquals(1, world.loadedChunks());
    }

    @Test
    public void chunkRequestMatchesServerGoldenVector() {
        byte[] expected = ByteBuffer.allocate(15)
                .put(new byte[] {'N','X','R','A',1,3,5})
                .putInt(-2).putInt(7).array();
        assertArrayEquals(expected, NexoraAndroidProtocolCodec.encodeChunkRequest(-2, 7));
    }

    @Test
    public void decodesChunkVolumeFromServerGoldenVector() {
        byte[] id = "minecraft:grass_block".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer bytes = ByteBuffer.allocate(7 + 12 + 1 + 4 + 1 + 1 + id.length);
        bytes.put(new byte[] {'N','X','R','A',1,3,6});
        bytes.putInt(2).putInt(-3).putInt(1);
        bytes.put((byte) 0).putInt(64).put((byte) 0).put((byte) id.length).put(id);
        NexoraAndroidProtocolCodec.ChunkData chunk =
                NexoraAndroidProtocolCodec.decodeChunkData(bytes.array());
        assertEquals(2, chunk.chunkX());
        assertEquals(-3, chunk.chunkZ());
        assertEquals(1, chunk.blocks().size());
        assertEquals(64, chunk.blocks().get(0).y());
        assertEquals("minecraft:grass_block", chunk.blocks().get(0).blockId());
    }


    @Test
    public void decodesRemotePlayerStateAndYaw() {
        UUID id = UUID.fromString("c7f9a7c3-18b0-4dc5-a3c2-7b7f10f2f1a1");
        byte[] name = "Тест".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer packet = ByteBuffer.allocate(7 + 16 + 1 + name.length + 1 + 32);
        packet.put(new byte[] {'N','X','R','A',1,3,9});
        packet.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        packet.put((byte) name.length).put(name).put((byte) 4);
        packet.putDouble(12.5).putDouble(65.0).putDouble(-8.25).putDouble(135.0);
        NexoraAndroidProtocolCodec.RemotePlayerState state =
                NexoraAndroidProtocolCodec.decodeRemotePlayerState(packet.array());
        assertEquals(id, state.playerId());
        assertEquals("Тест", state.playerName());
        assertEquals(4, state.skinIndex());
        assertEquals(135.0, state.yaw(), 0.0001);
    }

    @Test
    public void decodesServerInventorySnapshot() {
        UUID playerId = UUID.fromString("c7f9a7c3-18b0-4dc5-a3c2-7b7f10f2f1a1");
        byte[] apple = "minecraft:apple".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] diamond = "minecraft:diamond".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer packet = ByteBuffer.allocate(7 + 16 + 1 + 2 + apple.length + 1
                + 2 + diamond.length + 1);
        packet.put(new byte[] {'N','X','R','A',1,3,11});
        packet.putLong(playerId.getMostSignificantBits()).putLong(playerId.getLeastSignificantBits());
        packet.put((byte) 2);
        packet.put((byte) 0).put((byte) apple.length).put(apple).put((byte) 64);
        packet.put((byte) 4).put((byte) diamond.length).put(diamond).put((byte) 3);

        NexoraAndroidProtocolCodec.InventoryState inventory =
                NexoraAndroidProtocolCodec.decodeInventory(packet.array());

        assertEquals(playerId, inventory.playerId());
        assertEquals(2, inventory.stacks().size());
        assertEquals(0, inventory.stacks().get(0).slotIndex());
        assertEquals("minecraft:apple", inventory.stacks().get(0).itemId());
        assertEquals(64, inventory.stacks().get(0).count());
        assertEquals(4, inventory.stacks().get(1).slotIndex());
        assertEquals("minecraft:diamond", inventory.stacks().get(1).itemId());
        assertEquals(3, inventory.stacks().get(1).count());
        assertEquals(67, inventory.totalItems());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInventoryStackLargerThan64() {
        byte[] id = "minecraft:apple".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer packet = ByteBuffer.allocate(7 + 16 + 1 + 2 + id.length + 1);
        packet.put(new byte[] {'N','X','R','A',1,3,11});
        packet.putLong(1L).putLong(2L).put((byte) 1);
        packet.put((byte) 0).put((byte) id.length).put(id).put((byte) 65);
        NexoraAndroidProtocolCodec.decodeInventory(packet.array());
    }

    @Test
    public void encodesInventoryMoveAction() {
        byte[] packet = NexoraAndroidProtocolCodec.encodeInventoryAction(
                NexoraAndroidProtocolCodec.InventoryAction.MOVE_OR_SWAP, 2, 17);
        assertArrayEquals(new byte[] {'N','X','R','A',1,3,12,1,3,17}, packet);
    }

    @Test
    public void decodesInventoryActionResult() {
        byte[] packet = new byte[] {'N','X','R','A',1,3,13,3,0};
        NexoraAndroidProtocolCodec.InventoryActionResult result =
                NexoraAndroidProtocolCodec.decodeInventoryActionResult(packet);
        assertEquals(NexoraAndroidProtocolCodec.InventoryAction.DROP_STACK, result.action());
        assertEquals(NexoraAndroidProtocolCodec.InventoryActionStatus.OK, result.status());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongMagic() {
        NexoraAndroidProtocolCodec.decodeServerResponse(new byte[] {0,0,0,0,1,0,2});
    }
}
