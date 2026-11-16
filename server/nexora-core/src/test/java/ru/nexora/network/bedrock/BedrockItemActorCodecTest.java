package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.ItemStack;
import ru.nexora.world.WorldItemEntity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BedrockItemActorCodecTest {
    @Test
    void encodesDroppedItemActorDescriptorAndPosition() {
        BedrockItemRegistry registry = BedrockVanillaItemCatalog.createRegistry();
        WorldItemEntity entity = new WorldItemEntity(-1234, 1234,
                new ItemStack("minecraft:bread", 2, 64),
                1.25, 65.5, -2.5, 0.1, 0.2, -0.1, 1000, "owner-xuid");
        BedrockPacket packet = BedrockItemActorCodec.createAdd(entity, registry);
        assertEquals(BedrockItemActorCodec.ADD_ITEM_ACTOR_PACKET_ID, packet.packetId());

        Reader in = new Reader(packet.payload());
        assertEquals(-1234, in.varInt64());
        assertEquals(1234, in.varUInt64());
        assertEquals(261, in.int16());
        assertEquals(2, in.uint16());
        assertEquals(0, in.varUInt32());
        assertEquals(0, in.byteValue());
        assertEquals(0, in.varUInt32());
        assertEquals(0, in.varUInt32());
        assertEquals(1.25f, in.float32());
        assertEquals(65.5f, in.float32());
        assertEquals(-2.5f, in.float32());
        assertEquals(0.1f, in.float32(), 0.0001f);
        assertEquals(0.2f, in.float32(), 0.0001f);
        assertEquals(-0.1f, in.float32(), 0.0001f);
        assertEquals(0, in.varUInt32()); // empty metadata
        assertEquals(0, in.byteValue()); // not fishing
        assertTrue(in.exhausted());
    }

    @Test
    void encodesRemoveTakeAndMoveActorPackets() {
        BedrockPacket remove = BedrockItemActorCodec.createRemove(-1234);
        assertEquals(BedrockItemActorCodec.REMOVE_ACTOR_PACKET_ID, remove.packetId());
        assertEquals(-1234, new Reader(remove.payload()).varInt64());

        BedrockPacket take = BedrockItemActorCodec.createTake(5, 1234);
        assertEquals(BedrockItemActorCodec.TAKE_ITEM_ACTOR_PACKET_ID, take.packetId());
        Reader takeReader = new Reader(take.payload());
        assertEquals(5, takeReader.varUInt64());
        assertEquals(1234, takeReader.varUInt64());

        WorldItemEntity entity = new WorldItemEntity(-1234, 1234,
                new ItemStack("minecraft:bread", 1, 64),
                1, 65, 2, 0, 0, 0, 0, null);
        BedrockPacket move = BedrockItemActorCodec.createMove(entity, true, 99);
        assertEquals(BedrockItemActorCodec.MOVE_ACTOR_ABSOLUTE_PACKET_ID, move.packetId());
        Reader moveReader = new Reader(move.payload());
        assertEquals(1234, moveReader.varUInt64());
        assertEquals(1, moveReader.byteValue());
        assertEquals(1.0f, moveReader.float32());
        assertEquals(65.0f, moveReader.float32());
        assertEquals(2.0f, moveReader.float32());
        assertEquals(0, moveReader.byteValue());
        assertEquals(0, moveReader.byteValue());
        assertEquals(0, moveReader.byteValue());
        assertEquals(99, moveReader.varUInt64());
        assertTrue(moveReader.exhausted());
    }

    private static final class Reader {
        private final ByteBuffer buffer;
        Reader(byte[] payload) { buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN); }
        int byteValue() { return Byte.toUnsignedInt(buffer.get()); }
        int int16() { return buffer.getShort(); }
        int uint16() { return Short.toUnsignedInt(buffer.getShort()); }
        float float32() { return buffer.getFloat(); }
        long varUInt64() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                int next = Byte.toUnsignedInt(buffer.get());
                value |= (long) (next & 0x7f) << (7 * i);
                if ((next & 0x80) == 0) return value;
            }
            throw new AssertionError("invalid varuint64");
        }
        long varInt64() {
            long value = varUInt64();
            return (value >>> 1) ^ -(value & 1);
        }
        int varUInt32() { return (int) varUInt64(); }
        boolean exhausted() { return !buffer.hasRemaining(); }
    }
}
