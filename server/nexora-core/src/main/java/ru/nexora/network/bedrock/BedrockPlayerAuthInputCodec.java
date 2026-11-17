package ru.nexora.network.bedrock;

import ru.nexora.world.WorldChunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded protocol-2225 parser for the movement portion of PlayerAuthInput. */
public final class BedrockPlayerAuthInputCodec {
    public static final int PLAYER_AUTH_INPUT_PACKET_ID = 0x90;
    private static final int MAX_INPUT_ACTIONS = 256;

    private BedrockPlayerAuthInputCodec() {
    }

    public static PlayerInput decode(BedrockPacket packet) {
        if (packet == null || packet.packetId() != PLAYER_AUTH_INPUT_PACKET_ID) {
            throw new IllegalArgumentException("Expected PlayerAuthInput packet 0x90");
        }
        Reader in = new Reader(packet.payload());
        float pitch = in.float32();
        float yaw = in.float32();
        float x = in.float32();
        float y = in.float32();
        float z = in.float32();
        float motionX = in.float32();
        float motionZ = in.float32();
        float headYaw = in.float32();

        int actionCount = in.varUInt32();
        if (actionCount > MAX_INPUT_ACTIONS) {
            throw new IllegalArgumentException("PlayerAuthInput has too many input actions");
        }
        java.util.List<Integer> inputActions = new java.util.ArrayList<>(actionCount);
        for (int i = 0; i < actionCount; i++) inputActions.add(in.varInt32());

        int inputMode = in.varUInt32();
        int playMode = in.varUInt32();
        int interactionModel = in.varInt32();
        float interactYaw = in.float32();
        float interactPitch = in.float32();
        long tick = in.varUInt64();
        float deltaX = in.float32();
        float deltaY = in.float32();
        float deltaZ = in.float32();

        boolean hasItemUse = inputActions.contains(InputAction.PERFORM_ITEM_INTERACTION);
        boolean hasItemStackRequest = inputActions.contains(InputAction.PERFORM_ITEM_STACK_REQUEST);
        boolean hasBlockActions = inputActions.contains(InputAction.PERFORM_BLOCK_ACTIONS);
        boolean inPredictedVehicle = inputActions.contains(InputAction.IS_IN_CLIENT_PREDICTED_VEHICLE);
        boolean optionalSectionsPresent = hasItemUse || hasItemStackRequest
                || hasBlockActions || inPredictedVehicle;

        ItemUseTransaction itemUseTransaction = hasItemUse ? readPackedItemUse(in) : null;
        // ItemStackRequest remains opaque. The preceding packed item-use transaction
        // is still decoded so ordinary block placement can be validated server-side.
        if (hasItemStackRequest) {
            return new PlayerInput(pitch, yaw, x, y, z, motionX, motionZ, headYaw,
                    tick, true, true, inputMode, playMode, interactionModel,
                    interactYaw, interactPitch, deltaX, deltaY, deltaZ,
                    0, 0, 0, 0, 0, 0, 0,
                    java.util.List.copyOf(inputActions), java.util.List.of(), itemUseTransaction);
        }

        java.util.List<BlockAction> blockActions = new java.util.ArrayList<>();
        if (hasBlockActions) {
            int blockActionCount = in.varUInt32();
            if (blockActionCount > MAX_INPUT_ACTIONS) {
                throw new IllegalArgumentException("PlayerAuthInput has too many block actions");
            }
            for (int i = 0; i < blockActionCount; i++) {
                blockActions.add(new BlockAction(in.varInt32(), in.varInt32(),
                        in.varInt32(), in.varInt32(), in.varInt32()));
            }
        }
        if (inPredictedVehicle) {
            in.float32(); in.float32(); // vehicle rotation
            in.varInt64(); // client-predicted vehicle unique ID
        }

        float analogMoveX = in.float32();
        float analogMoveZ = in.float32();
        float cameraX = in.float32();
        float cameraY = in.float32();
        float cameraZ = in.float32();
        float rawMoveX = in.float32();
        float rawMoveZ = in.float32();
        in.requireExhausted();

        return new PlayerInput(pitch, yaw, x, y, z, motionX, motionZ, headYaw,
                tick, optionalSectionsPresent, false, inputMode, playMode, interactionModel,
                interactYaw, interactPitch, deltaX, deltaY, deltaZ,
                analogMoveX, analogMoveZ, cameraX, cameraY, cameraZ, rawMoveX, rawMoveZ,
                java.util.List.copyOf(inputActions), java.util.List.copyOf(blockActions), itemUseTransaction);
    }

