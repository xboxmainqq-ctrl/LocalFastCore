package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPlayerListCodecTest {
    @Test
    void encodesProtocol2225AddEntryInWireOrder() {
        UUID uuid = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        byte[] skin = {0x11, 0x22, 0x33};
        BedrockPlayerListCodec.AddEntry entry = new BedrockPlayerListCodec.AddEntry(
                uuid, 300, "Alex", "1234567890", "playfab-id", "platform-id",
                13, skin, false, true, false, 0x11223344);

        BedrockPacket packet = BedrockPlayerListCodec.createAdd(List.of(entry));
        assertEquals(0x3f, packet.packetId());

        ByteBuffer in = ByteBuffer.wrap(packet.payload()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, readVarUInt32(in));
        assertEquals(1, readVarUInt32(in)); // Nukkit-MOT entry action count
        assertEquals(BedrockPlayerListCodec.ACTION_ADD, Byte.toUnsignedInt(in.get()));
        assertEquals(uuid.getMostSignificantBits(), in.getLong());
        assertEquals(uuid.getLeastSignificantBits(), in.getLong());
        assertEquals(300, readVarInt64(in));
        assertEquals("Alex", readString(in));
        assertEquals("1234567890", readString(in));
        assertEquals("playfab-id", readString(in));
        assertEquals("platform-id", readString(in));
        assertEquals(13, in.getInt());
        assertArrayEquals(skin, readBytes(in, skin.length));
        assertFalse(readBoolean(in));
        assertTrue(readBoolean(in));
        assertFalse(readBoolean(in));
        assertEquals(0x11223344, in.getInt());
        assertFalse(in.hasRemaining());
    }

    @Test
    void encodesRemoveEntryWithActionBeforeUuid() {
        UUID uuid = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        BedrockPacket packet = BedrockPlayerListCodec.createRemove(List.of(uuid));
        assertEquals(0x3f, packet.packetId());

        ByteBuffer in = ByteBuffer.wrap(packet.payload()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, readVarUInt32(in));
        assertEquals(0, readVarUInt32(in)); // Nukkit-MOT remove entry action count
        assertEquals(BedrockPlayerListCodec.ACTION_REMOVE, Byte.toUnsignedInt(in.get()));
        assertEquals(uuid.getMostSignificantBits(), in.getLong());
        assertEquals(uuid.getLeastSignificantBits(), in.getLong());
        assertFalse(in.hasRemaining());
    }

    @Test
    void rejectsInvalidAddEntryAndEmptyLists() {
        assertThrows(IllegalArgumentException.class, () -> BedrockPlayerListCodec.createAdd(List.of()));
        assertThrows(IllegalArgumentException.class, () -> BedrockPlayerListCodec.createRemove(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BedrockPlayerListCodec.AddEntry(
                UUID.randomUUID(), 1, "Alex", "", "", "", 99,
                new byte[]{1}, false, false, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new BedrockPlayerListCodec.AddEntry(
                UUID.randomUUID(), 1, " ", "", "", "", 13,
                new byte[]{1}, false, false, false, 0));
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

    private static long readVarInt64(ByteBuffer in) {
        long value = 0;
        int shift = 0;
        for (int index = 0; index < 10; index++) {
            int next = Byte.toUnsignedInt(in.get());
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return (value >>> 1) ^ -(value & 1);
            shift += 7;
        }
        throw new AssertionError("Malformed VarInt64");
    }

    private static String readString(ByteBuffer in) {
        int length = readVarUInt32(in);
        byte[] bytes = readBytes(in, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(ByteBuffer in, int length) {
        byte[] bytes = new byte[length];
        in.get(bytes);
        return bytes;
    }

    private static boolean readBoolean(ByteBuffer in) {
        int value = Byte.toUnsignedInt(in.get());
        if (value > 1) throw new AssertionError("Invalid boolean");
        return value == 1;
    }
}
