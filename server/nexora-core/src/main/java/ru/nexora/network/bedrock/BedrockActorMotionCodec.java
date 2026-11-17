package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Protocol-2225 actor motion synchronization. */
public final class BedrockActorMotionCodec {
    public static final int SET_ACTOR_MOTION_PACKET_ID = 0x28;

    private BedrockActorMotionCodec() {
    }

    public static BedrockPacket createSetActorMotion(
            long runtimeEntityId, double velocityX, double velocityY, double velocityZ, long tick
    ) {
        if (runtimeEntityId <= 0 || tick < 0
                || !Double.isFinite(velocityX) || !Double.isFinite(velocityY) || !Double.isFinite(velocityZ)
                || Math.abs(velocityX) > 100 || Math.abs(velocityY) > 100 || Math.abs(velocityZ) > 100) {
            throw new IllegalArgumentException("Invalid actor motion update");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(32);
        writeVarUInt64(out, runtimeEntityId);
        writeFloat(out, (float) velocityX);
        writeFloat(out, (float) velocityY);
        writeFloat(out, (float) velocityZ);
        writeVarUInt64(out, tick);
        return new BedrockPacket(SET_ACTOR_MOTION_PACKET_ID, 0, 0, out.toByteArray());
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
