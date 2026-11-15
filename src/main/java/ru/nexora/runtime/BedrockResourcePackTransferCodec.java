package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bedrock 1.26.60 resource-pack wire codecs (protocol 2225).
 *
 * <p>Archives are transferred as opaque ZIP bytes. The client validates and
 * installs the received Bedrock pack; the server never extracts client assets.</p>
 */
public final class BedrockResourcePackTransferCodec {
    public static final int DATA_INFO_PACKET_ID = 82;
    public static final int CHUNK_DATA_PACKET_ID = 83;
    public static final int CHUNK_REQUEST_PACKET_ID = 84;
    public static final int RESOURCE_PACK_TYPE_RESOURCES = 6;
    public static final int DEFAULT_CHUNK_BYTES = 1024 * 1024;
    public static final int MAX_PACK_BYTES = 64 * 1024 * 1024;
    public static final int MAX_CHUNK_BYTES = 1024 * 1024;

    private BedrockResourcePackTransferCodec() {
    }

    /** Immutable resource-pack archive used by the transfer layer. */
    public record Pack(UUID uuid, String version, byte[] archive) {
        public Pack {
            Objects.requireNonNull(uuid, "uuid");
            if (version == null || !version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?")) {
                throw new IllegalArgumentException("Invalid resource-pack semantic version");
            }
            archive = Objects.requireNonNull(archive, "archive").clone();
            if (archive.length < 1 || archive.length > MAX_PACK_BYTES) {
                throw new IllegalArgumentException("Resource-pack archive size is outside the allowed range");
            }
        }

        @Override
        public byte[] archive() {
            return archive.clone();
        }

        public int archiveSize() {
            return archive.length;
        }

        public byte[] archiveChunk(int offset, int length) {
            if (offset < 0 || length < 1 || offset > archive.length - length) {
                throw new IllegalArgumentException("Resource-pack archive range is invalid");
            }
            return java.util.Arrays.copyOfRange(archive, offset, offset + length);
        }

        public String resourceName() {
            return uuid + "_" + version;
        }
    }

    public record ChunkRequest(String resourceName, int chunkIndex) {
        public ChunkRequest {
            if (resourceName == null || resourceName.isBlank()
                    || resourceName.getBytes(StandardCharsets.UTF_8).length > 128
                    || chunkIndex < 0) {
                throw new IllegalArgumentException("Invalid resource-pack chunk request");
            }
        }
    }

