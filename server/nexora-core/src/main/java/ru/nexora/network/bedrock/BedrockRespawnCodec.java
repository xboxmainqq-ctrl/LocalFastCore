package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Protocol-2225 death and respawn packet encoders. */
public final class BedrockRespawnCodec {
    public static final int MOVE_PLAYER_PACKET_ID = 0x13;
    public static final int DEATH_INFO_PACKET_ID = 0xbd;
    public static final int RESPAWN_PACKET_ID = 0x2d;
    public static final int POSITION_MODE_RESPAWN = 1;

    private BedrockRespawnCodec() {
    }

    public static BedrockPacket createDeathInfo(String translationKey) {
        if (translationKey == null || !translationKey.matches("[a-z0-9_.-]{1,128}")) {
            throw new IllegalArgumentException("Invalid death translation key");
        }
        Writer out = new Writer();
        out.string(translationKey);
        out.varUInt32(0); // no message parameters
        return new BedrockPacket(DEATH_INFO_PACKET_ID, 0, 0, out.toByteArray());
    }

    public static BedrockPacket createSearchingForSpawn(
            double x, double y, double z, long runtimeEntityId
    ) {
        validatePosition(x, y, z);
        if (runtimeEntityId <= 0) throw new IllegalArgumentException("Invalid respawning player runtime ID");
        Writer out = new Writer();
        out.float32(x); out.float32(y); out.float32(z);
        out.byteValue(0); // RespawnState.SearchingForSpawn
        out.varUInt64(runtimeEntityId);
        return new BedrockPacket(RESPAWN_PACKET_ID, 0, 0, out.toByteArray());
    }

    public static BedrockPacket createRespawnMove(
            long runtimeEntityId, double x, double y, double z,
            float pitch, float yaw, boolean onGround, long tick
    ) {
        validatePosition(x, y, z);
        if (runtimeEntityId <= 0 || !Float.isFinite(pitch) || !Float.isFinite(yaw) || tick < 0) {
            throw new IllegalArgumentException("Invalid respawn movement state");
        }
        Writer out = new Writer();
        out.varUInt64(runtimeEntityId);
        out.float32(x); out.float32(y); out.float32(z);
        out.float32(pitch); out.float32(yaw); out.float32(yaw);
        out.byteValue(POSITION_MODE_RESPAWN);
        out.byteValue(onGround ? 1 : 0);
        out.varUInt64(0); // not riding
        out.varUInt64(tick);
        return new BedrockPacket(MOVE_PLAYER_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static void validatePosition(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || y < -2048 || y > 2048) {
            throw new IllegalArgumentException("Invalid respawn position");
        }
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(64);
        void byteValue(int value) { output.write(value & 0xff); }
        void float32(double value) {
            if (!Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE) {
                throw new IllegalArgumentException("Non-finite packet float");
            }
            output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat((float) value).array());
        }
        void string(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            varUInt32(bytes.length);
            output.writeBytes(bytes);
        }
        void varUInt32(long value) {
            if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("VarUInt32 out of range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                output.write(next);
            } while (value != 0);
        }
        void varUInt64(long value) {
            if (value < 0) throw new IllegalArgumentException("VarUInt64 out of range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                output.write(next);
            } while (value != 0);
        }
        byte[] toByteArray() { return output.toByteArray(); }
    }
}
