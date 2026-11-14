package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Protocol-2225 network serializer for one version-9 Bedrock subchunk. */
public final class BedrockSubChunkTerrainCodec {
    public static final int FORMAT_VERSION = 9;
    public static final int BLOCK_STORAGE_LAYERS = 1;
    private static final int MAX_SERIALIZED_BYTES = 128 * 1024;

    private BedrockSubChunkTerrainCodec() {
    }

    /**
     * Encodes a single block-storage layer. This initial NEXORA terrain profile
     * has no fluid layer, block actors, or biome palette inside this blob.
     */
    public static ByteBuffer encode(
            ChunkSection section,
            int absoluteSubChunkY,
            BedrockRuntimeBlockRegistry registry
    ) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(registry, "registry");
        if (absoluteSubChunkY < Byte.MIN_VALUE || absoluteSubChunkY > Byte.MAX_VALUE) {
            throw new IllegalArgumentException("Subchunk Y index must fit signed int8");
        }

        ByteBuffer storage = BedrockPalettedStorageCodec.encode(section, registry);
        int required = 3 + storage.remaining();
        if (required > MAX_SERIALIZED_BYTES) {
            throw new IllegalArgumentException("Serialized subchunk exceeds the configured limit");
        }

        ByteBuffer out = ByteBuffer.allocate(required);
        out.put((byte) FORMAT_VERSION);
        out.put((byte) BLOCK_STORAGE_LAYERS);
        out.put((byte) absoluteSubChunkY);
        out.put(storage);
        out.flip();
        return out.asReadOnlyBuffer();
    }
}
