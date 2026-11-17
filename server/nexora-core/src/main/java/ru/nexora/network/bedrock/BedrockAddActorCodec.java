package ru.nexora.network.bedrock;

import ru.nexora.world.WorldLivingEntity;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Protocol-2225 AddActor packet encoder for server-owned living actors. */
public final class BedrockAddActorCodec {
    public static final int ADD_ACTOR_PACKET_ID = 0x0d;

    private BedrockAddActorCodec() {
    }

    public static BedrockPacket createAddActor(WorldLivingEntity entity) {
        Objects.requireNonNull(entity, "entity");
        Writer out = new Writer();
        out.varInt64(entity.uniqueId());
        out.varUInt64(entity.runtimeId());
        out.string(entity.identifier());
        out.float32((float) entity.x());
        out.float32((float) entity.y());
        out.float32((float) entity.z());
        out.float32((float) entity.velocityX());
        out.float32((float) entity.velocityY());
        out.float32((float) entity.velocityZ());
        out.float32(0.0f); // Rotation X
        out.float32(0.0f); // Rotation Y
        out.float32(0.0f); // Head rotation
        out.float32(0.0f); // Body rotation

        out.varUInt32(1); // Synced attributes
        out.string("minecraft:health");
        out.float32(0.0f);
        out.float32(entity.health());
        out.float32(entity.maxHealth());

        out.varUInt32(0); // Synched actor data
        out.varUInt32(0); // PropertySyncData integer entries
        out.varUInt32(0); // PropertySyncData float entries
        out.varUInt32(0); // Actor links
        out.byteValue(0); // Optional PassengerOfBlockArguments: absent
        return new BedrockPacket(ADD_ACTOR_PACKET_ID, 0, 0, out.bytes());
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(128);

        void byteValue(int value) {
            out.write(value & 0xff);
        }

        void float32(float value) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Actor value must be finite");
            out.writeBytes(ByteBuffer.allocate(Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(value).array());
        }

        void varUInt32(long value) {
            if (value < 0 || value > 0xffff_ffffL) {
                throw new IllegalArgumentException("varuint32 out of range");
            }
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

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
