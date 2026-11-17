package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Protocol-2225 ContainerOpen/ContainerClose packet codec.
 *
 * ContainerOpen uses a signed VarInt block position and signed VarLong entity
 * unique ID. ContainerClose includes the container type and server-initiation
 * flag on the protocol-2225 wire format.
 */
public final class BedrockContainerPacketCodec {
    public static final int CONTAINER_OPEN_PACKET_ID = 0x2e;
    public static final int CONTAINER_CLOSE_PACKET_ID = 0x2f;
    public static final int CONTAINER_TYPE = 0;
    public static final int WORKBENCH_TYPE = 1;
    public static final int FURNACE_TYPE = 2;
    public static final int BLAST_FURNACE_TYPE = 27;
    public static final int SMOKER_TYPE = 28;
    public static final long BLOCK_CONTAINER_ENTITY_ID = -1L;

    private BedrockContainerPacketCodec() {
    }

    public static BedrockPacket createOpen(int windowId, int type, int x, int y, int z, long entityUniqueId) {
        validateWindowId(windowId);
        if (type < 0 || type > 255) throw new IllegalArgumentException("Container type must be 0..255");
        if (entityUniqueId < -1L) throw new IllegalArgumentException("Invalid container entity unique ID");

        Writer out = new Writer();
        out.byteValue(windowId);
        out.byteValue(type);
        out.varInt32(x);
        out.varInt32(y);
        out.varInt32(z);
        out.varInt64(entityUniqueId);
        return new BedrockPacket(CONTAINER_OPEN_PACKET_ID, 0, 0, out.toByteArray());
    }

    public static BedrockPacket createChestOpen(int windowId, int x, int y, int z) {
        return createOpen(windowId, CONTAINER_TYPE, x, y, z, BLOCK_CONTAINER_ENTITY_ID);
    }

    public static BedrockPacket createClose(int windowId, int type, boolean serverInitiated) {
        validateWindowId(windowId);
        if (type < -128 || type > 127) throw new IllegalArgumentException("Container type must fit signed byte");
        return new BedrockPacket(CONTAINER_CLOSE_PACKET_ID, 0, 0,
                new byte[] {(byte) windowId, (byte) type, (byte) (serverInitiated ? 1 : 0)});
    }

    public static DecodedClose decodeClose(BedrockPacket packet) {
        if (packet == null || packet.packetId() != CONTAINER_CLOSE_PACKET_ID) {
            throw new IllegalArgumentException("Expected ContainerClose packet 0x2f");
        }
        byte[] payload = packet.payload();
        if (payload.length != 3) throw new IllegalArgumentException("Invalid ContainerClose payload length");
        int windowId = Byte.toUnsignedInt(payload[0]);
        int type = payload[1];
        int flag = Byte.toUnsignedInt(payload[2]);
        validateWindowId(windowId);
        if (flag > 1) throw new IllegalArgumentException("Invalid ContainerClose server flag");
        return new DecodedClose(windowId, type, flag != 0);
    }

    private static void validateWindowId(int windowId) {
        if (windowId < 1 || windowId > 255) {
            throw new IllegalArgumentException("Container window ID must be 1..255");
        }
    }

    public record DecodedClose(int windowId, int type, boolean serverInitiated) {
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(24);

        void byteValue(int value) {
            output.write(value & 0xff);
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

        byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
