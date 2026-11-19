package ru.nexora.network.bedrock;

import ru.nexora.combat.FireballProjectile;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Protocol-2225 AddActor and movement packets for server-owned fireballs. */
public final class BedrockFireballActorCodec {
    private BedrockFireballActorCodec() {
    }

    public static BedrockPacket createSpawn(FireballProjectile fireball) {
        Objects.requireNonNull(fireball, "fireball");
        Writer out = new Writer();
        out.varInt64(fireball.uniqueId());
        out.varUInt64(fireball.runtimeId());
        out.string(fireball.identifier());
        out.float32((float) fireball.x());
        out.float32((float) fireball.y());
        out.float32((float) fireball.z());
        out.float32((float) fireball.velocityX());
        out.float32((float) fireball.velocityY());
        out.float32((float) fireball.velocityZ());
        out.float32(0.0f);
        out.float32(0.0f);
        out.float32(0.0f);
        out.float32(0.0f);
        out.varUInt32(0); // Synced attributes
        out.varUInt32(0); // Synched actor data
        out.varUInt32(0); // Integer properties
        out.varUInt32(0); // Float properties
        out.varUInt32(0); // Actor links
        out.byteValue(0); // PassengerOfBlockArguments absent
        return new BedrockPacket(0x0d, 0, 0, out.bytes());
    }

    public static BedrockPacket createMove(FireballProjectile fireball) {
        Objects.requireNonNull(fireball, "fireball");
        return BedrockMoveActorAbsoluteCodec.create(fireball.runtimeId(),
                fireball.x(), fireball.y(), fireball.z(), 0.0f, 0.0f, 0.0f, false);
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(96);
        void byteValue(int value) { out.write(value & 0xff); }
        void float32(float value) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Fireball vector must be finite");
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
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            varUInt32(bytes.length);
            out.writeBytes(bytes);
        }
        byte[] bytes() { return out.toByteArray(); }
    }
}
