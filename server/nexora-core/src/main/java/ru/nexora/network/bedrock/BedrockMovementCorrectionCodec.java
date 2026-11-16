package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Encoder for the protocol-2225 CorrectPlayerMovePrediction packet (0xA1). */
public final class BedrockMovementCorrectionCodec {
    public static final int PACKET_ID = 0xA1;

    private BedrockMovementCorrectionCodec() {
    }

    public static BedrockPacket createPlayerCorrection(
            double x, double y, double z,
            double velocityX, double velocityY, double velocityZ,
            boolean onGround, long inputTick
    ) {
        if (!finite(x, y, z, velocityX, velocityY, velocityZ)
                || inputTick < 0
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) {
            throw new IllegalArgumentException("Invalid movement correction state");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(27);
        out.write(0); // RewindType.Player
        writeFloat(out, x);
        writeFloat(out, y);
        writeFloat(out, z);
        writeFloat(out, velocityX);
        writeFloat(out, velocityY);
        writeFloat(out, velocityZ);
        out.write(onGround ? 1 : 0);
        writeVarUInt64(out, inputTick);
        return new BedrockPacket(PACKET_ID, 0, 0, out.toByteArray());
    }

    private static void writeFloat(ByteArrayOutputStream out, double value) {
        out.writeBytes(ByteBuffer.allocate(Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat((float) value).array());
    }

    private static void writeVarUInt64(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
