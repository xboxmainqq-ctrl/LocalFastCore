package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Assembles the serialized data string of a full Bedrock LevelChunk column.
 *
 * <p>The column layout is sections from the dimension's minimum section Y
 * through the highest section containing non-air blocks, non-empty fluids or non-default
 * biome data, followed by the matching biome palettes, an empty border-block
 * list, and an empty block-entity NBT list. The field order follows the
 * documented full-column layout used by established Bedrock implementations;
 * it remains subject to byte-for-byte validation against an unmodified
 * protocol-2225 client.</p>
 */
public final class BedrockLevelChunkColumnDataCodec {
    private static final int MAX_COLUMN_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SECTIONS = 128;

    public record EncodedColumn(int subChunkCount, byte[] serializedData) {
        public EncodedColumn {
            if (subChunkCount < 0 || subChunkCount > MAX_SECTIONS) {
                throw new IllegalArgumentException("Invalid serialized subchunk count");
            }
            serializedData = Objects.requireNonNull(serializedData, "serializedData").clone();
            if (serializedData.length > MAX_COLUMN_BYTES) {
                throw new IllegalArgumentException("Serialized chunk column exceeds the byte limit");
            }
        }

        @Override
        public byte[] serializedData() {
            return serializedData.clone();
        }
    }

    private BedrockLevelChunkColumnDataCodec() {
    }

    /**
     * Builds a full column snapshot for a loaded chunk. Missing block sections
     * between the dimension floor and the highest populated section are
     * serialized as all-air sections so the section and biome sequences align.
     */
    public static EncodedColumn encode(
            World world,
            ChunkPosition chunkPosition,
            BedrockRuntimeBlockRegistry registry
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(chunkPosition, "chunkPosition");
        Objects.requireNonNull(registry, "registry");
        if (!world.isChunkLoaded(chunkPosition)) {
            throw new IllegalArgumentException("Cannot serialize an unloaded chunk: " + chunkPosition);
        }

        int minSectionY = Math.floorDiv(world.height().minY(), 16);
        Map<Integer, ChunkSection> blockSections =
                BedrockSubChunkResponseBuilder.groupBlocksBySection(world.blocksInChunk(chunkPosition));
        Map<Integer, ChunkSection> fluidSections =
                BedrockSubChunkResponseBuilder.groupBlocksBySection(world.fluidsInChunk(chunkPosition));
        List<Integer> biomeSectionYs = world.biomeSectionsInChunk(chunkPosition);

        int highestSectionY = minSectionY - 1;
        for (int sectionY : blockSections.keySet()) {
            highestSectionY = Math.max(highestSectionY, sectionY);
        }
        for (int sectionY : fluidSections.keySet()) {
            highestSectionY = Math.max(highestSectionY, sectionY);
        }
        for (int sectionY : biomeSectionYs) {
            highestSectionY = Math.max(highestSectionY, sectionY);
        }

        if (highestSectionY < minSectionY) {
            // Empty terrain: no section/biome entries, then empty border and block-entity lists.
            return new EncodedColumn(0, new byte[] {0, 0});
        }

        int sectionCount = Math.addExact(Math.subtractExact(highestSectionY, minSectionY), 1);
        if (sectionCount > MAX_SECTIONS) {
            throw new IllegalArgumentException("Chunk column exceeds 128 serialized sections");
        }
        if (highestSectionY > Math.floorDiv(world.height().maxYExclusive() - 1, 16)) {
            throw new IllegalStateException("Chunk data contains a section outside world height");
        }

        List<ByteBuffer> encodedTerrain = new ArrayList<>(sectionCount);
        List<int[]> biomeSections = new ArrayList<>(sectionCount);
        long totalBytes = 2; // border-block count and empty block-entity TAG_End
        for (int index = 0; index < sectionCount; index++) {
            int sectionY = minSectionY + index;
            ChunkSection blocks = blockSections.get(sectionY);
            if (blocks == null) {
                blocks = new ChunkSection();
            }
            ChunkSection fluids = fluidSections.get(sectionY);
            if (fluids == null) {
                fluids = new ChunkSection();
            }
            ByteBuffer terrain = BedrockSubChunkTerrainCodec.encodeSection(
                    blocks, fluids, sectionY, registry);
            encodedTerrain.add(terrain);
            totalBytes = Math.addExact(totalBytes, terrain.remaining());
            biomeSections.add(world.biomesInSection(chunkPosition, sectionY));
        }

        ByteBuffer encodedBiomes = BedrockBiomePaletteCodec.encodeSections(biomeSections);
        totalBytes = Math.addExact(totalBytes, encodedBiomes.remaining());
        if (totalBytes > MAX_COLUMN_BYTES) {
            throw new IllegalArgumentException("Serialized chunk column exceeds the byte limit");
        }

        ByteBuffer out = ByteBuffer.allocate((int) totalBytes);
        for (ByteBuffer terrain : encodedTerrain) {
            out.put(terrain);
        }
        out.put(encodedBiomes);
        out.put((byte) 0); // No Education Edition border-block columns.
        out.put((byte) 0); // Empty network block-entity NBT list (TAG_End).
        out.flip();

        byte[] bytes = new byte[out.remaining()];
        out.get(bytes);
        return new EncodedColumn(sectionCount, bytes);
    }
}
