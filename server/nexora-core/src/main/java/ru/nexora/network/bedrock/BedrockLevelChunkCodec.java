package ru.nexora.network.bedrock;

import ru.nexora.world.BlockType;
import ru.nexora.world.ChunkSection;
import ru.nexora.world.WorldChunk;

import java.io.ByteArrayOutputStream;

/** Encoder for the protocol-2225 FULL_CHUNK_DATA (LevelChunk) packet. */
public final class BedrockLevelChunkCodec {
    public static final int LEVEL_CHUNK_PACKET_ID = 0x3a;
    private static final int NEGATIVE_SUB_CHUNKS = 4;
    private static final int WORLD_BIOME_SECTIONS = 24;
    private static final int PLAINS_BIOME_ID = 1;
    private static final int BLOCK_TYPE_COUNT = BlockType.values().length;

    private BedrockLevelChunkCodec() {
    }

    public static BedrockPacket createPacket(WorldChunk chunk) {
        if (chunk == null) throw new IllegalArgumentException("chunk must not be null");

        byte[][] sections = chunk.copySectionsRaw();
        int highestSection = 0;
        for (int index = sections.length - 1; index >= 0; index--) {
            if (sections[index] != null) {
                highestSection = index + 1;
                break;
            }
        }
        // Sections are stored bottom-up from world Y=-64 (subchunk Y=-4).
        // Serialize every section through the highest non-empty one, including negative Y.
        int subChunkCount = highestSection;

        Writer chunkData = new Writer();
        for (int sectionIndex = 0; sectionIndex < subChunkCount; sectionIndex++) {
            int sectionY = sectionIndex - NEGATIVE_SUB_CHUNKS;
            writeSubChunk(chunkData, sectionY, sections[sectionIndex]);
        }

        // Vanilla Bedrock reads biome storage for the full Overworld height (24 sections),
        // independently of the number of block subchunks serialized above.
        for (int i = 0; i < WORLD_BIOME_SECTIONS; i++) {
            chunkData.byteValue(1); // singleton palette, runtime-ID flag
            chunkData.varInt32(PLAINS_BIOME_ID);
        }
        chunkData.byteValue(0); // Border blocks
        chunkData.byteValue(0); // Empty network-NBT block-entity list (TAG_End)

        byte[] data = chunkData.toByteArray();
        Writer packet = new Writer();
        packet.varInt32(chunk.chunkX());
        packet.varInt32(chunk.chunkZ());
        packet.varInt32(0); // Overworld dimension
        packet.varUInt32(subChunkCount);
        // Request-mode limit is absent in ordinary full-column mode.
        packet.bool(false); // client chunk cache disabled
        // Cache metadata is an array field and MUST still be present when caching is disabled.
        // Its empty array is encoded as a zero-length VarUInt32 before Serialized Chunk Data.
        packet.varUInt32(0);
        packet.byteArray(data);
        packet.bool(false); // clientBiomeUpdate

        return new BedrockPacket(LEVEL_CHUNK_PACKET_ID, 0, 0, packet.toByteArray());
    }

    /**
     * Serializes one non-empty Overworld subchunk for SubChunkPacket.
     * Subchunk Y is absolute (block Y divided by 16), from -4 through 19.
     * Returns null for an all-air section, which uses SuccessAllAir on the wire.
     */
    public static byte[] createSubChunkData(WorldChunk chunk, int subChunkY) {
        if (chunk == null) throw new IllegalArgumentException("chunk must not be null");
        if (subChunkY < -4 || subChunkY > 19) {
            throw new IllegalArgumentException("Overworld subchunk Y must be between -4 and 19");
        }
        byte[] raw = chunk.copySectionRaw(subChunkY + NEGATIVE_SUB_CHUNKS);
        if (raw == null || isEmpty(raw)) return null;
        Writer out = new Writer();
        writeSubChunk(out, subChunkY, raw);
        return out.toByteArray();
    }

    private static void writeEmptySubChunk(Writer out, int sectionY) {
        out.byteValue(9); // SubChunk version 9
        out.byteValue(2); // Vanilla empty sections carry block and liquid storage layers
        out.byteValue(sectionY);
        writeAirStorage(out);
        writeAirStorage(out);
    }

    private static void writeSubChunk(Writer out, int sectionY, byte[] raw) {
        out.byteValue(9); // SubChunk version 9
        out.byteValue(1); // One block-storage layer
        out.byteValue(sectionY);
        if (raw == null || isEmpty(raw)) {
            out.byteValue(2); // Empty vanilla section: block and liquid layers
            writeAirStorage(out);
            writeAirStorage(out);
            return;
        }

        int[] paletteIndexByBlockId = new int[BLOCK_TYPE_COUNT];
        int[] palette = new int[paletteIndexByBlockId.length];
        int paletteSize = 0;
        int[] indexes = new int[ChunkSection.VOLUME];
        for (int i = 0; i < raw.length; i++) {
            int blockId = Byte.toUnsignedInt(raw[i]);
            BlockType type = BlockType.byInternalId(blockId);
            int paletteIndexPlusOne = paletteIndexByBlockId[blockId];
            int paletteIndex;
            if (paletteIndexPlusOne == 0) {
                paletteIndex = paletteSize++;
                paletteIndexByBlockId[blockId] = paletteSize;
                palette[paletteIndex] = BedrockBlockRuntimeIds.forBlock(type);
            } else {
                paletteIndex = paletteIndexPlusOne - 1;
            }
            indexes[i] = paletteIndex;
        }
        writePalette(out, indexes, palette, paletteSize);
    }

    private static void writeAirStorage(Writer out) {
        out.byteValue(1); // V0 singleton palette, runtime IDs
        out.varInt32(BedrockBlockRuntimeIds.forBlock(BlockType.AIR));
    }

    private static boolean isEmpty(byte[] raw) {
        for (byte value : raw) if (value != 0) return false;
        return true;
    }

    private static void writePalette(Writer out, int[] indexes, int[] palette, int paletteSize) {
        if (paletteSize == 1) {
            out.byteValue(1); // V0, runtime palette
            out.varInt32(palette[0]);
            return;
        }

        int bits = 1;
        while ((1 << bits) < paletteSize) bits++;
        // Bedrock only accepts these packed-storage widths; skip 7 and 9..15.
        if (bits > 6 && bits < 8) bits = 8;
        if (bits > 8 && bits < 16) bits = 16;
        if (bits > 16) throw new IllegalArgumentException("Palette exceeds Bedrock's 16-bit storage limit");
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

        out.varInt32(paletteSize);
        for (int index = 0; index < paletteSize; index++) out.varInt32(palette[index]);
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
