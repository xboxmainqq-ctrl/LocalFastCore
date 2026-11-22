package ru.nexora.network.bedrock;

import ru.nexora.world.BlockType;
import ru.nexora.world.ChunkSection;
import ru.nexora.world.WorldChunk;

import java.io.ByteArrayOutputStream;

/** Encoder for the protocol-2225 FULL_CHUNK_DATA (LevelChunk) packet. */
public final class BedrockLevelChunkCodec {
    public static final int LEVEL_CHUNK_PACKET_ID = 0x3a;
    private static final int NEGATIVE_SUB_CHUNKS = 4;
    private static final int PLAINS_BIOME_ID = 1;
    private static final int BLOCK_TYPE_COUNT = BlockType.values().length;

    private BedrockLevelChunkCodec() {
    }

    public static BedrockPacket createPacket(WorldChunk chunk) {
        if (chunk == null) throw new IllegalArgumentException("chunk must not be null");

        byte[][] sections = chunk.copySectionsRaw();
        // Nukkit-MOT's NetworkChunkSerializer sends the four negative Overworld
        // sections followed only by positive sections through the highest non-empty one.
        // The 3D biome payload is still a full 24-section column.
        int highestNonEmptySectionCount = highestNonEmptySectionCount(sections);
        int subChunkCount = NEGATIVE_SUB_CHUNKS + highestNonEmptySectionCount;

        Writer chunkData = new Writer();
        for (int sectionIndex = 0; sectionIndex < subChunkCount; sectionIndex++) {
            int sectionY = sectionIndex - NEGATIVE_SUB_CHUNKS;
            writeSubChunk(chunkData, sectionY, sectionIndex < NEGATIVE_SUB_CHUNKS
                    ? null : sections[sectionIndex - NEGATIVE_SUB_CHUNKS]);
        }

        // Nukkit-MOT writes one 3D biome palette for every section in the dimension,
        // independently of the number of block subchunks sent above.
        for (int i = 0; i < WorldChunk.SECTION_COUNT; i++) {
            chunkData.byteValue(1); // V0 singleton palette, runtime IDs
            chunkData.varInt32(PLAINS_BIOME_ID);
        }
        chunkData.byteValue(0); // Border block count (empty)

        // Block entities are a sequence of little-endian network NBT compounds.
        // An empty sequence is encoded by a single TAG_End byte, not by EOF.
        chunkData.byteValue(0); // TAG_End: no block entities

        byte[] data = chunkData.toByteArray();
        Writer packet = new Writer();
        packet.varInt32(chunk.chunkX());
        packet.varInt32(chunk.chunkZ());
        packet.varInt32(0); // Dimension ID: Overworld
        packet.varUInt32(subChunkCount);
        // Nukkit's 1.26.40+ LevelChunkPacket encoder writes the optional request
        // presence flag and cache-metadata array even when both are empty.
        packet.bool(false); // requestSubChunks=false; no request limit follows
        packet.bool(false); // client chunk cache disabled
        packet.varUInt32(0); // empty blobIds array
        packet.byteArray(data);
        packet.bool(false); // clientBiomeUpdate

        return new BedrockPacket(LEVEL_CHUNK_PACKET_ID, 0, 0, packet.toByteArray());
    }

    private static int highestNonEmptySectionCount(byte[][] sections) {
        for (int i = sections.length - 1; i >= 0; i--) {
            if (sections[i] != null && !isEmpty(sections[i])) return i + 1;
        }
        return 0;
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
        // Nukkit-MOT EmptyChunkSection.writeTo(): v9, zero storage layers, signed section Y.
        out.byteValue(9);
        out.byteValue(0);
        out.byteValue(sectionY);
    }

    private static void writeSubChunk(Writer out, int sectionY, byte[] raw) {
        if (raw == null || isEmpty(raw)) {
            writeEmptySubChunk(out, sectionY);
            return;
        }

        // Nukkit-MOT LevelDBChunkSection emits one storage layer unless a real
        // second block layer exists. NEXORA currently stores only layer zero.
        out.byteValue(9);
        out.byteValue(1);
        out.byteValue(sectionY);

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
        // Match Nukkit-MOT BitArrayVersion: supported widths are 1,2,3,4,5,6,8,16.
        if (bits == 7) bits = 8;
        if (bits > 8 && bits < 16) bits = 16;
        if (bits > 16) throw new IllegalArgumentException("Palette exceeds Bedrock's 16-bit storage limit");
        int entriesPerWord = 32 / bits;
        int wordCount = (indexes.length + entriesPerWord - 1) / entriesPerWord;
        out.byteValue((bits << 1) | 1); // palette version bits plus runtime-ID flag

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
