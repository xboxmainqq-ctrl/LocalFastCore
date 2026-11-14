package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-2225 server SubChunkPacket (174) encoder for non-cached responses.
 * Each entry includes the subchunk blob and explicit no-heightmap markers.
 */
public final class BedrockSubChunkPacketCodec {
    public static final int PACKET_ID = 174;
    public static final int RESULT_SUCCESS = 1;
    public static final int RESULT_LEVEL_CHUNK_DOES_NOT_EXIST = 2;
    public static final int RESULT_WRONG_DIMENSION = 3;
    public static final int RESULT_PLAYER_DOES_NOT_EXIST = 4;
    public static final int RESULT_INDEX_OUT_OF_BOUNDS = 5;
    public static final int RESULT_SUCCESS_ALL_AIR = 6;

    private static final int MAX_ENTRIES = 64;
    private static final int MAX_SUBCHUNK_BYTES = 128 * 1024;
    private static final int MAX_PACKET_BYTES = 8 * 1024 * 1024;

    public record Position(int x, int y, int z) {
    }

    public record Offset(int x, int y, int z) {
        public Offset {
            requireInt8(x, "x");
            requireInt8(y, "y");
            requireInt8(z, "z");
        }
    }

    public record Entry(Offset offset, int result, byte[] serializedSubChunk) {
        public Entry {
            Objects.requireNonNull(offset, "offset");
            serializedSubChunk = Objects.requireNonNull(serializedSubChunk, "serializedSubChunk").clone();
            if (result < RESULT_SUCCESS || result > RESULT_SUCCESS_ALL_AIR) {
                throw new IllegalArgumentException("Unknown protocol-2225 subchunk result");
            }
            if (serializedSubChunk.length > MAX_SUBCHUNK_BYTES) {
                throw new IllegalArgumentException("Serialized subchunk exceeds the configured limit");
            }
            if (result == RESULT_SUCCESS && serializedSubChunk.length == 0) {
                throw new IllegalArgumentException("Successful subchunk responses require serialized data");
            }
            if (result != RESULT_SUCCESS && result != RESULT_SUCCESS_ALL_AIR
                    && serializedSubChunk.length != 0) {
                throw new IllegalArgumentException("Error subchunk responses must not carry terrain data");
            }
            if (result == RESULT_SUCCESS_ALL_AIR && serializedSubChunk.length != 0) {
                throw new IllegalArgumentException("SuccessAllAir must not carry terrain data");
            }
        }

        @Override
        public byte[] serializedSubChunk() {
            return serializedSubChunk.clone();
        }

        public static Entry success(Offset offset, byte[] serializedSubChunk) {
            return new Entry(offset, RESULT_SUCCESS, serializedSubChunk);
        }

        public static Entry allAir(Offset offset) {
            return new Entry(offset, RESULT_SUCCESS_ALL_AIR, new byte[0]);
        }

        public static Entry error(Offset offset, int result) {
            if (result == RESULT_SUCCESS || result == RESULT_SUCCESS_ALL_AIR) {
                throw new IllegalArgumentException("Use success() or allAir() for successful results");
            }
            return new Entry(offset, result, new byte[0]);
        }
    }

    private BedrockSubChunkPacketCodec() {
    }

    /** Encodes a cache-disabled packet; cache blob IDs are deliberately unsupported. */
    public static ByteBuffer encodePayload(int dimension, Position center, List<Entry> entries) {
        Objects.requireNonNull(center, "center");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (dimension < -1 || dimension > 2) {
            throw new IllegalArgumentException("Unsupported Bedrock dimension");
        }
        if (entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("SubChunkPacket entry count is outside the allowed range");
        }

        long estimated = 1L + 5 + 12 + 5;
        for (Entry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            estimated += 3L + 1 + BedrockGamePacket.varUInt32Size(entry.serializedSubChunk.length)
                    + entry.serializedSubChunk.length + 2;
        }
        if (estimated > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("SubChunkPacket exceeds the configured byte limit");
        }

        ByteBuffer out = ByteBuffer.allocate((int) estimated);
        writeBoolean(out, false); // Cache Enabled
        BedrockBinary.writeVarInt32(out, dimension);
        BedrockBinary.writeLittleEndianInt32(out, center.x());
        BedrockBinary.writeLittleEndianInt32(out, center.y());
        BedrockBinary.writeLittleEndianInt32(out, center.z());
        BedrockBinary.writeVarUInt32(out, entries.size());

        for (Entry entry : entries) {
            out.put((byte) entry.offset().x());
            out.put((byte) entry.offset().y());
            out.put((byte) entry.offset().z());
            out.put((byte) entry.result());

            byte[] serialized = entry.serializedSubChunk;
            BedrockBinary.writeVarUInt32(out, serialized.length);
            out.put(serialized);

            // SubChunkHeightmapData: both heightmap kinds are NoData.
            out.put((byte) 0);
            out.put((byte) 0);
            // Cache is disabled, so the optional blob ID is omitted.
        }

        out.flip();
        return out.asReadOnlyBuffer();
    }

    public static BedrockGamePacket encodePacket(int dimension, Position center, List<Entry> entries) {
        ByteBuffer payload = encodePayload(dimension, center, entries);
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        return new BedrockGamePacket(new BedrockPacketHeader(PACKET_ID, 0, 0), bytes);
    }

    private static void writeBoolean(ByteBuffer out, boolean value) {
        out.put((byte) (value ? 1 : 0));
    }

    private static void requireInt8(int value, String name) {
        if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
            throw new IllegalArgumentException(name + " offset must fit int8");
        }
    }
}
