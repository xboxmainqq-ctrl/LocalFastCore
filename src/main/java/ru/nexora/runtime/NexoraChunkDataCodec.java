package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded wire messages for requesting and transferring sparse NEXORA chunk volumes. */
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
        int size = 7 + 4 + 4 + 4;
        List<byte[]> identifiers = new ArrayList<>(chunk.blocks().size());
        for (NexoraChunkData.Block block : chunk.blocks()) {
            byte[] id = block.blockIdentifier().getBytes(StandardCharsets.UTF_8);
            if (id.length < 1 || id.length > 128) {
                throw new IllegalArgumentException("Block identifier is too long");
            }
            identifiers.add(id);
            size = Math.addExact(size, 1 + 4 + 1 + 1 + id.length);
        }
        if (size > MAX_MESSAGE_BYTES) throw new IllegalArgumentException("NEXORA chunk message is too large");
        ByteBuffer out = header(CHUNK_DATA, size - 7);
        out.putInt(chunk.chunkX()).putInt(chunk.chunkZ()).putInt(chunk.blocks().size());
        for (int i = 0; i < chunk.blocks().size(); i++) {
            NexoraChunkData.Block block = chunk.blocks().get(i);
            byte[] id = identifiers.get(i);
            out.put((byte) block.localX()).putInt(block.y()).put((byte) block.localZ())
                    .put((byte) id.length).put(id);
        }
        return out.array();
    }

    public static NexoraChunkData decodeChunkData(ByteBuffer source) {
        ByteBuffer in = checked(source, CHUNK_DATA);
        if (in.remaining() < 12) throw new IllegalArgumentException("Truncated NEXORA chunk data");
        int x = in.getInt(), z = in.getInt(), count = in.getInt();
        if (count < 0 || count > NexoraChunkData.MAX_BLOCKS) {
            throw new IllegalArgumentException("NEXORA chunk has an invalid block count");
        }
        List<NexoraChunkData.Block> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (in.remaining() < 7) throw new IllegalArgumentException("Truncated NEXORA chunk block");
            int localX = Byte.toUnsignedInt(in.get());
            int y = in.getInt();
            int localZ = Byte.toUnsignedInt(in.get());
            int length = Byte.toUnsignedInt(in.get());
            if (length < 1 || length > 128 || in.remaining() < length) {
                throw new IllegalArgumentException("Invalid NEXORA block identifier length");
            }
            byte[] id = new byte[length];
            in.get(id);
            blocks.add(new NexoraChunkData.Block(localX, y, localZ, decodeUtf8(id)));
        }
        if (in.hasRemaining()) throw new IllegalArgumentException("Trailing bytes in NEXORA chunk data");
        return new NexoraChunkData(x, z, blocks);
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
