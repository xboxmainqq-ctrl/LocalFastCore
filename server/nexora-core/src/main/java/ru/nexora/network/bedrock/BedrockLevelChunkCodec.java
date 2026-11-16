package ru.nexora.network.bedrock;

import ru.nexora.world.BlockType;
import ru.nexora.world.ChunkSection;
import ru.nexora.world.WorldChunk;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Encoder for the protocol-2225 FULL_CHUNK_DATA (LevelChunk) packet. */
public final class BedrockLevelChunkCodec {
    public static final int LEVEL_CHUNK_PACKET_ID = 0x3a;
    private static final int NEGATIVE_SUB_CHUNKS = 4;
    private static final int WORLD_BIOME_SECTIONS = 24;
    private static final int PLAINS_BIOME_ID = 1;

    private BedrockLevelChunkCodec() {
    }

    public static BedrockPacket createPacket(WorldChunk chunk) {
        if (chunk == null) throw new IllegalArgumentException("chunk must not be null");

        int highestSection = chunk.highestNonEmptySectionCount();
        int positiveSectionCount = Math.max(0, highestSection - NEGATIVE_SUB_CHUNKS);
        int subChunkCount = NEGATIVE_SUB_CHUNKS + positiveSectionCount;

        Writer chunkData = new Writer();
        for (int sectionY = -NEGATIVE_SUB_CHUNKS; sectionY < 0; sectionY++) {
            writeEmptySubChunk(chunkData, sectionY);
        }
        for (int sectionY = 0; sectionY < positiveSectionCount; sectionY++) {
            ChunkSection section = chunk.section(sectionY + NEGATIVE_SUB_CHUNKS);
            writeSubChunk(chunkData, sectionY, section);
        }

        // The Overworld's 3D biome palette spans all 24 vertical sections, even when
        // only a small number of block sub-chunks are sent with this initial column.
        for (int i = 0; i < WORLD_BIOME_SECTIONS; i++) {
            chunkData.byteValue(1); // singleton palette, runtime-ID flag
            chunkData.varInt32(PLAINS_BIOME_ID);
        }
        chunkData.byteValue(0); // Border blocks

        byte[] data = chunkData.toByteArray();
        Writer packet = new Writer();
        packet.varInt32(chunk.chunkX());
        packet.varInt32(chunk.chunkZ());
        packet.varInt32(0); // Overworld dimension
        packet.varUInt32(subChunkCount);
        packet.bool(false); // requestSubChunks optional is absent
        packet.bool(false); // client chunk cache disabled
        packet.varUInt32(0); // no blob hashes
        packet.byteArray(data);
        packet.bool(false); // clientBiomeUpdate

        return new BedrockPacket(LEVEL_CHUNK_PACKET_ID, 0, 0, packet.toByteArray());
    }

    private static void writeEmptySubChunk(Writer out, int sectionY) {
        out.byteValue(9); // SubChunk version 9
        out.byteValue(0); // No block layers
        out.byteValue(sectionY);
    }

    private static void writeSubChunk(Writer out, int sectionY, ChunkSection section) {
        out.byteValue(9); // SubChunk version 9
        out.byteValue(1); // One block-storage layer
        out.byteValue(sectionY);
        if (section == null || section.isEmpty()) {
            out.byteValue(1); // V0 singleton palette, runtime IDs
            out.varInt32(BedrockBlockRuntimeIds.forBlock(BlockType.AIR));
            return;
        }

        byte[] raw = section.copyRawBlocks();
        Map<Integer, Integer> paletteIndexes = new LinkedHashMap<>();
        List<Integer> palette = new ArrayList<>();
        int[] indexes = new int[ChunkSection.VOLUME];
        for (int i = 0; i < raw.length; i++) {
            BlockType type = BlockType.byInternalId(Byte.toUnsignedInt(raw[i]));
            int runtimeId = BedrockBlockRuntimeIds.forBlock(type);
            Integer paletteIndex = paletteIndexes.get(runtimeId);
            if (paletteIndex == null) {
                paletteIndex = palette.size();
                paletteIndexes.put(runtimeId, paletteIndex);
                palette.add(runtimeId);
            }
            indexes[i] = paletteIndex;
        }
        writePalette(out, indexes, palette);
    }

    private static void writePalette(Writer out, int[] indexes, List<Integer> palette) {
        if (palette.size() == 1) {
            out.byteValue(1); // V0, runtime palette
            out.varInt32(palette.getFirst());
            return;
        }

        int bits = 1;
        while ((1 << bits) < palette.size()) bits++;
        int entriesPerWord = 32 / bits;
        int wordCount = (indexes.length + entriesPerWord - 1) / entriesPerWord;
        out.byteValue((bits << 1) | 1); // version bits plus runtime-ID flag

        for (int wordIndex = 0; wordIndex < wordCount; wordIndex++) {
            int word = 0;
            int base = wordIndex * entriesPerWord;
            for (int slot = 0; slot < entriesPerWord && base + slot < indexes.length; slot++) {
                word |= indexes[base + slot] << (slot * bits);
            }
            out.int32(word);
        }

        out.varUInt32(palette.size());
        for (int runtimeId : palette) out.varInt32(runtimeId);
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(8192);

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
            varUInt32(Integer.toUnsignedLong((value << 1) ^ (value >> 31)));
        }

        void varUInt32(long value) {
            if (value < 0 || value > 0xffff_ffffL) {
                throw new IllegalArgumentException("VarUInt32 out of range");
            }
            do {
                int next = (int) (value & 0x7f);
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
