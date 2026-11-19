package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BedrockLevelEventCodecTest {
    @Test
    void encodesExplosionParticleEventAndPosition() {
        BedrockPacket packet = BedrockLevelEventCodec.createExplosionParticle(12.5, 64.0, -3.25);
        assertEquals(0x19, packet.packetId());
        ByteBuffer in = ByteBuffer.wrap(packet.payload()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(BedrockLevelEventCodec.PARTICLE_EXPLOSION, readVarInt(in));
        assertEquals(12.5f, in.getFloat(), 0.0001f);
        assertEquals(64.0f, in.getFloat(), 0.0001f);
        assertEquals(-3.25f, in.getFloat(), 0.0001f);
        assertEquals(0, readVarInt(in));
        assertFalse(in.hasRemaining());
    }

    @Test
    void rejectsInvalidPositions() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockLevelEventCodec.createExplosionParticle(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockLevelEventCodec.createExplosionParticle(0, 2049, 0));
    }

    private static int readVarInt(ByteBuffer in) {
        int raw = 0;
        int shift = 0;
        while (shift < 35) {
            int next = Byte.toUnsignedInt(in.get());
            raw |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return (raw >>> 1) ^ -(raw & 1);
            shift += 7;
        }
        throw new AssertionError("VarInt too long");
    }
}