    public record PlayerInput(
            float pitch, float yaw,
            float x, float y, float z,
            float motionX, float motionZ, float headYaw,
            long tick, boolean optionalSectionsPresent, boolean interactionPayloadUnparsed,
            int inputMode, int playMode, int interactionModel,
            float interactYaw, float interactPitch,
            float deltaX, float deltaY, float deltaZ,
            float analogMoveX, float analogMoveZ,
            float cameraX, float cameraY, float cameraZ,
            float rawMoveX, float rawMoveZ,
            java.util.List<Integer> inputActions,
            java.util.List<BlockAction> blockActions,
            ItemUseTransaction itemUseTransaction
    ) {
        public boolean hasInputAction(int action) {
            return inputActions.contains(action);
        }
    }

    /** Protocol-2225 PlayerAuthInputData flags relevant to initial movement simulation. */
    public static final class InputAction {
        public static final int SPRINTING = 20;
        public static final int JUMPING = 6;
        public static final int PERFORM_ITEM_INTERACTION = 34;
        public static final int PERFORM_BLOCK_ACTIONS = 35;
        public static final int PERFORM_ITEM_STACK_REQUEST = 36;
        public static final int IS_IN_CLIENT_PREDICTED_VEHICLE = 45;
        public static final int START_JUMPING = 31;
        public static final int START_USING_ITEM = 53;
        public static final int JUMP_PRESSED_RAW = 60;
        public static final int JUMP_CURRENT_RAW = 61;

        private InputAction() {
        }
    }

    public record BlockAction(int action, int x, int y, int z, int face) {
    }

    public record RawItemStack(
            int runtimeItemId, int count, long auxValue,
            boolean hasStackNetId, int stackNetId,
            long blockRuntimeId, byte[] userData
    ) {
        public RawItemStack {
            userData = userData.clone();
        }
        @Override public byte[] userData() { return userData.clone(); }
    }

    public record RawInventoryAction(
            int sourceType, int containerId, long sourceFlags, int slot,
            RawItemStack from, RawItemStack to
    ) {
    }

    public record ItemUseTransaction(
            java.util.List<RawInventoryAction> actions,
            int actionType, int triggerType,
            int x, int y, int z, int face, int slot, int hand,
            RawItemStack item,
            float fromX, float fromY, float fromZ,
            float clickX, float clickY, float clickZ,
            long targetBlockId, int clientPrediction, int cooldownState
    ) {
        public ItemUseTransaction {
            actions = java.util.List.copyOf(actions);
        }
    }

    private static ItemUseTransaction readPackedItemUse(Reader in) {
        int legacyRequestId = in.varInt32();
        if (legacyRequestId != 0) {
            throw new UnsupportedOperationException("Legacy item slot lists are not supported");
        }
        int actionCount = in.varUInt32();
        if (actionCount > 64) throw new IllegalArgumentException("Item-use transaction has too many inventory actions");
        java.util.List<RawInventoryAction> actions = new java.util.ArrayList<>(actionCount);
        for (int i = 0; i < actionCount; i++) {
            int sourceType = in.varInt32();
            int containerId = -1;
            long sourceFlags = 0;
            if (sourceType == 0) containerId = in.int8();
            else if (sourceType == 2) sourceFlags = in.varInt32();
            else if (sourceType != 1 && sourceType != 3) {
                throw new UnsupportedOperationException("Unsupported item-use inventory source type");
            }
            int slot = in.varInt32();
            RawItemStack from = readRawItemStack(in);
            RawItemStack to = readRawItemStack(in);
            actions.add(new RawInventoryAction(sourceType, containerId, sourceFlags, slot, from, to));
        }
        int actionType = in.varInt32();
        int triggerType = in.uint8();
        int x = in.varInt32();
        int y = in.varInt32();
        int z = in.varInt32();
        int face = in.uint8();
        int slot = in.varInt32();
        int hand = in.uint8();
        RawItemStack item = readRawItemStack(in);
        float fromX = in.float32();
        float fromY = in.float32();
        float fromZ = in.float32();
        float clickX = in.float32();
        float clickY = in.float32();
        float clickZ = in.float32();
        long targetBlockId = Integer.toUnsignedLong(in.varInt32());
        int clientPrediction = in.uint8();
        int cooldownState = in.uint8();
        if (triggerType > 2 || (actionType != 1 && face > 5) || hand > 1
                || clientPrediction > 1 || cooldownState > 1) {
            throw new IllegalArgumentException("Invalid item-use transaction enum value");
        }
        if (slot < 0 || slot >= 36 || y < WorldChunk.MIN_Y || y > WorldChunk.MAX_Y) {
            throw new IllegalArgumentException("Item-use target or slot is outside supported bounds");
        }
        if (actionType != 1 && (clickX < -0.001f || clickX > 1.001f
                || clickY < -0.001f || clickY > 1.001f
                || clickZ < -0.001f || clickZ > 1.001f)) {
            throw new IllegalArgumentException("Item-use click position is outside target block bounds");
        }
        return new ItemUseTransaction(actions, actionType, triggerType, x, y, z, face, slot, hand,
                item, fromX, fromY, fromZ, clickX, clickY, clickZ,
                targetBlockId, clientPrediction, cooldownState);
    }

