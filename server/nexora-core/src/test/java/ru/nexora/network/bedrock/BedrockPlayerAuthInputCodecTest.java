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
    void distinguishesStartUsingItemFromStartSpinAttackInProtocol2225() {
        BedrockPlayerAuthInputCodec.PlayerInput usingItem =
                BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0,
                        movementPayload(0, 65, 0, 0, 15L, false,
                                BedrockPlayerAuthInputCodec.InputAction.START_USING_ITEM)));
        BedrockPlayerAuthInputCodec.PlayerInput spinAttack =
                BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0,
                        movementPayload(0, 65, 0, 0, 16L, false,
                                BedrockPlayerAuthInputCodec.InputAction.START_SPIN_ATTACK)));

        assertEquals(52, BedrockPlayerAuthInputCodec.InputAction.START_USING_ITEM);
        assertEquals(53, BedrockPlayerAuthInputCodec.InputAction.START_SPIN_ATTACK);
        assertTrue(usingItem.hasInputAction(52));
        assertFalse(usingItem.hasInputAction(53));
        assertTrue(spinAttack.hasInputAction(53));
        assertFalse(spinAttack.hasInputAction(52));
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
        assertFalse(input.interactionPayloadUnparsed());
        assertEquals(13L, input.tick());
        assertNotNull(input.itemUseTransaction());
        assertEquals(0, input.itemUseTransaction().actionType());
        assertEquals(1, input.itemUseTransaction().triggerType());
    }

    @Test
    void decodesPackedItemUseTransactionForBlockPlacement() {
        byte[] payload = itemUseMovementPayload(
                0.5f, 65.0f, 0.5f, 0.0f, 20L,
                0, 64, 0, 5, 0, 1, 2,
                Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.STONE)),
                0.5f, 65.0f, 0.5f);
        BedrockPlayerAuthInputCodec.PlayerInput input =
                BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0, payload));

        var use = input.itemUseTransaction();
        assertNotNull(use);
        assertEquals(0, use.actionType());
        assertEquals(1, use.triggerType());
        assertEquals(0, use.x());
        assertEquals(64, use.y());
        assertEquals(0, use.z());
        assertEquals(5, use.face());
        assertEquals(0, use.slot());
        assertEquals(1, use.item().runtimeItemId());
        assertEquals(2, use.item().count());
        assertEquals(20L, input.tick());
        assertFalse(input.interactionPayloadUnparsed());
    }

    @Test
    void decodesClickAirFoodUseWithUnsignedFaceSentinel() {
        byte[] payload = foodUseMovementPayload(
                0.5f, 65.0f, 0.5f, 0.0f, 21L,
                0, 261, 1, 0.5f, 66.62f, 0.5f);
        var input = BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0, payload));
        assertNotNull(input.itemUseTransaction());
        assertEquals(1, input.itemUseTransaction().actionType());
        assertEquals(255, input.itemUseTransaction().face());
        assertEquals(261, input.itemUseTransaction().item().runtimeItemId());
        assertEquals(1, input.itemUseTransaction().item().count());
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
    void acceptsSmallTrailingRetailClientExtensionBytes() {
        byte[] base = movementPayload(0.5f, 65.0f, 0.5f, 90.0f, 22L, false);
        byte[] extended = java.util.Arrays.copyOf(base, base.length + 8);
        extended[base.length] = 0x11;
        extended[base.length + 1] = 0x22;
        var input = BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0, extended));
        assertEquals(0.5f, input.x());
        assertEquals(65.0f, input.y());
        assertEquals(22L, input.tick());
    }

    @Test
    void rejectsOversizedTrailingExtensionBytes() {
        byte[] base = movementPayload(0, 65, 0, 0, 23L, false);
        byte[] extended = java.util.Arrays.copyOf(base, base.length + 65);
        assertThrows(IllegalArgumentException.class,
                () -> BedrockPlayerAuthInputCodec.decode(new BedrockPacket(0x90, 0, 0, extended)));
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
            // PackedItemUseLegacyInventoryTransaction: request ID 0, no inventory actions.
            writeVarInt(output, 0);
            writeVarUInt(output, 0); // inventory actions
            writeVarInt(output, 0); // Place
            output.write(1); // Player Input trigger
            writeVarInt(output, 0); writeVarInt(output, 64); writeVarInt(output, 0); // target block
            output.write(1); // top face
            writeVarInt(output, 0); // hotbar slot
            output.write(0); // main hand
            writeShort(output, 0); writeShort(output, 0); // empty item descriptor
            writeVarUInt(output, 0); output.write(0); writeVarUInt(output, 0); writeVarUInt(output, 0);
            writeFloat(output, 0); writeFloat(output, 65); writeFloat(output, 0); // from position
            writeFloat(output, 0.5f); writeFloat(output, 1); writeFloat(output, 0.5f); // click position
            writeVarUInt(output, 0); // target block runtime ID
            output.write(1); // predicted success
            output.write(0); // cooldown off
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

    static byte[] foodUseMovementPayload(
            float playerX, float playerY, float playerZ, float yaw, long tick,
            int slot, int runtimeItemId, int count,
            float fromX, float fromY, float fromZ
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFloat(output, 0); writeFloat(output, yaw);
        writeFloat(output, playerX); writeFloat(output, playerY); writeFloat(output, playerZ);
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, yaw);
        writeVarUInt(output, 1);
        writeVarInt(output, BedrockPlayerAuthInputCodec.InputAction.PERFORM_ITEM_INTERACTION);
        writeVarUInt(output, 2); writeVarUInt(output, 0); writeVarInt(output, 0);
        writeFloat(output, 0); writeFloat(output, 0);
        writeVarULong(output, tick);
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0); // delta
        writeVarInt(output, 0); // legacy request ID
        writeVarUInt(output, 0); // inventory actions
        writeVarInt(output, 1); // ItemUseActionType.ClickAir
        output.write(1); // Player Input trigger
        writeVarInt(output, 0); writeVarInt(output, 0); writeVarInt(output, 0);
        output.write(255); // no block face for click-air
        writeVarInt(output, slot);
        output.write(0); // main hand
        writeShort(output, runtimeItemId);
        writeShort(output, count);
        writeVarUInt(output, 0); // aux value
        output.write(0); // has StackNetId
        writeVarUInt(output, 0); // block runtime ID
        writeVarUInt(output, 0); // user data
        writeFloat(output, fromX); writeFloat(output, fromY); writeFloat(output, fromZ);
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0); // click position
        writeVarInt(output, 0); // no target block
        output.write(1); // prediction success
        output.write(0); // cooldown off
        writeFloat(output, 0); writeFloat(output, 0); // analog move
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0); // camera
        writeFloat(output, 0); writeFloat(output, 0); // raw move
        return output.toByteArray();
    }

    static byte[] itemUseMovementPayload(
            float playerX, float playerY, float playerZ, float yaw, long tick,
            int blockX, int blockY, int blockZ, int face, int slot,
            int runtimeItemId, int count, long blockRuntimeId,
            float fromX, float fromY, float fromZ
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFloat(output, 0); writeFloat(output, yaw);
        writeFloat(output, playerX); writeFloat(output, playerY); writeFloat(output, playerZ);
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, yaw);
        writeVarUInt(output, 1);
        writeVarInt(output, BedrockPlayerAuthInputCodec.InputAction.PERFORM_ITEM_INTERACTION);
        writeVarUInt(output, 2); writeVarUInt(output, 0); writeVarInt(output, 0);
        writeFloat(output, 0); writeFloat(output, 0);
        writeVarULong(output, tick);
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0);
        writeVarInt(output, 0); // legacy request ID
        writeVarUInt(output, 0); // inventory actions
        writeVarInt(output, 0); // ItemUseActionType.Place
        output.write(1); // ItemUseTriggerType.PlayerInput
        writeVarInt(output, blockX); writeVarInt(output, blockY); writeVarInt(output, blockZ);
        output.write(face);
        writeVarInt(output, slot);
        output.write(0); // HandSlot.Mainhand
        writeShort(output, runtimeItemId);
        writeShort(output, count);
        writeVarUInt(output, 0); // aux value
        output.write(0); // has StackNetId
        writeVarUInt(output, blockRuntimeId);
        writeVarUInt(output, 0); // user data
        writeFloat(output, fromX); writeFloat(output, fromY); writeFloat(output, fromZ);
        writeFloat(output, 0.5f); writeFloat(output, 0.5f); writeFloat(output, 0.5f);
        writeVarInt(output, BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.GRASS_BLOCK));
        output.write(1); // ClientInteractPrediction.Success
        output.write(0); // ClientCooldownState.Off
        writeFloat(output, 0); writeFloat(output, 0); // analog move
        writeFloat(output, 0); writeFloat(output, 0); writeFloat(output, 0); // camera
        writeFloat(output, 0); writeFloat(output, 0); // raw move
        return output.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value);
        output.write(value >>> 8);
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
