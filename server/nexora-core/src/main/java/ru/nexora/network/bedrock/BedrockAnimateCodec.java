package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/** Protocol-2225 actor animation packet helpers. */
public final class BedrockAnimateCodec {
    public static final int ANIMATE_PACKET_ID = 0x2c;
    public static final int ACTION_SWING = 1;
    public static final int MAIN_HAND = 0;
    public static final int OFF_HAND = 1;

    private BedrockAnimateCodec() {
    }

    /** Creates the server-to-client main/off-hand swing used to sync melee attacks to observers. */
    public static BedrockPacket createSwing(long actorRuntimeId, int hand) {
        if (actorRuntimeId <= 0) throw new IllegalArgumentException("actorRuntimeId must be positive");
        if (hand != MAIN_HAND && hand != OFF_HAND) {
            throw new IllegalArgumentException("hand must be main hand (0) or off hand (1)");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ACTION_SWING);
        writeVarUInt64(out, actorRuntimeId);
        writeFloat32(out, 0.0f);
        writeString(out, "Attack");
        out.write(hand);
        return new BedrockPacket(ANIMATE_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static void writeVarUInt64(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarUInt64(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeFloat32(ByteArrayOutputStream out, float value) {
        int bits = Float.floatToIntBits(value);
        out.write(bits & 0xff);
        out.write((bits >>> 8) & 0xff);
        out.write((bits >>> 16) & 0xff);
        out.write((bits >>> 24) & 0xff);
    }
}
