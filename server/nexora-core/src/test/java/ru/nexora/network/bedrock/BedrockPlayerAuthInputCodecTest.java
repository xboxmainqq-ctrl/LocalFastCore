package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

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
    void decodesInputFlagsNeededByMovementSimulation() {
        BedrockPlayerAuthInputCodec.PlayerInput input =
                BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0,
                        movementPayload(0, 65, 0, 0, 14L, false,
                                BedrockPlayerAuthInputCodec.InputAction.JUMPING,
                                BedrockPlayerAuthInputCodec.InputAction.START_JUMPING,
                                BedrockPlayerAuthInputCodec.InputAction.JUMP_PRESSED_RAW)));

        assertEquals(java.util.List.of(6, 31, 60), input.inputActions());
        assertTrue(input.hasInputAction(BedrockPlayerAuthInputCodec.InputAction.JUMPING));
        assertFalse(input.hasInputAction(BedrockPlayerAuthInputCodec.InputAction.SPRINTING));
    }

    @Test
    void preservesDigitalAnalogAndRawMovementVectors() {
        byte[] payload = movementPayload(0.5f, 65.0f, 0.5f, 90.0f, 16L, false);
        ByteBuffer wire = ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        wire.putFloat(20, -1.0f); // digital move vector X
        wire.putFloat(24, 0.75f); // digital move vector Z
        wire.putFloat(payload.length - 28, -0.5f); // analog move vector X
        wire.putFloat(payload.length - 24, 0.25f); // analog move vector Z
        wire.putFloat(payload.length - 8, 1.0f); // raw move vector X
        wire.putFloat(payload.length - 4, -1.0f); // raw move vector Z

        BedrockPlayerAuthInputCodec.PlayerInput input =
                BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0, payload));

        assertEquals(-1.0f, input.motionX());
        assertEquals(0.75f, input.motionZ());
        assertEquals(-0.5f, input.analogMoveX());
        assertEquals(0.25f, input.analogMoveZ());
        assertEquals(1.0f, input.rawMoveX());
        assertEquals(-1.0f, input.rawMoveZ());
    }

    @Test
    void reportsOptionalActionSectionsWithoutMisreadingTheirPayload() {
        BedrockPlayerAuthInputCodec.PlayerInput input =
                BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0,
                        movementPayload(0, 65, 0, 0, 13L, true)));

        assertTrue(input.optionalSectionsPresent());
        assertTrue(input.interactionPayloadUnparsed());
        assertEquals(13L, input.tick());
    }

    @Test
    void decodesProtocol2225BlockActionsInWireOrder() {
        BedrockPlayerAuthInputCodec.PlayerInput input =
                BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0,
                        blockActionPayload(0.5f, 65.0f, 0.5f, 26, -2, 64, 3, 1, 15L)));

        assertEquals(1, input.blockActions().size());
        BedrockPlayerAuthInputCodec.BlockAction action = input.blockActions().getFirst();
        assertEquals(26, action.action());
        assertEquals(-2, action.x());
        assertEquals(64, action.y());
        assertEquals(3, action.z());
        assertEquals(1, action.face());
    }

    @Test
    void parsesVehicleRotationAndPredictedVehicleOnlyWhenFlagged() {
        byte[] payload = vehiclePayload();
        BedrockPlayerAuthInputCodec.PlayerInput input =
                BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0, payload));

        assertTrue(input.optionalSectionsPresent());
        assertFalse(input.interactionPayloadUnparsed());
        assertTrue(input.blockActions().isEmpty());
        assertTrue(input.hasInputAction(
                BedrockPlayerAuthInputCodec.InputAction.IS_IN_CLIENT_PREDICTED_VEHICLE));
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
        return movementPayload(x, y, z, yaw, tick, optional, new int[0]);
    }

    static byte[] movementPayload(
            float x, float y, float z, float yaw, long tick, boolean optional, int... inputActions
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFloat(output, 0); // pitch
        writeFloat(output, yaw);
        writeFloat(output, x);
        writeFloat(output, y);
        writeFloat(output, z);
        writeFloat(output, 0); // motion X
        writeFloat(output, 0); // motion Z
        writeFloat(output, yaw); // head yaw
        int[] encodedActions = optional && inputActions.length == 0
                ? new int[] {BedrockPlayerAuthInputCodec.InputAction.PERFORM_ITEM_INTERACTION}
                : inputActions;
        writeVarUInt(output, encodedActions.length);
        for (int action : encodedActions) writeVarInt(output, action);
        writeVarUInt(output, 2); // input mode
        writeVarUInt(output, 0); // play mode
        writeVarInt(output, 0); // interaction model
        writeFloat(output, 0); writeFloat(output, 0); // interact rotation
        writeVarULong(output, tick);
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0); // delta
        if (optional) {
            // Opaque item-use payload: the decoder must stop after the movement snapshot.
            output.write(0x7f);
            return output.toByteArray();
        }
        writeFloat(output, 0); writeFloat(output, 0); // analog move
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0); // camera orientation
        writeFloat(output, 0); writeFloat(output, 0); // raw move
        return output.toByteArray();
    }

    private static byte[] vehiclePayload() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i < 8; i++) writeFloat(output, 0);
        writeVarUInt(output, 1);
        writeVarInt(output, BedrockPlayerAuthInputCodec.InputAction.IS_IN_CLIENT_PREDICTED_VEHICLE);
        writeVarUInt(output, 2); writeVarUInt(output, 0); writeVarInt(output, 0);
        writeFloat(output, 0); writeFloat(output, 0);
        writeVarULong(output, 1);
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0);
        writeFloat(output, 0); writeFloat(output, 0); // vehicle rotation
        writeVarULong(output, 0); // predicted vehicle unique ID
        writeFloat(output, 0); writeFloat(output, 0); // analog move
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0); // camera orientation
        writeFloat(output, 0); writeFloat(output, 0); // raw move
        return output.toByteArray();
    }

    static byte[] blockActionPayload(
            float playerX, float playerY, float playerZ,
            int action, int x, int y, int z, int face, long tick
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFloat(output, 0); writeFloat(output, 30); // pitch/yaw
        writeFloat(output, playerX); writeFloat(output, playerY); writeFloat(output, playerZ);
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0);
        writeVarUInt(output, 1); // input actions
        writeVarInt(output, BedrockPlayerAuthInputCodec.InputAction.PERFORM_BLOCK_ACTIONS);
        writeVarUInt(output, 2); writeVarUInt(output, 0); writeVarInt(output, 0);
        writeFloat(output, 0); writeFloat(output, 0);
        writeVarULong(output, tick);
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0);
        writeVarUInt(output, 1);
        writeVarInt(output, action);
        writeVarInt(output, x); writeVarInt(output, y); writeVarInt(output, z); writeVarInt(output, face);
        writeFloat(output, 0); writeFloat(output, 0);
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0);
        writeFloat(output, 0); writeFloat(output, 0);
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
