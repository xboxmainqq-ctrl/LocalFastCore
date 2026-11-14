package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Compact movement messages for the private NEXORA protocol v1. */
public final class NexoraMovementPacketCodec {
    private static final byte[] MAGIC = {'N', 'X', 'R', 'A'};
    private static final int TYPE_CLIENT_MOVE = 7;
    private static final int TYPE_SERVER_POSITION = 8;
    private static final int PACKET_BYTES = 31;

    private NexoraMovementPacketCodec() {
    }

    public static byte[] encodeClientMove(NexoraMovementValidator.Position position) {
        return encode(TYPE_CLIENT_MOVE, position);
    }

    public static byte[] encodeServerPosition(NexoraMovementValidator.Position position) {
        return encode(TYPE_SERVER_POSITION, position);
    }

    public static NexoraMovementValidator.Position decodeClientMove(ByteBuffer source) {
        return decode(source, TYPE_CLIENT_MOVE);
    }

    public static NexoraMovementValidator.Position decodeServerPosition(ByteBuffer source) {
        return decode(source, TYPE_SERVER_POSITION);
    }

    private static byte[] encode(int type, NexoraMovementValidator.Position position) {
        Objects.requireNonNull(position, "position");
        if (!Double.isFinite(position.x()) || !Double.isFinite(position.y())
                || !Double.isFinite(position.z())) {
            throw new IllegalArgumentException("Movement coordinates must be finite");
        }
        ByteBuffer output = ByteBuffer.allocate(PACKET_BYTES).order(ByteOrder.BIG_ENDIAN);
        output.put(MAGIC).put((byte) 1).put((byte) 0).put((byte) type);
        output.putDouble(position.x()).putDouble(position.y()).putDouble(position.z());
        return output.array();
    }

    private static NexoraMovementValidator.Position decode(ByteBuffer source, int expectedType) {
        Objects.requireNonNull(source, "source");
        ByteBuffer input = source.slice().order(ByteOrder.BIG_ENDIAN);
        if (input.remaining() != PACKET_BYTES) {
            throw new IllegalArgumentException("Invalid NEXORA movement packet size");
        }
        for (byte expected : MAGIC) {
            if (input.get() != expected) throw new IllegalArgumentException("Invalid NEXORA packet magic");
        }
        int major = Byte.toUnsignedInt(input.get());
        int minor = Byte.toUnsignedInt(input.get());
        int type = Byte.toUnsignedInt(input.get());
        if (major != 1 || minor != 0 || type != expectedType) {
            throw new IllegalArgumentException("Unexpected NEXORA movement packet");
        }
        double x = input.getDouble();
        double y = input.getDouble();
        double z = input.getDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000d || Math.abs(y) > 30_000_000d
                || Math.abs(z) > 30_000_000d) {
            throw new IllegalArgumentException("Invalid NEXORA movement coordinates");
        }
        return new NexoraMovementValidator.Position(x, y, z);
    }
}
