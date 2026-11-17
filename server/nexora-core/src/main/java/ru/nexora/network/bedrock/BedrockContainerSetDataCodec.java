package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;

/** Protocol-2225 ContainerSetData packet codec for furnace progress indicators. */
public final class BedrockContainerSetDataCodec {
    public static final int PACKET_ID = 0x33;

    private BedrockContainerSetDataCodec() { }

    public static BedrockPacket create(int windowId, int property, int value) {
        if (windowId < 1 || windowId > 255) {
            throw new IllegalArgumentException("Container window ID must be 1..255");
        }
        Writer out = new Writer();
        out.byteValue(windowId);
        out.varInt32(property);
        out.varInt32(value);
        return new BedrockPacket(PACKET_ID, 0, 0, out.toByteArray());
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(12);

        void byteValue(int value) { output.write(value & 0xff); }

        void varInt32(int value) {
            long encoded = Integer.toUnsignedLong((value << 1) ^ (value >> 31));
            do {
                int next = (int) (encoded & 0x7f);
                encoded >>>= 7;
                if (encoded != 0) next |= 0x80;
                byteValue(next);
            } while (encoded != 0);
        }

        byte[] toByteArray() { return output.toByteArray(); }
    }
}
