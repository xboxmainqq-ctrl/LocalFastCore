package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BedrockActorMotionCodecTest {
    @Test
    void encodesRuntimeIdVelocityAndTick() {
        BedrockPacket packet = BedrockActorMotionCodec.createSetActorMotion(
                2_000_001L, 0.6, 0.1, -0.25, 99);
        assertEquals(BedrockActorMotionCodec.SET_ACTOR_MOTION_PACKET_ID, packet.packetId());

        ByteBuffer in = ByteBuffer.wrap(packet.payload()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2_000_001L, readVarUInt64(in));
        assertEquals(0.6f, in.getFloat(), 0.0001f);
        assertEquals(0.1f, in.getFloat(), 0.0001f);
        assertEquals(-0.25f, in.getFloat(), 0.0001f);
        assertEquals(99, readVarUInt64(in));
        assertFalse(in.hasRemaining());
    }

    @Test
    void rejectsInvalidActorMotion() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockActorMotionCodec.createSetActorMotion(0, 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockActorMotionCodec.createSetActorMotion(1, Double.NaN, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockActorMotionCodec.createSetActorMotion(1, 101, 0, 0, 1));
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