    private static RawItemStack readRawItemStack(Reader in) {
        int runtimeItemId = in.int16();
        int count = in.uint16();
        long auxValue = in.varUInt32Long();
        boolean hasStackNetId = in.bool();
        int stackNetId = hasStackNetId ? in.varInt32() : 0;
        long blockRuntimeId = in.varUInt32Long();
        byte[] userData = in.byteArray(1_048_576);
        if (runtimeItemId == 0 && (count != 0 || auxValue != 0
                || blockRuntimeId != 0 || userData.length != 0 || hasStackNetId)) {
            throw new IllegalArgumentException("Malformed empty item-use descriptor");
        }
        if (runtimeItemId != 0 && (count < 1 || count > 99)) {
            throw new IllegalArgumentException("Invalid item-use stack count");
        }
        return new RawItemStack(runtimeItemId, count, auxValue, hasStackNetId,
                stackNetId, blockRuntimeId, userData);
    }

    private static final class Reader {
        private final ByteBuffer buffer;

        Reader(byte[] payload) {
            if (payload == null || payload.length > 65_536) {
                throw new IllegalArgumentException("PlayerAuthInput payload size is invalid");
            }
            buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }

        float float32() {
            require(Integer.BYTES);
            float value = buffer.getFloat();
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite PlayerAuthInput value");
            return value;
        }

        int int8() {
            require(1);
            return buffer.get();
        }

        int uint8() {
            require(1);
            return Byte.toUnsignedInt(buffer.get());
        }

        int int16() {
            require(Short.BYTES);
            return buffer.getShort();
        }

        int uint16() {
            require(Short.BYTES);
            return Short.toUnsignedInt(buffer.getShort());
        }

        boolean bool() {
            require(1);
            int value = Byte.toUnsignedInt(buffer.get());
            if (value > 1) throw new IllegalArgumentException("Invalid PlayerAuthInput boolean");
            return value != 0;
        }

        long varUInt32Long() {
            long value = 0;
            for (int i = 0; i < 5; i++) {
                require(1);
                int current = Byte.toUnsignedInt(buffer.get());
                if (i == 4 && (current & 0xf0) != 0) throw new IllegalArgumentException("VarUInt32 overflow");
                value |= (long) (current & 0x7f) << (7 * i);
                if ((current & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException("VarUInt32 is too long");
        }

        int varUInt32() {
            long value = varUInt32Long();
            if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("VarUInt32 exceeds supported integer range");
            return (int) value;
        }

        int varInt32() {
            long encoded = varUInt32Long();
            return (int) ((encoded >>> 1) ^ -(encoded & 1));
        }

        byte[] byteArray(int maximumLength) {
            long length = varUInt32Long();
            if (length > maximumLength) throw new IllegalArgumentException("PlayerAuthInput byte array exceeds size limit");
            require((int) length);
            byte[] result = new byte[(int) length];
            buffer.get(result);
            return result;
        }

        long varUInt64() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                require(1);
                int current = Byte.toUnsignedInt(buffer.get());
                if (i == 9 && (current & 0xfe) != 0) {
                    throw new IllegalArgumentException("VarUInt64 overflow");
                }
                value |= (long) (current & 0x7f) << (7 * i);
                if ((current & 0x80) == 0) {
                    if (value < 0) throw new IllegalArgumentException("Tick exceeds signed long range");
                    return value;
                }
            }
            throw new IllegalArgumentException("VarUInt64 is too long");
        }

        long varInt64() {
            long value = varUInt64();
            return (value >>> 1) ^ -(value & 1);
        }

        void requireExhausted() {
            if (buffer.hasRemaining()) throw new IllegalArgumentException("Trailing PlayerAuthInput bytes");
        }

        private void require(int bytes) {
            if (buffer.remaining() < bytes) throw new IllegalArgumentException("Truncated PlayerAuthInput packet");
        }
    }
}
