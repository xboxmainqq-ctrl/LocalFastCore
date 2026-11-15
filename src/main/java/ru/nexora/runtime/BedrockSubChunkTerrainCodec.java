package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Protocol-2225 network serializer for one version-9 Bedrock subchunk. */
public final class BedrockSubChunkTerrainCodec {
    public static final int FORMAT_VERSION = 9;
    public static final int STORAGE_LAYER_COUNT = 2;
    private static final int MAX_SERIALIZED_BYTES = 128 * 1024;

    private BedrockSubChunkTerrainCodec() {
    }

    /**
     * Encodes one subchunk response blob, including its empty block-actor NBT
     * terminator. Fluid cells are read from the separately stored liquid section.
     */
    public static ByteBuffer encode(
            ChunkSection section,
            int absoluteSubChunkY,
            BedrockRuntimeBlockRegistry registry
    ) {
        return encode(section, new ChunkSection(), absoluteSubChunkY, registry);
    }

    /** Encodes solid blocks and a separately stored liquid section. */
    public static ByteBuffer encode(
            ChunkSection section,
            ChunkSection fluidSection,
            int absoluteSubChunkY,
            BedrockRuntimeBlockRegistry registry
    ) {
        ByteBuffer terrain = encodeSection(section, fluidSection, absoluteSubChunkY, registry);
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
        return encodeSection(section, new ChunkSection(), absoluteSubChunkY, registry);
    }

    public static ByteBuffer encodeSection(
            ChunkSection section,
            ChunkSection fluidSection,
            int absoluteSubChunkY,
            BedrockRuntimeBlockRegistry registry
    ) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(fluidSection, "fluidSection");
        Objects.requireNonNull(registry, "registry");
        if (absoluteSubChunkY < Byte.MIN_VALUE || absoluteSubChunkY > Byte.MAX_VALUE) {
            throw new IllegalArgumentException("Subchunk Y index must fit signed int8");
        }

        ByteBuffer blockStorage = BedrockPalettedStorageCodec.encode(section, registry);
        ByteBuffer liquidStorage = BedrockPalettedStorageCodec.encodeFluidLayer(fluidSection, registry);
        int required = 3 + blockStorage.remaining() + liquidStorage.remaining();
        if (required > MAX_SERIALIZED_BYTES) {
            throw new IllegalArgumentException("Serialized subchunk exceeds the configured limit");
        }

        ByteBuffer out = ByteBuffer.allocate(required);
        out.put((byte) FORMAT_VERSION);
        out.put((byte) STORAGE_LAYER_COUNT);
        out.put((byte) absoluteSubChunkY);
        out.put(blockStorage);
        out.put(liquidStorage);
        out.flip();
        return out.asReadOnlyBuffer();
    }
}
