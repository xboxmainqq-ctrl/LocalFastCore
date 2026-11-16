package ru.nexora.network.bedrock;

import ru.nexora.world.PlayerVitals;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Protocol-2225 health and hunger synchronization packets. */
public final class BedrockVitalsCodec {
    public static final int UPDATE_ATTRIBUTES_PACKET_ID = 0x1d;
    public static final int SET_HEALTH_PACKET_ID = 0x2a;

    private BedrockVitalsCodec() {
    }

    /** SetHealth is sent on initial spawn/respawn; fractional health is carried by UpdateAttributes. */
    public static BedrockPacket createSetHealth(PlayerVitals vitals) {
        Objects.requireNonNull(vitals, "vitals");
        Writer out = new Writer();
        out.varInt32(Math.round(vitals.health()));
        return new BedrockPacket(SET_HEALTH_PACKET_ID, 0, 0, out.toByteArray());
    }

    public static BedrockPacket createUpdateAttributes(long runtimeEntityId, PlayerVitals vitals, long inputTick) {
        Objects.requireNonNull(vitals, "vitals");
        if (runtimeEntityId <= 0 || inputTick < 0) {
            throw new IllegalArgumentException("Invalid player attribute target or tick");
        }
        List<Attribute> attributes = List.of(
                new Attribute(0, 20, vitals.health(), 0, 20, 20, "minecraft:health"),
                new Attribute(0, 20, vitals.hunger(), 0, 20, 20, "minecraft:player.hunger"),
                new Attribute(0, 20, vitals.saturation(), 0, 20, 5, "minecraft:player.saturation"),
                new Attribute(0, 4, vitals.exhaustion(), 0, 4, 0, "minecraft:player.exhaustion")
        );
        Writer out = new Writer();
        out.varUInt64(runtimeEntityId);
        out.varUInt32(attributes.size());
        for (Attribute attribute : attributes) {
            out.float32(attribute.min());
            out.float32(attribute.max());
            out.float32(attribute.current());
            out.float32(attribute.defaultMin());
            out.float32(attribute.defaultMax());
            out.float32(attribute.defaultValue());
            out.string(attribute.name());
            out.varUInt32(0); // no attribute modifiers
        }
        out.varUInt64(inputTick);
        return new BedrockPacket(UPDATE_ATTRIBUTES_PACKET_ID, 0, 0, out.toByteArray());
    }

    private record Attribute(
            float min, float max, float current,
            float defaultMin, float defaultMax, float defaultValue, String name
    ) {
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(160);

        void float32(float value) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Attribute values must be finite");
            output.writeBytes(ByteBuffer.allocate(Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(value).array());
        }

        void string(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            varUInt32(bytes.length);
            output.writeBytes(bytes);
        }

        void varInt32(int value) {
            varUInt32(Integer.toUnsignedLong((value << 1) ^ (value >> 31)));
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

        byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
