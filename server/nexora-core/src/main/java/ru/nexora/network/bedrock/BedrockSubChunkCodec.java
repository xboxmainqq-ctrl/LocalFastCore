package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Protocol-2225 SubChunkRequestPacket (175) and SubChunkPacket (174) codec. */
public final class BedrockSubChunkCodec {
    public static final int SUB_CHUNK_PACKET_ID = 174;
    public static final int SUB_CHUNK_REQUEST_PACKET_ID = 175;
    public static final int RESULT_SUCCESS = 1;
    public static final int RESULT_LEVEL_CHUNK_DOES_NOT_EXIST = 2;
    public static final int RESULT_WRONG_DIMENSION = 3;
    public static final int RESULT_PLAYER_DOES_NOT_EXIST = 4;
    public static final int RESULT_INDEX_OUT_OF_BOUNDS = 5;
    public static final int RESULT_SUCCESS_ALL_AIR = 6;
    // Match Nukkit-MOT SubChunkRequestPacket's protocol cap (8192 offsets).
    public static final int MAX_REQUESTED_SUB_CHUNKS = 8192;

    private BedrockSubChunkCodec() {
    }

    public static Request decodeRequest(BedrockPacket packet) {
        if (packet == null || packet.packetId() != SUB_CHUNK_REQUEST_PACKET_ID) {
            throw new IllegalArgumentException("Expected SubChunkRequestPacket (175)");
        }
        Reader in = new Reader(packet.payload());
        int dimension = in.varInt32();
        long countValue = in.varUInt32();
        if (countValue > MAX_REQUESTED_SUB_CHUNKS) {
            throw new IllegalArgumentException("SubChunk request exceeds " + MAX_REQUESTED_SUB_CHUNKS + " entries");
        }
        int count = (int) countValue;
        List<Offset> offsets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            offsets.add(new Offset(in.signedByte(), in.signedByte(), in.signedByte()));
        }
        int centerX = in.int32();
        int centerY = in.int32();
        int centerZ = in.int32();
        if (!in.exhausted()) throw new IllegalArgumentException("Trailing bytes in SubChunkRequestPacket");
        return new Request(dimension, centerX, centerY, centerZ, offsets);
    }

    public static BedrockPacket createResponsePacket(Request request, List<Response> responses) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responses, "responses");
        if (responses.size() > MAX_REQUESTED_SUB_CHUNKS) {
            throw new IllegalArgumentException("SubChunk response exceeds " + MAX_REQUESTED_SUB_CHUNKS + " entries");
        }
        Writer out = new Writer();
        out.bool(false); // Blob cache disabled
        out.varInt32(request.dimension());
        out.int32(request.centerX());
        out.int32(request.centerY());
        out.int32(request.centerZ());
        out.varUInt32(responses.size());
        for (Response response : responses) {
            Objects.requireNonNull(response, "response");
            out.byteValue(response.offset().x());
            out.byteValue(response.offset().y());
            out.byteValue(response.offset().z());
            out.byteValue(response.result());
            if (response.result() == RESULT_SUCCESS) {
                byte[] serialized = Objects.requireNonNull(response.serializedSubChunk(),
                        "Successful subchunk response requires serialized data");
                out.bool(true); // Serialized subchunk is present
                out.byteArray(serialized);
            } else {
                if (response.serializedSubChunk() != null) {
                    throw new IllegalArgumentException("Only successful responses may include serialized subchunk data");
                }
                out.bool(false); // Serialized subchunk is absent
            }
            out.byteValue(0); // Heightmap: NoData
            out.bool(false); // Heightmap data is absent
            out.byteValue(0); // Render heightmap: NoData
            out.bool(false); // Render heightmap data is absent
            out.bool(false); // Blob ID is absent
        }
        return new BedrockPacket(SUB_CHUNK_PACKET_ID, 0, 0, out.toByteArray());
    }

    public record Request(int dimension, int centerX, int centerY, int centerZ, List<Offset> offsets) {
        public Request {
            offsets = List.copyOf(Objects.requireNonNull(offsets, "offsets"));
            if (offsets.size() > MAX_REQUESTED_SUB_CHUNKS) {
                throw new IllegalArgumentException("Too many requested subchunks");
            }
        }
    }

    public record Offset(int x, int y, int z) {
        public Offset {
            if (x < -128 || x > 127 || y < -128 || y > 127 || z < -128 || z > 127) {
                throw new IllegalArgumentException("SubChunk offsets must fit signed bytes");
            }
        }
    }

    public record Response(Offset offset, int result, byte[] serializedSubChunk) {
        public Response {
            Objects.requireNonNull(offset, "offset");
            if (result < RESULT_SUCCESS || result > RESULT_SUCCESS_ALL_AIR) {
                throw new IllegalArgumentException("Unknown SubChunk request result: " + result);
            }
            if (serializedSubChunk != null) serializedSubChunk = serializedSubChunk.clone();
        }

        @Override
        public byte[] serializedSubChunk() {
            return serializedSubChunk == null ? null : serializedSubChunk.clone();
        }
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;

        private Reader(byte[] data) {
            this.data = data;
        }

        int signedByte() {
            require(1);
            return data[offset++];
        }

        int int32() {
            require(4);
            int value = Byte.toUnsignedInt(data[offset])
                    | Byte.toUnsignedInt(data[offset + 1]) << 8
                    | Byte.toUnsignedInt(data[offset + 2]) << 16
                    | Byte.toUnsignedInt(data[offset + 3]) << 24;
            offset += 4;
            return value;
        }

        int varInt32() {
            int value = (int) varUInt32();
            return (value >>> 1) ^ -(value & 1);
        }

        long varUInt32() {
            long value = 0;
            for (int index = 0; index < 5; index++) {
                require(1);
                int next = Byte.toUnsignedInt(data[offset++]);
                if (index == 4 && (next & 0xf0) != 0) {
                    throw new IllegalArgumentException("SubChunk VarUInt32 exceeds 32 bits");
                }
                value |= (long) (next & 0x7f) << (index * 7);
                if ((next & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException("SubChunk VarUInt32 exceeds five bytes");
        }

        boolean exhausted() {
            return offset == data.length;
        }

        private void require(int length) {
            if (length < 0 || offset > data.length - length) {
                throw new IllegalArgumentException("Truncated SubChunk packet");
            }
        }
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        void byteValue(int value) {
            output.write(value & 0xff);
        }

        void bool(boolean value) {
            byteValue(value ? 1 : 0);
        }

        void int32(int value) {
            byteValue(value);
            byteValue(value >>> 8);
            byteValue(value >>> 16);
            byteValue(value >>> 24);
        }

        void varInt32(int value) {
            varUInt32((value << 1) ^ (value >> 31));
        }

        void varUInt32(int value) {
            if (value < 0) throw new IllegalArgumentException("VarUInt32 must be non-negative");
            do {
                int next = value & 0x7f;
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }

        void byteArray(byte[] value) {
            varUInt32(value.length);
            output.writeBytes(value);
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
