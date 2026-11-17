package ru.nexora.network.bedrock;

import ru.nexora.combat.ArrowProjectile;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Protocol-2225 AddActor encoder for a plain server-fired arrow projectile. */
public final class BedrockArrowActorCodec {
    private BedrockArrowActorCodec() {
    }

    public static BedrockPacket createSpawn(ArrowProjectile arrow) {
        Objects.requireNonNull(arrow, "arrow");
        Writer out = new Writer();
        out.varInt64(arrow.uniqueId());
        out.varUInt64(arrow.runtimeId());
        out.string("minecraft:arrow");
        out.float32((float) arrow.x());
        out.float32((float) arrow.y());
        out.float32((float) arrow.z());
        out.float32((float) arrow.velocityX());
        out.float32((float) arrow.velocityY());
        out.float32((float) arrow.velocityZ());
        out.float32(0.0f); // Pitch
        out.float32(0.0f); // Yaw
        out.float32(0.0f); // Head yaw
        out.float32(0.0f); // Body yaw
        out.varUInt32(0); // Attributes
        out.varUInt32(1); // One arrow metadata entry: DATA_ARROW_AUX_VALUE
        out.varUInt32(18);
        out.varUInt32(0); // Byte entity-data type
        out.byteValue(0); // Protocol 2225 includes the explicit data type byte
        out.byteValue(0); // Plain arrow auxiliary value
        out.varUInt32(0); // Integer properties
        out.varUInt32(0); // Float properties
        out.varUInt32(0); // Actor links
        out.byteValue(0); // PassengerOfBlockArguments absent
        return new BedrockPacket(0x0d, 0, 0, out.bytes());
    }

    public static BedrockPacket createMove(ArrowProjectile arrow, long tick) {
        Objects.requireNonNull(arrow, "arrow");
        return BedrockMoveActorAbsoluteCodec.create(arrow.runtimeId(),
                arrow.x(), arrow.y(), arrow.z(), 0.0f, 0.0f, 0.0f, false);
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(96);
        void byteValue(int value) { out.write(value & 0xff); }
        void float32(float value) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Arrow vector must be finite");
            out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array());
        }
        void varUInt32(long value) {
            if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("varuint32 out of range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }
        void varUInt64(long value) {
            if (value < 0) throw new IllegalArgumentException("varuint64 out of range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }
        void varInt64(long value) {
            long encoded = (value << 1) ^ (value >> 63);
            do {
                int next = (int) (encoded & 0x7f);
                encoded >>>= 7;
                if (encoded != 0) next |= 0x80;
                byteValue(next);
            } while (encoded != 0);
        }
        void string(String value) {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            varUInt32(bytes.length);
            out.writeBytes(bytes);
        }
        byte[] bytes() { return out.toByteArray(); }
    }
}
