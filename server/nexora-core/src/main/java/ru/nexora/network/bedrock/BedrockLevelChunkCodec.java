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
        // Nukkit-MOT LevelDB chunks use sectionOffset=4 for the Overworld.
        // Their section array already starts at Y=-4, so there is no extra
        // four-section prefix (that prefix is only for offset-zero Anvil chunks).
        // The packet count is the highest non-empty section index plus one.
        int subChunkCount = highestNonEmptySectionCount(sections);

        Writer chunkData = new Writer();
        for (int sectionIndex = 0; sectionIndex < subChunkCount; sectionIndex++) {
            int sectionY = sectionIndex - NEGATIVE_SUB_CHUNKS;
            // Both NEXORA and Nukkit-MOT LevelDB map section index 0 to world
            // section Y=-4; the first four entries are real negative-Y sections.
            writeSubChunk(chunkData, sectionY, sections[sectionIndex]);
        }

        // LevelChunk's serialized column contains one biome storage for each
        // transmitted block subchunk, in the same bottom-to-top order. Sending
        // all dimension sections here (even when only a few block sections exist)
        // shifts the border-block/NBT tail and makes Bedrock reject packet 0x3A.
        for (int i = 0; i < subChunkCount; i++) {
            chunkData.byteValue(1); // V0 singleton palette, runtime IDs
            chunkData.varInt32(PLAINS_BIOME_ID);
        }
        chunkData.byteValue(0); // Border block count (empty)

        // Nukkit-MOT serializes block entities as concatenated little-endian
        // network NBT roots; an empty collection contributes zero bytes.
        byte[] data = chunkData.toByteArray();
        Writer packet = new Writer();
        packet.varInt32(chunk.chunkX());
        packet.varInt32(chunk.chunkZ());
        packet.varInt32(0); // Dimension ID: Overworld
        packet.varUInt32(subChunkCount);
        // Client Request SubChunk Limit is optional and is omitted entirely
        // for ordinary full-chunk delivery (it is present only in request mode).
        packet.bool(false); // client chunk cache disabled
        packet.varUInt32(0); // empty blobIds array
        packet.byteArray(data);
        packet.bool(false); // clientBiomeUpdate

        return new BedrockPacket(LEVEL_CHUNK_PACKET_ID, 0, 0, packet.toByteArray());
    }

    private static int highestNonEmptySectionCount(byte[][] sections) {
        // Match Nukkit-MOT NetworkChunkSerializer: sectionOffset=4 means the
        // section array already covers world Y=-4..19 without a synthetic prefix.
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

        // Nukkit-MOT's fresh LevelDBChunkSection has two storages. NEXORA has
        // no liquid/waterlogged layer yet, so the second storage is all-air.
        out.byteValue(9);
        out.byteValue(2);
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
        writeAirStorage(out); // layer 1: no liquid/waterlogged blocks in the current world model
    }

    private static void writeAirStorage(Writer out) {
        out.byteValue(1); // V0 singleton runtime palette
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
