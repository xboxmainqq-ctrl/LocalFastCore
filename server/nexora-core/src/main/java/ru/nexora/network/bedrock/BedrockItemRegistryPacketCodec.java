package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Protocol-2225 ItemRegistry packet encoder (packet 0xA2).
 *
 * Item names use the length-prefixed UTF-8 representation used by the current
 * Bedrock packet implementations. Component data is a complete network-NBT
 * named tag supplied by the immutable registry; it is never synthesized here.
 */
public final class BedrockItemRegistryPacketCodec {
    public static final int ITEM_REGISTRY_PACKET_ID = 0xA2;
    public static final int MAX_ENTRIES = 4096;
    public static final int MAX_COMPONENT_DATA_BYTES = 1_048_576;
    private static final int MAX_PACKET_BYTES = 4 * 1024 * 1024;

    private BedrockItemRegistryPacketCodec() {
    }

    public static BedrockPacket createPacket(BedrockItemRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        if (registry.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Item registry exceeds entry limit");
        }

        Writer out = new Writer();
        out.varUInt32(registry.size());
        for (BedrockItemRegistry.Entry entry : registry.entries()) {
            byte[] name = entry.namespacedId().getBytes(StandardCharsets.UTF_8);
            if (name.length == 0 || name.length > 256) {
                throw new IllegalArgumentException("Item registry identifier length is invalid");
            }
            byte[] componentData = entry.componentData();
            if (componentData.length > MAX_COMPONENT_DATA_BYTES) {
                throw new IllegalArgumentException("Item component data exceeds size limit");
            }

            out.string(name);
            out.int16(entry.networkDefinition().runtimeItemId());
            out.byteValue(entry.componentBased() ? 1 : 0);
            out.varInt32(entry.itemVersion());
            out.bytes(componentData);
        }
        return new BedrockPacket(ITEM_REGISTRY_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(1024);

        void byteValue(int value) {
            output.write(value & 0xff);
            checkSize();
        }

        void int16(int value) {
            byteValue(value);
            byteValue(value >>> 8);
        }

        void varInt32(int value) {
            varUInt32(Integer.toUnsignedLong((value << 1) ^ (value >> 31)));
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

        void string(byte[] value) {
            varUInt32(value.length);
            bytes(value);
        }

        void bytes(byte[] value) {
            output.writeBytes(value);
            checkSize();
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }

        private void checkSize() {
            if (output.size() > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("ItemRegistry packet exceeds size limit");
            }
        }
    }
}
