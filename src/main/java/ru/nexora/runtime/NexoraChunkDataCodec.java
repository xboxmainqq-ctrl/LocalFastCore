package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded wire messages for requesting and transferring the first NEXORA chunk preview. */
public final class NexoraChunkDataCodec {
    public static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
    private static final byte[] MAGIC = {'N','X','R','A'};
    private static final int REQUEST_CHUNK = 5;
    private static final int CHUNK_DATA = 6;

    private NexoraChunkDataCodec() { }

    public static byte[] encodeChunkRequest(int chunkX, int chunkZ) {
        ByteBuffer out = header(REQUEST_CHUNK, 8);
        out.putInt(chunkX).putInt(chunkZ);
        return out.array();
    }

    public static ChunkRequest decodeChunkRequest(ByteBuffer source) {
        ByteBuffer in = checked(source, REQUEST_CHUNK);
        if (in.remaining() != 8) throw new IllegalArgumentException("Malformed NEXORA chunk request");
        return new ChunkRequest(in.getInt(), in.getInt());
    }

    public static byte[] encodeChunkData(NexoraChunkData chunk) {
        Objects.requireNonNull(chunk, "chunk");
        int size = 7 + 4 + 4 + 2;
        List<byte[]> identifiers = new ArrayList<>(chunk.columns().size());
        for (NexoraChunkData.Column column : chunk.columns()) {
            byte[] id = column.blockIdentifier().getBytes(StandardCharsets.UTF_8);
            if (id.length < 1 || id.length > 128) throw new IllegalArgumentException("Block identifier is too long");
            identifiers.add(id);
            size = Math.addExact(size, 1 + 4 + 1 + id.length);
        }
        if (size > MAX_MESSAGE_BYTES) throw new IllegalArgumentException("NEXORA chunk message is too large");
        ByteBuffer out = header(CHUNK_DATA, size - 7);
        out.putInt(chunk.chunkX()).putInt(chunk.chunkZ()).putShort((short) chunk.columns().size());
        for (int i = 0; i < chunk.columns().size(); i++) {
            NexoraChunkData.Column column = chunk.columns().get(i);
            byte[] id = identifiers.get(i);
            out.put((byte) column.index()).putInt(column.y()).put((byte) id.length).put(id);
        }
        return out.array();
    }

    public static NexoraChunkData decodeChunkData(ByteBuffer source) {
        ByteBuffer in = checked(source, CHUNK_DATA);
        if (in.remaining() < 10) throw new IllegalArgumentException("Truncated NEXORA chunk data");
        int x = in.getInt(), z = in.getInt();
        int count = Short.toUnsignedInt(in.getShort());
        if (count > 256) throw new IllegalArgumentException("NEXORA chunk has too many columns");
        List<NexoraChunkData.Column> columns = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (in.remaining() < 6) throw new IllegalArgumentException("Truncated NEXORA chunk column");
            int index = Byte.toUnsignedInt(in.get());
            int y = in.getInt();
            int length = Byte.toUnsignedInt(in.get());
            if (length < 1 || length > 128 || in.remaining() < length) {
                throw new IllegalArgumentException("Invalid NEXORA block identifier length");
            }
            byte[] id = new byte[length];
            in.get(id);
            columns.add(new NexoraChunkData.Column(index, y, decodeUtf8(id)));
        }
        if (in.hasRemaining()) throw new IllegalArgumentException("Trailing bytes in NEXORA chunk data");
        return new NexoraChunkData(x, z, columns);
    }

    private static ByteBuffer header(int type, int payloadLength) {
        int total = Math.addExact(7, payloadLength);
        if (total > MAX_MESSAGE_BYTES) throw new IllegalArgumentException("NEXORA message exceeds size limit");
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        out.put(MAGIC).put((byte) NexoraPrivateProtocolCodec.MAJOR_VERSION)
                .put((byte) NexoraPrivateProtocolCodec.MINOR_VERSION).put((byte) type);
        return out;
    }

    private static ByteBuffer checked(ByteBuffer source, int type) {
        Objects.requireNonNull(source, "source");
        ByteBuffer in = source.slice().order(ByteOrder.BIG_ENDIAN);
        if (in.remaining() < 7 || in.remaining() > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Invalid NEXORA message size");
        }
        for (byte expected : MAGIC) {
            if (in.get() != expected) throw new IllegalArgumentException("Invalid NEXORA magic");
        }
        int major = Byte.toUnsignedInt(in.get()), minor = Byte.toUnsignedInt(in.get());
        int actualType = Byte.toUnsignedInt(in.get());
        if (major != NexoraPrivateProtocolCodec.MAJOR_VERSION
                || minor > NexoraPrivateProtocolCodec.MINOR_VERSION || actualType != type) {
            throw new IllegalArgumentException("Unexpected NEXORA message type or version");
        }
        return in;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid UTF-8 in NEXORA chunk data", invalid);
        }
    }

    public record ChunkRequest(int chunkX, int chunkZ) { }
}
