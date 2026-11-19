package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Protocol-2225 LevelEvent packet helpers. */
public final class BedrockLevelEventCodec {
    public static final int LEVEL_EVENT_PACKET_ID = 0x19;
    public static final int PARTICLE_EXPLOSION = 2025;

    private BedrockLevelEventCodec() {
    }

    public static BedrockPacket createExplosionParticle(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || y < -2048 || y > 2048) {
            throw new IllegalArgumentException("Invalid level-event position");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        writeVarInt(out, PARTICLE_EXPLOSION);
        writeFloat(out, (float) x);
        writeFloat(out, (float) y);
        writeFloat(out, (float) z);
        writeVarInt(out, 0);
        return new BedrockPacket(LEVEL_EVENT_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static void writeFloat(ByteArrayOutputStream out, float value) {
        out.writeBytes(ByteBuffer.allocate(Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(value).array());
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int encoded = (value << 1) ^ (value >> 31);
        while ((encoded & ~0x7f) != 0) {
            out.write((encoded & 0x7f) | 0x80);
            encoded >>>= 7;
        }
        out.write(encoded);
    }
}
