package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPlayerAuthInputCodecTest {
    @Test
    void decodesProtocol2225MovementAndTick() {
        BedrockPlayerAuthInputCodec.PlayerInput input =
                BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0,
                        movementPayload(1.25f, 65.0f, -2.5f, 90.0f, 12L, false)));

        assertEquals(1.25f, input.x());
        assertEquals(65.0f, input.y());
        assertEquals(-2.5f, input.z());
        assertEquals(90.0f, input.yaw());
        assertEquals(12L, input.tick());
        assertFalse(input.optionalSectionsPresent());
    }

    @Test
    void reportsOptionalActionSectionsWithoutMisreadingTheirPayload() {
        BedrockPlayerAuthInputCodec.PlayerInput input =
                BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0,
                        movementPayload(0, 65, 0, 0, 13L, true)));

        assertTrue(input.optionalSectionsPresent());
        assertEquals(13L, input.tick());
    }

    @Test
    void rejectsWrongPacketIdTruncationAndNonFiniteCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPlayerAuthInputCodec.decode(new BedrockPacket(1, 0, 0, new byte[0])));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0, new byte[8])));

        byte[] invalid = movementPayload(0, 65, 0, 0, 1L, false);
        invalid[8] = 0;
        invalid[9] = 0;
        invalid[10] = (byte) 0xc0;
        invalid[11] = 0x7f; // NaN in X
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0, invalid)));
    }

    @Test
    void rejectsExcessiveInputActionCount() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i < 8; i++) writeFloat(output, 0);
        writeVarUInt(output, 257);
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0, output.toByteArray())));
    }

    static byte[] movementPayload(float x, float y, float z, float yaw, long tick, boolean optional) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFloat(output, 0); // pitch
        writeFloat(output, yaw);
        writeFloat(output, x);
        writeFloat(output, y);
        writeFloat(output, z);
        writeFloat(output, 0); // motion X
        writeFloat(output, 0); // motion Z
        writeFloat(output, yaw); // head yaw
        writeVarUInt(output, 0); // input actions
        writeVarUInt(output, 2); // input mode
        writeVarUInt(output, 0); // play mode
        writeVarInt(output, 0); // interaction model
        writeFloat(output, 0); writeFloat(output, 0); // interact rotation
        writeVarULong(output, tick);
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0); // delta
        output.write(optional ? 1 : 0); // item use
        output.write(0); // item stack request
        output.write(0); // block actions
        output.write(0); // vehicle rotation
        output.write(0); // predicted vehicle
        if (!optional) {
            writeFloat(output, 0); writeFloat(output, 0); // analog move
            writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0); // camera orientation
            writeFloat(output, 0); writeFloat(output, 0); // raw move
        } else {
            output.write(0x7f); // ignored optional-section payload
        }
        return output.toByteArray();
    }

    private static void writeFloat(ByteArrayOutputStream output, float value) {
        int bits = Float.floatToRawIntBits(value);
        output.write(bits);
        output.write(bits >>> 8);
        output.write(bits >>> 16);
        output.write(bits >>> 24);
    }

    private static void writeVarUInt(ByteArrayOutputStream output, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) next |= 0x80;
            output.write(next);
        } while (value != 0);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        writeVarUInt(output, Integer.toUnsignedLong((value << 1) ^ (value >> 31)));
    }

    private static void writeVarULong(ByteArrayOutputStream output, long value) {
        writeVarUInt(output, value);
    }
}
