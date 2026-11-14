package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Protocol-2225 network serializer for one version-9 Bedrock subchunk. */
public final class BedrockSubChunkTerrainCodec {
    public static final int FORMAT_VERSION = 9;
    public static final int BLOCK_STORAGE_LAYERS = 2;
    private static final int MAX_SERIALIZED_BYTES = 128 * 1024;

    private BedrockSubChunkTerrainCodec() {
    }

    /**
     * Encodes one subchunk response blob, including its empty block-actor NBT
     * terminator. The current terrain profile writes the block layer and an explicit empty
     * liquid layer; fluid-bearing states are rejected until world fluid storage exists.
     */
    public static ByteBuffer encode(
            ChunkSection section,
            int absoluteSubChunkY,
            BedrockRuntimeBlockRegistry registry
    ) {
        ByteBuffer terrain = encodeSection(section, absoluteSubChunkY, registry);
        if (terrain.remaining() + 1 > MAX_SERIALIZED_BYTES) {
            throw new IllegalArgumentException("Serialized subchunk exceeds the configured limit");
        }
        ByteBuffer out = ByteBuffer.allocate(terrain.remaining() + 1);
        out.put(terrain);
        out.put((byte) 0); // Empty block-actor NBT sequence (TAG_End).
        out.flip();
        return out.asReadOnlyBuffer();
    }

    /**
     * Encodes only the version-9 terrain section. Full LevelChunk column
     * payloads place the block-entity NBT list once after all sections, while
     * SubChunkPacket response blobs carry their own block-actor terminator.
     */
    public static ByteBuffer encodeSection(
            ChunkSection section,
            int absoluteSubChunkY,
            BedrockRuntimeBlockRegistry registry
    ) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(registry, "registry");
        if (absoluteSubChunkY < Byte.MIN_VALUE || absoluteSubChunkY > Byte.MAX_VALUE) {
            throw new IllegalArgumentException("Subchunk Y index must fit signed int8");
        }

        ByteBuffer blockStorage = BedrockPalettedStorageCodec.encode(section, registry);
        ByteBuffer liquidStorage = BedrockPalettedStorageCodec.encodeEmptyFluidLayer(registry);
        int required = 3 + blockStorage.remaining() + liquidStorage.remaining();
        if (required > MAX_SERIALIZED_BYTES) {
            throw new IllegalArgumentException("Serialized subchunk exceeds the configured limit");
        }

        ByteBuffer out = ByteBuffer.allocate(required);
        out.put((byte) FORMAT_VERSION);
        out.put((byte) BLOCK_STORAGE_LAYERS);
        out.put((byte) absoluteSubChunkY);
        out.put(blockStorage);
        out.put(liquidStorage);
        out.flip();
        return out.asReadOnlyBuffer();
    }
}
