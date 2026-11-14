package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Protocol-2225 decoder for client SubChunkRequest packet 175. */
public final class BedrockSubChunkRequestPacketCodec {
    public static final int PACKET_ID = 175;
    public static final int MAX_REQUESTED_SUBCHUNKS = 4096;

    public record SubChunkPosition(int x, int y, int z) {
    }

    public record Offset(int x, int y, int z) {
        public Offset {
            requireInt8(x, "x");
            requireInt8(y, "y");
            requireInt8(z, "z");
        }
    }

    public record Request(int dimension, List<Offset> offsets, SubChunkPosition center) {
        public Request {
            if (dimension < -1 || dimension > 2) {
                throw new IllegalArgumentException("Unsupported Bedrock dimension");
            }
            offsets = List.copyOf(Objects.requireNonNull(offsets, "offsets"));
            if (offsets.isEmpty() || offsets.size() > MAX_REQUESTED_SUBCHUNKS) {
                throw new IllegalArgumentException("Subchunk request count is outside the allowed range");
            }
            offsets.forEach(value -> Objects.requireNonNull(value, "offset"));
            Objects.requireNonNull(center, "center");
        }

        public List<SubChunkPosition> absolutePositions() {
            List<SubChunkPosition> positions = new ArrayList<>(offsets.size());
            for (Offset offset : offsets) {
                positions.add(new SubChunkPosition(
                        Math.addExact(center.x(), offset.x()),
                        Math.addExact(center.y(), offset.y()),
                        Math.addExact(center.z(), offset.z())
                ));
            }
            return List.copyOf(positions);
        }
    }

    private BedrockSubChunkRequestPacketCodec() {
    }

    /** Decodes packet 175 payload fields in protocol schema order. */
    public static Request decodePayload(ByteBuffer input) {
        Objects.requireNonNull(input, "input");
        int dimension = BedrockBinary.readVarInt32(input);
        int count = BedrockBinary.readVarUInt32(input);
        if (count <= 0 || count > MAX_REQUESTED_SUBCHUNKS) {
            throw new IllegalArgumentException("Subchunk request count is outside the allowed range");
        }

        // Every offset is exactly three signed bytes.
        if (input.remaining() < count * 3L + 12L) {
            throw new IllegalArgumentException("Truncated SubChunkRequest payload");
        }
        List<Offset> offsets = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            offsets.add(new Offset(input.get(), input.get(), input.get()));
        }

        SubChunkPosition center = new SubChunkPosition(
                BedrockBinary.readLittleEndianInt32(input),
                BedrockBinary.readLittleEndianInt32(input),
                BedrockBinary.readLittleEndianInt32(input)
        );
        if (input.hasRemaining()) {
            throw new IllegalArgumentException("Trailing SubChunkRequest payload bytes");
        }
        return new Request(dimension, offsets, center);
    }

    private static void requireInt8(int value, String name) {
        if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
            throw new IllegalArgumentException(name + " offset must fit int8");
        }
    }
}
