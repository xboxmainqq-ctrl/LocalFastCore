package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BedrockMoveActorAbsoluteCodecTest {
    @Test
    void encodesRuntimeIdGroundFlagPositionAndRotations() {
        BedrockPacket packet = BedrockMoveActorAbsoluteCodec.create(
                2_000_001, 1.25, 64.0, -3.5, 0, 90, 180, true);
        assertEquals(BedrockMoveActorAbsoluteCodec.MOVE_ACTOR_ABSOLUTE_PACKET_ID, packet.packetId());
        ByteBuffer in = ByteBuffer.wrap(packet.payload()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2_000_001, readVarUInt64(in));
        assertEquals(BedrockMoveActorAbsoluteCodec.FLAG_ON_GROUND, Byte.toUnsignedInt(in.get()));
        assertEquals(1.25f, in.getFloat(), 0.0001f);
        assertEquals(64.0f, in.getFloat(), 0.0001f);
        assertEquals(-3.5f, in.getFloat(), 0.0001f);
        assertEquals(0, Byte.toUnsignedInt(in.get()));
        assertEquals(64, Byte.toUnsignedInt(in.get()));
        assertEquals(128, Byte.toUnsignedInt(in.get()));
        assertFalse(in.hasRemaining());
    }

    @Test
    void rejectsInvalidActorPositionAndAngles() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockMoveActorAbsoluteCodec.create(0, 0, 64, 0, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockMoveActorAbsoluteCodec.create(1, Double.NaN, 64, 0, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockMoveActorAbsoluteCodec.create(1, 0, 64, 0, Float.NaN, 0, 0, false));
    }

    private static long readVarUInt64(ByteBuffer in) {
        long value = 0;
        for (int i = 0; i < 10; i++) {
            int next = Byte.toUnsignedInt(in.get());
            if (i == 9 && (next & 0xfe) != 0) throw new AssertionError("VarUInt64 overflow");
            value |= (long) (next & 0x7f) << (7 * i);
            if ((next & 0x80) == 0) return value;
        }
        throw new AssertionError("VarUInt64 too long");
    }
}
