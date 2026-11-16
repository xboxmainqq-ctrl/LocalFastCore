package ru.nexora.network.bedrock;

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
        for (int i = 0; i < actionCount; i++) in.varInt32();

        int inputMode = in.varUInt32();
        int playMode = in.varUInt32();
        int interactionModel = in.varInt32();
        float interactYaw = in.float32();
        float interactPitch = in.float32();
        long tick = in.varUInt64();
        float deltaX = in.float32();
        float deltaY = in.float32();
        float deltaZ = in.float32();

        boolean hasItemUse = in.bool();
        boolean hasItemStackRequest = in.bool();
        boolean hasBlockActions = in.bool();
        boolean hasVehicleRotation = in.bool();
        boolean hasPredictedVehicle = in.bool();
        boolean optionalSectionsPresent = hasItemUse || hasItemStackRequest || hasBlockActions
                || hasVehicleRotation || hasPredictedVehicle;

        if (!optionalSectionsPresent) {
            in.float32(); in.float32(); // analog move vector
            in.float32(); in.float32(); in.float32(); // camera orientation
            in.float32(); in.float32(); // raw move vector
            in.requireExhausted();
        }

        return new PlayerInput(pitch, yaw, x, y, z, motionX, motionZ, headYaw,
                tick, optionalSectionsPresent, inputMode, playMode, interactionModel,
                interactYaw, interactPitch, deltaX, deltaY, deltaZ);
    }

    public record PlayerInput(
            float pitch, float yaw,
            float x, float y, float z,
            float motionX, float motionZ, float headYaw,
            long tick, boolean optionalSectionsPresent,
            int inputMode, int playMode, int interactionModel,
            float interactYaw, float interactPitch,
            float deltaX, float deltaY, float deltaZ
    ) {
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

        boolean bool() {
            require(1);
            int value = Byte.toUnsignedInt(buffer.get());
            if (value > 1) throw new IllegalArgumentException("Invalid PlayerAuthInput boolean");
            return value != 0;
        }

        int varUInt32() {
            long value = 0;
            for (int i = 0; i < 5; i++) {
                require(1);
                int current = Byte.toUnsignedInt(buffer.get());
                value |= (long) (current & 0x7f) << (7 * i);
                if ((current & 0x80) == 0) {
                    if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("VarUInt32 exceeds supported integer range");
                    return (int) value;
                }
            }
            throw new IllegalArgumentException("VarUInt32 is too long");
        }

        int varInt32() {
            int value = varUInt32();
            return (value >>> 1) ^ -(value & 1);
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

        void requireExhausted() {
            if (buffer.hasRemaining()) throw new IllegalArgumentException("Trailing PlayerAuthInput bytes");
        }

        private void require(int bytes) {
            if (buffer.remaining() < bytes) throw new IllegalArgumentException("Truncated PlayerAuthInput packet");
        }
    }
}
