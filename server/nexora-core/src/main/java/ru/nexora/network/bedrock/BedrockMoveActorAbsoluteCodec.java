package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Protocol-2225 MoveActorAbsolute packet for authoritative actor position sync. */
public final class BedrockMoveActorAbsoluteCodec {
    public static final int MOVE_ACTOR_ABSOLUTE_PACKET_ID = 0x12;
    public static final int FLAG_ON_GROUND = 0x01;
    public static final int FLAG_TELEPORTED = 0x02;

    private BedrockMoveActorAbsoluteCodec() {
    }

    public static BedrockPacket create(
            long runtimeEntityId, double x, double y, double z,
            float pitch, float yaw, float headYaw, boolean onGround
    ) {
        return create(runtimeEntityId, x, y, z, pitch, yaw, headYaw, onGround, false);
    }

    /** Creates an absolute movement packet with the Bedrock teleport flag when requested. */
    public static BedrockPacket create(
            long runtimeEntityId, double x, double y, double z,
            float pitch, float yaw, float headYaw, boolean onGround, boolean teleported
    ) {
        if (runtimeEntityId <= 0 || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z) || Math.abs(x) > 30_000_000
                || Math.abs(z) > 30_000_000 || y < -2048 || y > 2048
                || !Float.isFinite(pitch) || !Float.isFinite(yaw) || !Float.isFinite(headYaw)) {
            throw new IllegalArgumentException("Invalid absolute actor movement");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(24);
        writeVarUInt64(out, runtimeEntityId);
        out.write((onGround ? FLAG_ON_GROUND : 0) | (teleported ? FLAG_TELEPORTED : 0));
        writeFloat(out, (float) x);
        writeFloat(out, (float) y);
        writeFloat(out, (float) z);
        out.write(angle(pitch));
        out.write(angle(yaw));
        out.write(angle(headYaw));
        return new BedrockPacket(MOVE_ACTOR_ABSOLUTE_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static int angle(float degrees) {
        return ((int) Math.floor((degrees % 360.0f) * 256.0f / 360.0f)) & 0xff;
    }

    private static void writeFloat(ByteArrayOutputStream out, float value) {
        out.writeBytes(ByteBuffer.allocate(Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(value).array());
    }

    private static void writeVarUInt64(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }
}
