package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.world.PlayerVitals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BedrockVitalsCodecTest {
    @Test
    void encodesInitialHealthPacketAsVarInt() {
        BedrockPacket packet = BedrockVitalsCodec.createSetHealth(
                new PlayerVitals(13.5f, 8, 2.5f, 1.25f));
        assertEquals(BedrockVitalsCodec.SET_HEALTH_PACKET_ID, packet.packetId());
        assertArrayEquals(new byte[] {28}, packet.payload()); // round(13.5) = 14, ZigZag(14) = 28
    }

    @Test
    void encodesHealthHungerSaturationAndExhaustionAttributesWithInputTick() {
        BedrockPacket packet = BedrockVitalsCodec.createUpdateAttributes(
                123, new PlayerVitals(13.5f, 8, 2.5f, 1.25f), 300);
        assertEquals(BedrockVitalsCodec.UPDATE_ATTRIBUTES_PACKET_ID, packet.packetId());
        Reader in = new Reader(packet.payload());
        assertEquals(123, in.varUInt64());
        assertEquals(6, in.varUInt32());

        assertEquals(0.0f, in.float32());
        assertEquals(20.0f, in.float32());
        assertEquals(13.5f, in.float32());
        assertEquals(0.0f, in.float32());
        assertEquals(20.0f, in.float32());
        assertEquals(20.0f, in.float32());
        assertEquals("minecraft:health", in.string());
        assertEquals(0, in.varUInt32());

        in.skipAttribute("minecraft:player.hunger", 8.0f, 20.0f);
        in.skipAttribute("minecraft:player.saturation", 2.5f, 20.0f);
        in.skipAttribute("minecraft:player.exhaustion", 1.25f, 4.0f);
        in.skipAttribute("minecraft:player.level", 0.0f, 24791.0f);
        in.skipAttribute("minecraft:player.experience", 0.0f, 1.0f);
        assertEquals(300, in.varUInt64());
        assertTrue(in.exhausted());
    }

    @Test
    void encodesCurrentExperienceLevelAndProgress() {
        BedrockPacket packet = BedrockVitalsCodec.createUpdateAttributes(
                77, PlayerVitals.DEFAULT, 18, 0.625f, 44);
        Reader in = new Reader(packet.payload());
        assertEquals(77, in.varUInt64());
        assertEquals(6, in.varUInt32());
        in.skipAttribute("minecraft:health", 20.0f, 20.0f);
        in.skipAttribute("minecraft:player.hunger", 20.0f, 20.0f);
        in.skipAttribute("minecraft:player.saturation", 5.0f, 20.0f);
        in.skipAttribute("minecraft:player.exhaustion", 0.0f, 4.0f);
        assertEquals(0.0f, in.float32());
        assertEquals(24791.0f, in.float32());
        assertEquals(18.0f, in.float32());
        assertEquals(0.0f, in.float32());
        assertEquals(24791.0f, in.float32());
        assertEquals(0.0f, in.float32());
        assertEquals("minecraft:player.level", in.string());
        assertEquals(0, in.varUInt32());
        assertEquals(0.0f, in.float32());
        assertEquals(1.0f, in.float32());
        assertEquals(0.625f, in.float32());
        assertEquals(0.0f, in.float32());
        assertEquals(1.0f, in.float32());
        assertEquals(0.0f, in.float32());
        assertEquals("minecraft:player.experience", in.string());
        assertEquals(0, in.varUInt32());
        assertEquals(44, in.varUInt64());
        assertTrue(in.exhausted());
    }

    @Test
    void rejectsInvalidEntityOrTick() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockVitalsCodec.createUpdateAttributes(0, PlayerVitals.DEFAULT, 1));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockVitalsCodec.createUpdateAttributes(1, PlayerVitals.DEFAULT, -1));
    }

    private static final class Reader {
        private final ByteBuffer buffer;
        Reader(byte[] payload) { buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN); }
        float float32() { return buffer.getFloat(); }
        long varUInt64() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                int b = Byte.toUnsignedInt(buffer.get());
                value |= (long) (b & 0x7f) << (7 * i);
                if ((b & 0x80) == 0) return value;
            }
            throw new AssertionError("invalid varuint64");
        }
        int varUInt32() { return (int) varUInt64(); }
        String string() {
            int length = varUInt32();
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        void skipAttribute(String expectedName, float expectedCurrent, float expectedMax) {
            assertEquals(0.0f, float32());
            assertEquals(expectedMax, float32());
            assertEquals(expectedCurrent, float32());
            assertEquals(0.0f, float32());
            assertEquals(expectedMax, float32());
            float defaultValue = float32();
            assertTrue(defaultValue >= 0.0f);
            assertEquals(expectedName, string());
            assertEquals(0, varUInt32());
        }
        boolean exhausted() { return !buffer.hasRemaining(); }
    }
}
