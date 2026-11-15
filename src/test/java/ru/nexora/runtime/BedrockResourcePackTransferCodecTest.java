package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BedrockResourcePackTransferCodecTest {
    private static final UUID UUID_VALUE =
            UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void encodesPackInfoAndStackWithMatchingIdentity() {
        BedrockResourcePackTransferCodec.Pack pack = pack("zip-data");
        ByteBuffer info = BedrockResourcePackTransferCodec
                .encodeResourcePacksInfoPayload(List.of(pack), true)
                .order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(1, info.get() & 0xff);
        assertEquals(0, info.get() & 0xff);
        assertEquals(0, info.get() & 0xff);
        assertEquals(0, info.get() & 0xff);
        assertEquals(0L, info.getLong());
        assertEquals(0L, info.getLong());
        assertEquals("1.0.0", BedrockBinary.readString(info, 16));
        assertEquals(1, BedrockBinary.readVarUInt32(info));
        assertEquals(UUID_VALUE.getMostSignificantBits(), info.getLong());
        assertEquals(UUID_VALUE.getLeastSignificantBits(), info.getLong());
        assertEquals("2.4.1", BedrockBinary.readString(info, 64));
        assertEquals("zip-data".length(), info.getLong());
        assertEquals("", BedrockBinary.readString(info, 256));
        assertEquals("", BedrockBinary.readString(info, 256));
        assertEquals("", BedrockBinary.readString(info, 256));
        assertEquals(0, info.get() & 0xff);
        assertEquals(0, info.get() & 0xff);
        assertEquals(0, info.get() & 0xff);
        assertEquals("", BedrockBinary.readString(info, 2048));
        assertFalse(info.hasRemaining());

        ByteBuffer stack = BedrockResourcePackTransferCodec
                .encodeResourcePackStackPayload(List.of(pack), "1.26.60", true);
        assertEquals(1, stack.get() & 0xff);
        assertEquals(1, BedrockBinary.readVarUInt32(stack));
        assertEquals(UUID_VALUE.toString(), BedrockBinary.readString(stack, 64));
        assertEquals("2.4.1", BedrockBinary.readString(stack, 64));
        assertEquals("", BedrockBinary.readString(stack, 256));
        assertEquals("1.26.60", BedrockBinary.readString(stack, 64));
        assertEquals(0, BedrockBinary.readLittleEndianInt32(stack));
        assertEquals(0, stack.get() & 0xff);
        assertEquals(0, stack.get() & 0xff);
        assertFalse(stack.hasRemaining());
    }

    @Test
    void encodesDataInfoAndChunkWithCorrectHashAndOffsets() throws Exception {
        byte[] archive = new byte[BedrockResourcePackTransferCodec.DEFAULT_CHUNK_BYTES + 17];
        for (int i = 0; i < archive.length; i++) archive[i] = (byte) (i * 31);
        BedrockResourcePackTransferCodec.Pack pack =
                new BedrockResourcePackTransferCodec.Pack(UUID_VALUE, "2.4.1", archive);

        ByteBuffer info = BedrockResourcePackTransferCodec.encodeDataInfoPayload(
                pack, BedrockResourcePackTransferCodec.DEFAULT_CHUNK_BYTES);
        assertEquals(pack.resourceName(), BedrockBinary.readString(info, 128));
        assertEquals(BedrockResourcePackTransferCodec.DEFAULT_CHUNK_BYTES,
                BedrockBinary.readLittleEndianInt32(info));
        assertEquals(2, BedrockBinary.readLittleEndianInt32(info));
        assertEquals(archive.length, BedrockBinary.readLittleEndianInt64(info));
        String expectedHash = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(archive));
        assertEquals(expectedHash, BedrockBinary.readString(info, 64));
        assertEquals(0, info.get() & 0xff);
        assertEquals(BedrockResourcePackTransferCodec.RESOURCE_PACK_TYPE_RESOURCES,
                info.get() & 0xff);
        assertFalse(info.hasRemaining());

        ByteBuffer chunk = BedrockResourcePackTransferCodec.encodeChunkDataPayload(
                pack, 1, BedrockResourcePackTransferCodec.DEFAULT_CHUNK_BYTES);
        assertEquals(pack.resourceName(), BedrockBinary.readString(chunk, 128));
        assertEquals(1, BedrockBinary.readLittleEndianInt32(chunk));
        assertEquals(BedrockResourcePackTransferCodec.DEFAULT_CHUNK_BYTES,
                BedrockBinary.readLittleEndianInt64(chunk));
        int length = BedrockBinary.readVarUInt32(chunk);
        assertEquals(17, length);
        byte[] actual = new byte[length];
        chunk.get(actual);
        assertArrayEquals(java.util.Arrays.copyOfRange(archive,
                BedrockResourcePackTransferCodec.DEFAULT_CHUNK_BYTES, archive.length), actual);
        assertFalse(chunk.hasRemaining());
    }

    @Test
    void decodesChunkRequestAndRejectsUnknownChunks() {
        ByteBuffer request = ByteBuffer.allocate(160);
        BedrockBinary.writeString(request, UUID_VALUE + "_2.4.1", 128);
        BedrockBinary.writeLittleEndianInt32(request, 7);
        request.flip();

        BedrockResourcePackTransferCodec.ChunkRequest decoded =
                BedrockResourcePackTransferCodec.decodeChunkRequestPayload(request);
        assertEquals(UUID_VALUE + "_2.4.1", decoded.resourceName());
        assertEquals(7, decoded.chunkIndex());

        BedrockResourcePackTransferCodec.Pack pack = pack("tiny");
        assertThrows(IllegalArgumentException.class,
                () -> BedrockResourcePackTransferCodec.encodeChunkDataPayload(pack, 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockResourcePackTransferCodec.Pack(UUID_VALUE, "not-a-version", new byte[]{1}));
    }

    private static BedrockResourcePackTransferCodec.Pack pack(String text) {
        return new BedrockResourcePackTransferCodec.Pack(
                UUID_VALUE, "2.4.1", text.getBytes(StandardCharsets.UTF_8));
    }
}