    /** Encodes ResourcePacksInfo with one or more resource-only Bedrock packs. */
    public static ByteBuffer encodeResourcePacksInfoPayload(List<Pack> packs, boolean required) {
        Objects.requireNonNull(packs, "packs");
        if (packs.size() > 64) throw new IllegalArgumentException("Too many resource packs");
        int capacity = 64;
        for (Pack pack : packs) capacity = Math.addExact(capacity, 128
                + pack.version().getBytes(StandardCharsets.UTF_8).length);
        ByteBuffer out = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) (required ? 1 : 0));
        out.put((byte) 0); // has addon packs
        out.put((byte) 0); // has scripts
        out.put((byte) 0); // force disable vibrant visuals
        out.putLong(0L).putLong(0L); // world-template UUID
        BedrockBinary.writeString(out, "1.0.0", 16); // world-template version
        BedrockBinary.writeVarUInt32(out, packs.size());
        for (Pack pack : packs) {
            Objects.requireNonNull(pack, "pack");
            writeUuid(out, pack.uuid());
            BedrockBinary.writeString(out, pack.version(), 64);
            BedrockBinary.writeLittleEndianInt64(out, pack.archive.length);
            BedrockBinary.writeString(out, "", 256); // content key
            BedrockBinary.writeString(out, "", 256); // subpack name
            BedrockBinary.writeString(out, "", 256); // content identity
            out.put((byte) 0); // has scripts
            out.put((byte) 0); // is addon pack
            out.put((byte) 0); // ray tracing capable
            BedrockBinary.writeString(out, "", 2048); // CDN URL
        }
        out.flip();
        return out.asReadOnlyBuffer();
    }

    /** Encodes the pack stack in the same dependency/load order as ResourcePacksInfo. */
    public static ByteBuffer encodeResourcePackStackPayload(
            List<Pack> packs, String baseGameVersion, boolean required) {
        Objects.requireNonNull(packs, "packs");
        if (packs.size() > 64) throw new IllegalArgumentException("Too many resource packs");
        if (baseGameVersion == null || baseGameVersion.isBlank()
                || baseGameVersion.getBytes(StandardCharsets.UTF_8).length > 64) {
            throw new IllegalArgumentException("Invalid base game version");
        }
        ByteBuffer out = ByteBuffer.allocate(64 + packs.size() * 256
                + baseGameVersion.getBytes(StandardCharsets.UTF_8).length);
        out.put((byte) (required ? 1 : 0));
        BedrockBinary.writeVarUInt32(out, packs.size());
        for (Pack pack : packs) {
            Objects.requireNonNull(pack, "pack");
            BedrockBinary.writeString(out, pack.uuid().toString(), 64);
            BedrockBinary.writeString(out, pack.version(), 64);
            BedrockBinary.writeString(out, "", 256); // subpack name
        }
        BedrockBinary.writeString(out, baseGameVersion, 64);
        out.order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0); // experiments: fixed-width uint32 count
        out.put((byte) 0); // experiments ever toggled
        out.put((byte) 0); // include editor packs
        out.flip();
        return out.asReadOnlyBuffer();
    }

    /** Encodes ResourcePackDataInfo for a resource archive. */
    public static ByteBuffer encodeDataInfoPayload(Pack pack, int chunkBytes) {
        Objects.requireNonNull(pack, "pack");
        validateChunkBytes(chunkBytes);
        byte[] archive = pack.archive;
        int chunkCount = (archive.length + chunkBytes - 1) / chunkBytes;
        byte[] digest = sha256(archive);
        String hash = HexFormat.of().formatHex(digest);
        ByteBuffer out = ByteBuffer.allocate(256);
        BedrockBinary.writeString(out, pack.resourceName(), 128);
        out.order(ByteOrder.LITTLE_ENDIAN);
        BedrockBinary.writeLittleEndianInt32(out, chunkBytes);
        BedrockBinary.writeLittleEndianInt32(out, chunkCount);
        BedrockBinary.writeLittleEndianInt64(out, archive.length);
        BedrockBinary.writeString(out, hash, 64);
        out.put((byte) 0); // not premium
        out.put((byte) RESOURCE_PACK_TYPE_RESOURCES);
        out.flip();
        return out.asReadOnlyBuffer();
    }

    /** Encodes one requested ResourcePackChunkData packet payload. */
    public static ByteBuffer encodeChunkDataPayload(Pack pack, int chunkIndex, int chunkBytes) {
        Objects.requireNonNull(pack, "pack");
        validateChunkBytes(chunkBytes);
        byte[] archive = pack.archive;
        int chunkCount = (archive.length + chunkBytes - 1) / chunkBytes;
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("Requested resource-pack chunk is outside the archive");
        }
        int offset = Math.multiplyExact(chunkIndex, chunkBytes);
        int length = Math.min(chunkBytes, archive.length - offset);
        ByteBuffer out = ByteBuffer.allocate(160 + length);
        BedrockBinary.writeString(out, pack.resourceName(), 128);
        out.order(ByteOrder.LITTLE_ENDIAN);
        BedrockBinary.writeLittleEndianInt32(out, chunkIndex);
        BedrockBinary.writeLittleEndianInt64(out, offset);
        BedrockBinary.writeVarUInt32(out, length);
        out.put(archive, offset, length);
        out.flip();
        return out.asReadOnlyBuffer();
    }

    /** Decodes a client ResourcePackChunkRequest packet payload. */
    public static ChunkRequest decodeChunkRequestPayload(ByteBuffer payload) {
        Objects.requireNonNull(payload, "payload");
        String resourceName = BedrockBinary.readString(payload, 128);
        payload.order(ByteOrder.LITTLE_ENDIAN);
        int chunkIndex = BedrockBinary.readLittleEndianInt32(payload);
        if (payload.hasRemaining()) {
            throw new IllegalArgumentException("Trailing ResourcePackChunkRequest bytes");
        }
        return new ChunkRequest(resourceName, chunkIndex);
    }

    private static void writeUuid(ByteBuffer out, UUID uuid) {
        BedrockBinary.writeLittleEndianInt64(out, uuid.getMostSignificantBits());
        BedrockBinary.writeLittleEndianInt64(out, uuid.getLeastSignificantBits());
    }

    private static void validateChunkBytes(int chunkBytes) {
        if (chunkBytes < 1 || chunkBytes > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("Resource-pack chunk size is outside the allowed range");
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
