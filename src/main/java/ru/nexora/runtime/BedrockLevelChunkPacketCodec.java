package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-2225 LevelChunk packet envelope codec.
 *
 * <p>The serialized chunk body is supplied as bytes because its internal
 * subchunk/palette format is a separate codec. This class never fabricates
 * terrain bytes or interprets binary chunk data as UTF-8 text.</p>
 */
public final class BedrockLevelChunkPacketCodec {
    public static final int PACKET_ID = 58;
    public static final int SUBCHUNK_REQUEST_SENTINEL = -1; // uint32 0xffffffff
    private static final int MAX_CHUNK_DATA_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CACHE_ENTRIES = 4096;

    public record ChunkPosition(int x, int z) {
    }

    public record CacheMetadata(long blobId) {
    }

    public record Settings(
            ChunkPosition position,
            int dimension,
            int subChunksCount,
            Integer clientRequestSubChunkLimit,
            boolean cacheEnabled,
            List<CacheMetadata> cacheMetadata,
            byte[] serializedChunkData,
            boolean clientBiomeUpdate
    ) {
        public Settings {
            Objects.requireNonNull(position, "position");
            cacheMetadata = List.copyOf(Objects.requireNonNull(cacheMetadata, "cacheMetadata"));
            serializedChunkData = Objects.requireNonNull(serializedChunkData, "serializedChunkData").clone();
            if (dimension < -1 || dimension > 2) {
                throw new IllegalArgumentException("Unsupported Bedrock dimension");
            }
            if (subChunksCount < 0 && subChunksCount != SUBCHUNK_REQUEST_SENTINEL) {
                throw new IllegalArgumentException("Invalid subchunk count");
            }
            if (subChunksCount != SUBCHUNK_REQUEST_SENTINEL && subChunksCount > 4096) {
                throw new IllegalArgumentException("Subchunk count exceeds the configured limit");
            }
            if (clientRequestSubChunkLimit != null
                    && (subChunksCount != SUBCHUNK_REQUEST_SENTINEL
                    || clientRequestSubChunkLimit < -1 || clientRequestSubChunkLimit > 4096)) {
                throw new IllegalArgumentException("Client subchunk limit is only valid in request mode");
            }
            if (cacheMetadata.size() > MAX_CACHE_ENTRIES) {
                throw new IllegalArgumentException("Too many subchunk cache metadata entries");
            }
            cacheMetadata.forEach(value -> Objects.requireNonNull(value, "cache metadata"));
            if (!cacheEnabled && !cacheMetadata.isEmpty()) {
                throw new IllegalArgumentException("Cache metadata requires cache to be enabled");
            }
            if (serializedChunkData.length > MAX_CHUNK_DATA_BYTES) {
                throw new IllegalArgumentException("Serialized chunk data exceeds the byte limit");
            }
            if (subChunksCount == SUBCHUNK_REQUEST_SENTINEL && serializedChunkData.length != 0) {
                throw new IllegalArgumentException("Skeleton chunks must not carry block-entity or terrain bytes");
            }
        }

        @Override
        public byte[] serializedChunkData() {
            return serializedChunkData.clone();
        }
    }

    private BedrockLevelChunkPacketCodec() {
    }

    /** Encodes all eight LevelChunk fields in protocol-2225 schema order. */
    public static ByteBuffer encodePayload(Settings settings) {
        Objects.requireNonNull(settings, "settings");
        ByteBuffer out = ByteBuffer.allocate(MAX_CHUNK_DATA_BYTES + 64 * 1024);

        BedrockBinary.writeVarInt32(out, settings.position().x());
        BedrockBinary.writeVarInt32(out, settings.position().z());
        BedrockBinary.writeVarInt32(out, settings.dimension());
        BedrockBinary.writeVarUInt32(out, settings.subChunksCount());

        // Client Request SubChunk Limit is optional and only used by request-mode chunks.
        writeBoolean(out, settings.clientRequestSubChunkLimit() != null);
        if (settings.clientRequestSubChunkLimit() != null) {
            BedrockBinary.writeVarInt32(out, settings.clientRequestSubChunkLimit());
        }

        writeBoolean(out, settings.cacheEnabled());
        BedrockBinary.writeVarUInt32(out, settings.cacheMetadata().size());
        for (CacheMetadata metadata : settings.cacheMetadata()) {
            BedrockBinary.writeLittleEndianInt64(out, metadata.blobId());
        }

        byte[] chunkData = settings.serializedChunkData();
        BedrockBinary.writeVarUInt32(out, chunkData.length);
        out.put(chunkData);
        writeBoolean(out, settings.clientBiomeUpdate());

        out.flip();
        return out.asReadOnlyBuffer();
    }

    public static BedrockGamePacket encodePacket(Settings settings) {
        ByteBuffer payload = encodePayload(settings);
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        return new BedrockGamePacket(new BedrockPacketHeader(PACKET_ID, 0, 0), bytes);
    }

    /** Creates a skeleton LevelChunk that opts into the client subchunk request system. */
    public static BedrockGamePacket encodeSkeletonPacket(
            ChunkPosition position, int dimension, int clientRequestSubChunkLimit) {
        return encodePacket(new Settings(
                position,
                dimension,
                SUBCHUNK_REQUEST_SENTINEL,
                clientRequestSubChunkLimit,
                false,
                List.of(),
                new byte[0],
                false
        ));
    }

    private static void writeBoolean(ByteBuffer out, boolean value) {
        out.put((byte) (value ? 1 : 0));
    }
}
