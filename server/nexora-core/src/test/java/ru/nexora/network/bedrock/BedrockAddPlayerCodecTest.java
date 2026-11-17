package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BedrockAddPlayerCodecTest {
    @Test
    void serializesProtocol2225AddPlayerPacketInWireOrder() {
        UUID uuid = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        BedrockPacket packet = BedrockAddPlayerCodec.createSpawn(new BedrockAddPlayerCodec.SpawnData(
                uuid, "Alex", 300, "platform-chat", 1.25, 65.62, -2.5,
                0.1f, 0.0f, -0.2f, 10.0f, 90.0f, 90.0f, "device-id", 13));

        assertEquals(0x0c, packet.packetId());
        ByteBuffer in = ByteBuffer.wrap(packet.payload()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(uuid.getMostSignificantBits(), in.getLong());
        assertEquals(uuid.getLeastSignificantBits(), in.getLong());
        assertEquals("Alex", readString(in));
        assertEquals(300, readVarUInt64(in));
        assertEquals("platform-chat", readString(in));
        assertEquals(1.25f, in.getFloat());
        assertEquals(65.62f, in.getFloat(), 0.0001f);
        assertEquals(-2.5f, in.getFloat());
        assertEquals(0.1f, in.getFloat(), 0.0001f);
        assertEquals(0.0f, in.getFloat());
        assertEquals(-0.2f, in.getFloat(), 0.0001f);
        assertEquals(10.0f, in.getFloat());
        assertEquals(90.0f, in.getFloat());
        assertEquals(90.0f, in.getFloat());
        assertEquals(0, in.getShort());
        assertEquals(0, in.getShort());
        assertEquals(0, readVarUInt32(in));
        assertEquals(0, Byte.toUnsignedInt(in.get())); // absent optional net ID
        assertEquals(0, readVarUInt32(in));
        assertEquals(0, readVarUInt32(in)); // empty item user data
        assertEquals(0, readVarUInt32(in)); // survival game type
        assertEquals(0, readVarUInt32(in)); // entity data
        assertEquals(0, readVarUInt32(in)); // property integer entries
        assertEquals(0, readVarUInt32(in)); // property float entries
        assertEquals(0L, in.getLong()); // raw player ID
        assertEquals(1, Byte.toUnsignedInt(in.get())); // Member
        assertEquals(0, Byte.toUnsignedInt(in.get())); // Any command
        assertEquals(1, readVarUInt32(in)); // one base ability layer
        assertEquals(1, Short.toUnsignedInt(in.getShort()));
        assertEquals(0x0000ffff, in.getInt());
        assertEquals(0x0000003f, in.getInt());
        assertEquals(0.05f, in.getFloat(), 0.0001f);
        assertEquals(1.0f, in.getFloat());
        assertEquals(0.1f, in.getFloat(), 0.0001f);
        assertEquals(0, readVarUInt32(in)); // actor links
        assertEquals(0, Byte.toUnsignedInt(in.get())); // no passenger block
        assertEquals("device-id", readString(in));
        assertEquals(13, in.getInt());
        assertFalse(in.hasRemaining());
    }

    @Test
    void rejectsInvalidSpawnData() {
        assertThrows(IllegalArgumentException.class, () -> new BedrockAddPlayerCodec.SpawnData(
                UUID.randomUUID(), "Alex", 0, "", 0, 64, 0, 0, 0, 0, 0, 0, 0, "", 13));
        assertThrows(IllegalArgumentException.class, () -> new BedrockAddPlayerCodec.SpawnData(
                UUID.randomUUID(), "Alex", 1, "", Double.NaN, 64, 0, 0, 0, 0, 0, 0, 0, "", 13));
    }

    private static String readString(ByteBuffer in) {
        int length = readVarUInt32(in);
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static int readVarUInt32(ByteBuffer in) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = Byte.toUnsignedInt(in.get());
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new AssertionError("Malformed VarUInt32");
    }
    private static long readVarUInt64(ByteBuffer in) {
        long value = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            int next = Byte.toUnsignedInt(in.get());
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new AssertionError("Malformed VarUInt64");
    }
}
