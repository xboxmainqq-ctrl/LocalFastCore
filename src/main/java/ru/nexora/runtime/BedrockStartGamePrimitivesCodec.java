package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Exact protocol-2225 encoders for the small, fully specified value types
 * used by StartGame. The complete StartGame packet remains a separate codec.
 */
public final class BedrockStartGamePrimitivesCodec {
    public record Vec3(float x, float y, float z) {
        public Vec3 {
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
        }
    }

    public record Vec2(float x, float y) {
        public Vec2 {
            requireFinite(x, "x");
            requireFinite(y, "y");
        }
    }

    public record BlockPosition(int x, int y, int z) {
    }

    public record SyncedPlayerMovementSettings(int authoritativeMovementMode,
                                                int rewindHistorySize,
                                                boolean serverAuthoritativeBlockBreaking) {
        public SyncedPlayerMovementSettings(int rewindHistorySize,
                                            boolean serverAuthoritativeBlockBreaking) {
            this(1, rewindHistorySize, serverAuthoritativeBlockBreaking);
        }

        public SyncedPlayerMovementSettings {
            if (authoritativeMovementMode < 0 || authoritativeMovementMode > 2) {
                throw new IllegalArgumentException("authoritativeMovementMode must be 0, 1 or 2");
            }
            if (rewindHistorySize < 0) {
                throw new IllegalArgumentException("rewindHistorySize must not be negative");
            }
        }
    }

    private BedrockStartGamePrimitivesCodec() {
    }

    public static void writeVec3(ByteBuffer output, Vec3 value) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(value, "value");
        BedrockBinary.writeLittleEndianFloat(output, value.x());
        BedrockBinary.writeLittleEndianFloat(output, value.y());
        BedrockBinary.writeLittleEndianFloat(output, value.z());
    }

    public static Vec3 readVec3(ByteBuffer input) {
        Objects.requireNonNull(input, "input");
        return new Vec3(
                BedrockBinary.readLittleEndianFloat(input),
                BedrockBinary.readLittleEndianFloat(input),
                BedrockBinary.readLittleEndianFloat(input)
        );
    }

    public static void writeVec2(ByteBuffer output, Vec2 value) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(value, "value");
        BedrockBinary.writeLittleEndianFloat(output, value.x());
        BedrockBinary.writeLittleEndianFloat(output, value.y());
    }

    public static Vec2 readVec2(ByteBuffer input) {
        Objects.requireNonNull(input, "input");
        return new Vec2(
                BedrockBinary.readLittleEndianFloat(input),
                BedrockBinary.readLittleEndianFloat(input)
        );
    }

    public static void writeBlockPosition(ByteBuffer output, BlockPosition value) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(value, "value");
        BedrockBinary.writeVarInt32(output, value.x());
        BedrockBinary.writeVarInt32(output, value.y());
        BedrockBinary.writeVarInt32(output, value.z());
    }

    public static BlockPosition readBlockPosition(ByteBuffer input) {
        Objects.requireNonNull(input, "input");
        return new BlockPosition(
                BedrockBinary.readVarInt32(input),
                BedrockBinary.readVarInt32(input),
                BedrockBinary.readVarInt32(input)
        );
    }

    public static void writeMovementSettings(
            ByteBuffer output,
            SyncedPlayerMovementSettings value
    ) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(value, "value");
        BedrockBinary.writeVarInt32(output, value.authoritativeMovementMode());
        BedrockBinary.writeVarInt32(output, value.rewindHistorySize());
        output.put((byte) (value.serverAuthoritativeBlockBreaking() ? 1 : 0));
    }

    public static SyncedPlayerMovementSettings readMovementSettings(ByteBuffer input) {
        Objects.requireNonNull(input, "input");
        int authoritativeMovementMode = BedrockBinary.readVarInt32(input);
        int rewindHistorySize = BedrockBinary.readVarInt32(input);
        if (authoritativeMovementMode < 0 || authoritativeMovementMode > 2) {
            throw new IllegalArgumentException("Unsupported authoritative movement mode");
        }
        if (rewindHistorySize < 0) {
            throw new IllegalArgumentException("rewindHistorySize must not be negative");
        }
        if (!input.hasRemaining()) {
            throw new IllegalArgumentException("Truncated movement settings boolean");
        }
        return new SyncedPlayerMovementSettings(authoritativeMovementMode, rewindHistorySize, input.get() != 0);
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
