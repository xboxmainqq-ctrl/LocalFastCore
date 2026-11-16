package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.ItemStack;
import ru.nexora.world.WorldItemEntity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BedrockDroppedItemCodecTest {
    private static final BedrockItemRegistry REGISTRY = BedrockVanillaItemCatalog.createRegistry();

    @Test
    void encodesProtocol2225AddItemActorAndRemoveActor() {
        WorldItemEntity entity = new WorldItemEntity(-1_000_000, 1_000_000,
                new ItemStack("minecraft:diamond", 3, 64),
                1.25, 65.0, -2.5, 0.1, 0.2, -0.1, 0, null);
        BedrockPacket add = BedrockAddItemActorCodec.createAddItemActor(entity, REGISTRY);
        assertEquals(0x0f, add.packetId());
        ByteBuffer in = ByteBuffer.wrap(add.payload()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1_999_999, readVarUInt64(in)); // ZigZag(-1,000,000)
        assertEquals(1_000_000, readVarUInt64(in)); // runtime ID
        assertEquals(306, in.getShort() & 0xffff); // diamond runtime item ID
        assertEquals(3, in.getShort() & 0xffff);
        assertEquals(0, in.get()); // aux
        assertEquals(0, in.get()); // has StackNetId
        assertEquals(0, in.get()); // block runtime ID
        assertEquals(0, in.get()); // user data length
        assertEquals(1.25f, in.getFloat());
        assertEquals(65.0f, in.getFloat());
        assertEquals(-2.5f, in.getFloat());
        assertEquals(0.1f, in.getFloat(), 0.0001f);
        assertEquals(0.2f, in.getFloat(), 0.0001f);
        assertEquals(-0.1f, in.getFloat(), 0.0001f);
        assertEquals(0, in.get()); // empty actor data list
        assertEquals(0, in.get()); // not fishing
        assertFalse(in.hasRemaining());

        BedrockPacket remove = BedrockAddItemActorCodec.createRemoveActor(-1_000_000);
        assertEquals(0x0e, remove.packetId());
        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0x88, (byte) 0x7a},
                remove.payload());
    }

    @Test
    void encodesAndDecodesTakeItemActorAcknowledgement() {
        BedrockPacket packet = BedrockTakeItemActorCodec.createAcknowledgement(1_000_000, 42);
        assertEquals(0x11, packet.packetId());
        var decoded = BedrockTakeItemActorCodec.decode(packet);
        assertEquals(1_000_000, decoded.itemRuntimeId());
        assertEquals(42, decoded.playerRuntimeId());
    }

    @Test
    void rejectsUnknownItemsAndMalformedPickupRequests() {
        WorldItemEntity unknown = new WorldItemEntity(-2, 2,
                new ItemStack("nexora:missing_item", 1, 1),
                0, 0, 0, 0, 0, 0, 0, null);
        assertThrows(IllegalArgumentException.class, () ->
                BedrockAddItemActorCodec.createAddItemActor(unknown, REGISTRY));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockTakeItemActorCodec.decode(new BedrockPacket(0x10, 0, 0, new byte[0])));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockTakeItemActorCodec.decode(new BedrockPacket(0x11, 0, 0, new byte[] {1})));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockTakeItemActorCodec.decode(new BedrockPacket(0x11, 0, 0,
                        new byte[] {1, 2, 3})));
    }

    private static long readVarUInt64(ByteBuffer buffer) {
        long value = 0;
        for (int i = 0; i < 10; i++) {
            int current = Byte.toUnsignedInt(buffer.get());
            value |= (long) (current & 0x7f) << (7 * i);
            if ((current & 0x80) == 0) return value;
        }
        throw new AssertionError("VarUInt64 too long");
    }
}
