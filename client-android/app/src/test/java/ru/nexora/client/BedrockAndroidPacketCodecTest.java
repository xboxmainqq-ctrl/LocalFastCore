package ru.nexora.client;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.Assert.*;

public class BedrockAndroidPacketCodecTest {
    private static final String AUTHENTICATION_PAYLOAD_JSON =
            "{\"AuthenticationType\":0,\"Token\":\"a.b.c\",\"Certificate\":\"\"}";
    private static final String CLIENT_DATA_JWT =
            "eyJhbGciOiJFUzM4NCJ9.eyJjbGllbnQiOiJ0ZXN0In0.c2ln";

    @Test
    public void encodesRequestNetworkSettingsAsUncompressedBatch() {
        ByteBuffer batch = ByteBuffer.wrap(BedrockAndroidPacketCodec.encodeRequestNetworkSettings());

        assertEquals(6, readVarUInt32(batch));
        assertEquals(193, readVarUInt32(batch));
        assertEquals(2225, batch.getInt());
        assertFalse(batch.hasRemaining());
    }

    @Test
    public void decodesLittleEndianNetworkSettingsPayload() {
        ByteBuffer batch = ByteBuffer.allocate(13);
        writeVarUInt32(batch, 12);
        writeVarUInt32(batch, 143);
        batch.order(ByteOrder.LITTLE_ENDIAN);
        batch.putShort((short) 256);
        batch.putShort((short) 0);
        batch.put((byte) 1);
        batch.put((byte) 7);
        batch.putFloat(0.5f);

        BedrockAndroidNetworkSettings settings =
                BedrockAndroidPacketCodec.decodeNetworkSettings(batch.array());

        assertEquals(256, settings.compressionThreshold());
        assertEquals(0, settings.compressionAlgorithm());
        assertTrue(settings.clientThrottleEnabled());
        assertEquals(7, settings.clientThrottleThreshold());
        assertEquals(0.5f, settings.clientThrottleScalar(), 0.0001f);
    }

    @Test
    public void encodesLoginWithUncompressedEnvelopeWhenThresholdIsZero() {
        BedrockAndroidNetworkSettings settings =
                new BedrockAndroidNetworkSettings(0, 0, false, 0, 0.0f);
        byte[] encoded = BedrockAndroidPacketCodec.encodeLoginBatch(AUTHENTICATION_PAYLOAD_JSON, CLIENT_DATA_JWT, settings);

        assertEquals(0xff, Byte.toUnsignedInt(encoded[0]));
        assertLoginBatch(ByteBuffer.wrap(encoded, 1, encoded.length - 1).slice());
    }

    @Test
    public void encodesLoginWithRawDeflateWhenThresholdIsReached() throws Exception {
        BedrockAndroidNetworkSettings settings =
                new BedrockAndroidNetworkSettings(1, 0, false, 0, 0.0f);
        byte[] encoded = BedrockAndroidPacketCodec.encodeLoginBatch(AUTHENTICATION_PAYLOAD_JSON, CLIENT_DATA_JWT, settings);

        assertEquals(0x00, Byte.toUnsignedInt(encoded[0]));
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(encoded, 1, encoded.length - 1);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    fail("Truncated raw DEFLATE Login batch");
                }
                output.write(buffer, 0, count);
            }
            assertLoginBatch(ByteBuffer.wrap(output.toByteArray()));
        } finally {
            inflater.end();
        }
    }

    @Test(expected = UnsupportedOperationException.class)
    public void rejectsSnappyWhenCompressionIsRequired() {
        BedrockAndroidNetworkSettings settings =
                new BedrockAndroidNetworkSettings(1, 1, false, 0, 0.0f);
        BedrockAndroidPacketCodec.encodeLoginBatch(AUTHENTICATION_PAYLOAD_JSON, CLIENT_DATA_JWT, settings);
    }


    @Test
    public void decodesUncompressedServerPlayStatusBatch() {
        BedrockAndroidNetworkSettings settings =
                new BedrockAndroidNetworkSettings(0, 0, false, 0, 0.0f);
        List<BedrockAndroidServerPacket> packets = BedrockAndroidPacketCodec.decodeServerBatch(
                playStatusEnvelope(0, false), settings);

        assertEquals(1, packets.size());
        assertEquals(BedrockAndroidPlayStatus.LOGIN_SUCCESS,
                BedrockAndroidPacketCodec.decodePlayStatus(packets.get(0)));
    }

    @Test
    public void decodesRawDeflateServerPlayStatusBatch() {
        BedrockAndroidNetworkSettings settings =
                new BedrockAndroidNetworkSettings(1, 0, false, 0, 0.0f);
        List<BedrockAndroidServerPacket> packets = BedrockAndroidPacketCodec.decodeServerBatch(
                playStatusEnvelope(3, true), settings);

        assertEquals(BedrockAndroidPlayStatus.PLAYER_SPAWN,
                BedrockAndroidPacketCodec.decodePlayStatus(packets.get(0)));
    }

    @Test
    public void rejectsSnappyAndUnknownServerCompression() {
        BedrockAndroidNetworkSettings settings =
                new BedrockAndroidNetworkSettings(1, 0, false, 0, 0.0f);
        assertThrows(UnsupportedOperationException.class,
                () -> BedrockAndroidPacketCodec.decodeServerBatch(new byte[] {1, 0}, settings));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockAndroidPacketCodec.decodeServerBatch(new byte[] {2, 0}, settings));
    }

    @Test
    public void decodesEmptyBedrockResourcePackInfoAndStack() {
        ByteBuffer info = ByteBuffer.allocate(64);
        info.put(new byte[] {0, 0, 0, 0});
        info.put(new byte[16]);
        writeVarUInt32(info, 5);
        info.put("1.0.0".getBytes(StandardCharsets.UTF_8));
        writeVarUInt32(info, 0);
        BedrockAndroidPacketCodec.decodeEmptyResourcePacksInfo(
                java.util.Arrays.copyOf(info.array(), info.position()));

        ByteBuffer stack = ByteBuffer.allocate(64);
        stack.put((byte) 0);
        writeVarUInt32(stack, 0);
        writeVarUInt32(stack, 7);
        stack.put("1.26.60".getBytes(StandardCharsets.UTF_8));
        stack.order(ByteOrder.LITTLE_ENDIAN).putInt(0);
        stack.put((byte) 0).put((byte) 0);
        BedrockAndroidPacketCodec.decodeEmptyResourcePackStack(
                java.util.Arrays.copyOf(stack.array(), stack.position()));
    }

    @Test
    public void encodesNumericBedrockResourcePackResponseStatuses() {
        BedrockAndroidNetworkSettings settings =
                new BedrockAndroidNetworkSettings(0, 0xffff, false, 0, 0.0f);
        byte[] encoded = BedrockAndroidPacketCodec.encodeResourcePackClientResponse(
                BedrockAndroidResourcePackStatus.HAVE_ALL_PACKS, settings);
        List<BedrockAndroidServerPacket> packets =
                BedrockAndroidPacketCodec.decodeServerBatch(encoded, settings);
        assertEquals(1, packets.size());
        assertEquals(8, packets.get(0).packetId());
        ByteBuffer payload = ByteBuffer.wrap(packets.get(0).payload());
        assertEquals(3, readVarUInt32(payload));
        assertEquals("downloadingfinished", readTestString(payload));
        assertFalse(payload.hasRemaining());

        byte[] completed = BedrockAndroidPacketCodec.encodeResourcePackClientResponse(
                BedrockAndroidResourcePackStatus.COMPLETED, settings);
        ByteBuffer completedPayload = ByteBuffer.wrap(
                BedrockAndroidPacketCodec.decodeServerBatch(completed, settings).get(0).payload());
        assertEquals(4, readVarUInt32(completedPayload));
        assertEquals("resourcepackstackfinished", readTestString(completedPayload));
        assertFalse(completedPayload.hasRemaining());
    }

    private static String readTestString(ByteBuffer input) {
        int length = readVarUInt32(input);
        byte[] bytes = new byte[length];
        input.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    public void decodesBedrockResourcePackInfoDataChunkAndStack() throws Exception {
        UUID uuid = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        ByteBuffer info = ByteBuffer.allocate(512);
        info.put(new byte[] {1, 0, 0, 0});
        writeLittleEndianLong(info, 0L);
        writeLittleEndianLong(info, 0L);
        writeTestString(info, "1.0.0");
        writeVarUInt32(info, 1);
        writeLittleEndianLong(info, uuid.getMostSignificantBits());
        writeLittleEndianLong(info, uuid.getLeastSignificantBits());
        writeTestString(info, "1.2.3");
        writeLittleEndianLong(info, 8L);
        writeTestString(info, "");
        writeTestString(info, "");
        writeTestString(info, "");
        info.put(new byte[] {0, 0, 0});
        writeTestString(info, "");
        BedrockAndroidResourcePackInfo decodedInfo =
                BedrockAndroidPacketCodec.decodeResourcePacksInfo(
                        java.util.Arrays.copyOf(info.array(), info.position()));
        assertTrue(decodedInfo.required());
        assertEquals(1, decodedInfo.packs().size());
        assertEquals(uuid + "_1.2.3", decodedInfo.packs().get(0).resourceName());

        String hash = "ab".repeat(32);
        ByteBuffer dataInfo = ByteBuffer.allocate(256);
        writeTestString(dataInfo, uuid + "_1.2.3");
        writeLittleEndianInt(dataInfo, 4);
        writeLittleEndianInt(dataInfo, 2);
        writeLittleEndianLong(dataInfo, 8L);
        writeTestString(dataInfo, hash);
        dataInfo.put((byte) 0).put((byte) 6);
        BedrockAndroidResourcePackDataInfo decodedDataInfo =
                BedrockAndroidPacketCodec.decodeResourcePackDataInfo(
                        java.util.Arrays.copyOf(dataInfo.array(), dataInfo.position()));
        assertEquals(4, decodedDataInfo.chunkSize());
        assertEquals(2, decodedDataInfo.chunkCount());
        assertEquals(hash, decodedDataInfo.fileHash());

        ByteBuffer chunk = ByteBuffer.allocate(256);
        writeTestString(chunk, uuid + "_1.2.3");
        writeLittleEndianInt(chunk, 1);
        writeLittleEndianLong(chunk, 4L);
        writeVarUInt32(chunk, 4);
        chunk.put(new byte[] {4, 5, 6, 7});
        BedrockAndroidResourcePackChunk decodedChunk =
                BedrockAndroidPacketCodec.decodeResourcePackChunkData(
                        java.util.Arrays.copyOf(chunk.array(), chunk.position()));
        assertEquals(1, decodedChunk.chunkIndex());
        assertEquals(4L, decodedChunk.offset());
        assertArrayEquals(new byte[] {4, 5, 6, 7}, decodedChunk.data());

        ByteBuffer stack = ByteBuffer.allocate(256);
        stack.put((byte) 1);
        writeVarUInt32(stack, 1);
        writeTestString(stack, uuid.toString());
        writeTestString(stack, "1.2.3");
        writeTestString(stack, "");
        writeTestString(stack, "1.26.60");
        writeLittleEndianInt(stack, 0);
        stack.put(new byte[] {0, 0});
        BedrockAndroidResourcePackStack decodedStack =
                BedrockAndroidPacketCodec.decodeResourcePackStack(
                        java.util.Arrays.copyOf(stack.array(), stack.position()));
        assertTrue(decodedStack.required());
        assertEquals(List.of(uuid + "_1.2.3"), decodedStack.resourceNames());
    }

    @Test
    public void encodesResourcePacksReadyForValidationPacket() {
        BedrockAndroidNetworkSettings settings =
                new BedrockAndroidNetworkSettings(0, 0xffff, false, 0, 0.0f);
        byte[] encoded = BedrockAndroidPacketCodec.encodeResourcePacksReadyForValidation(settings);
        List<BedrockAndroidServerPacket> packets =
                BedrockAndroidPacketCodec.decodeServerBatch(encoded, settings);
        assertEquals(1, packets.size());
        assertEquals(340, packets.get(0).packetId());
        assertEquals(0, packets.get(0).payload().length);
    }

    @Test
    public void encodesChunkRequestsAndSendPacksResponse() {
        BedrockAndroidNetworkSettings settings =
                new BedrockAndroidNetworkSettings(0, 0xffff, false, 0, 0.0f);
        String packId = "12345678-1234-5678-9abc-def012345678_1.2.3";
        byte[] request = BedrockAndroidPacketCodec.encodeResourcePackChunkRequest(packId, 3, settings);
        BedrockAndroidServerPacket requestPacket =
                BedrockAndroidPacketCodec.decodeServerBatch(request, settings).get(0);
        assertEquals(84, requestPacket.packetId());
        ByteBuffer requestPayload = ByteBuffer.wrap(requestPacket.payload());
        assertEquals(packId, readTestString(requestPayload));
        assertEquals(3, readLittleEndianInt(requestPayload));

        byte[] response = BedrockAndroidPacketCodec.encodeResourcePackClientResponse(
                BedrockAndroidResourcePackStatus.SEND_PACKS, List.of(packId), settings);
        BedrockAndroidServerPacket responsePacket =
                BedrockAndroidPacketCodec.decodeServerBatch(response, settings).get(0);
        assertEquals(8, responsePacket.packetId());
        ByteBuffer responsePayload = ByteBuffer.wrap(responsePacket.payload());
        assertEquals(2, readVarUInt32(responsePayload));
        assertEquals("downloading", readTestString(responsePayload));
        assertEquals(1, readVarUInt32(responsePayload));
        assertEquals(packId, readTestString(responsePayload));
    }

    @Test
    public void assemblesAndVerifiesDownloadedResourcePack() throws Exception {
        byte[] bytes = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8};
        String hash = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        BedrockAndroidResourcePackDataInfo info =
                new BedrockAndroidResourcePackDataInfo("pack_1.0.0", 4, 3,
                        bytes.length, hash, 6);
        File directory = Files.createTempDirectory("nexora-pack-test").toFile();
        BedrockAndroidResourcePackDownload download =
                new BedrockAndroidResourcePackDownload(directory, info);
        download.accept(new BedrockAndroidResourcePackChunk("pack_1.0.0", 0, 0,
                new byte[] {0, 1, 2, 3}));
        download.accept(new BedrockAndroidResourcePackChunk("pack_1.0.0", 1, 4,
                new byte[] {4, 5, 6, 7}));
        download.accept(new BedrockAndroidResourcePackChunk("pack_1.0.0", 2, 8,
                new byte[] {8}));
        assertTrue(download.isComplete());
        File result = download.finishAndVerify();
        assertArrayEquals(bytes, Files.readAllBytes(result.toPath()));
        download.close();
        result.delete();
        directory.delete();
    }

    private static void writeTestString(ByteBuffer output, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeVarUInt32(output, encoded.length);
        output.put(encoded);
    }

    private static void writeLittleEndianInt(ByteBuffer output, int value) {
        output.put((byte) value).put((byte) (value >>> 8))
                .put((byte) (value >>> 16)).put((byte) (value >>> 24));
    }

    private static int readLittleEndianInt(ByteBuffer input) {
        return Byte.toUnsignedInt(input.get()) | (Byte.toUnsignedInt(input.get()) << 8)
                | (Byte.toUnsignedInt(input.get()) << 16) | (input.get() << 24);
    }

    private static void writeLittleEndianLong(ByteBuffer output, long value) {
        for (int i = 0; i < 8; i++) output.put((byte) (value >>> (8 * i)));
    }

    private static void assertLoginBatch(ByteBuffer batch) {
        int packetLength = readVarUInt32(batch);
        assertEquals(packetLength, batch.remaining());
        assertEquals(1, readVarUInt32(batch));
        assertEquals(2225, batch.getInt());
        int jwtBlockLength = readVarUInt32(batch);
        assertEquals(jwtBlockLength, batch.remaining());
        batch.order(ByteOrder.LITTLE_ENDIAN);
        int authLength = batch.getInt();
        byte[] auth = new byte[authLength];
        batch.get(auth);
        int clientDataLength = batch.getInt();
        byte[] clientData = new byte[clientDataLength];
        batch.get(clientData);
        assertEquals(AUTHENTICATION_PAYLOAD_JSON, new String(auth, StandardCharsets.UTF_8));
        assertEquals(CLIENT_DATA_JWT, new String(clientData, StandardCharsets.UTF_8));
        assertFalse(batch.hasRemaining());
    }


    private static byte[] playStatusEnvelope(int status, boolean compress) {
        ByteBuffer batch = ByteBuffer.allocate(6);
        writeVarUInt32(batch, 5);
        writeVarUInt32(batch, 2);
        batch.putInt(status).flip();
        byte[] raw = new byte[batch.remaining()];
        batch.get(raw);
        if (!compress) {
            byte[] envelope = new byte[raw.length + 1];
            envelope[0] = (byte) 0xff;
            System.arraycopy(raw, 0, envelope, 1, raw.length);
            return envelope;
        }
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[128];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                output.write(buffer, 0, count);
            }
            byte[] compressed = output.toByteArray();
            byte[] envelope = new byte[compressed.length + 1];
            envelope[0] = 0;
            System.arraycopy(compressed, 0, envelope, 1, compressed.length);
            return envelope;
        } finally {
            deflater.end();
        }
    }

    private static int readVarUInt32(ByteBuffer input) {
        int value = 0;
        for (int index = 0; index < 5; index++) {
            int current = Byte.toUnsignedInt(input.get());
            value |= (current & 0x7f) << (index * 7);
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new AssertionError("Invalid test varuint32");
    }

    private static void writeVarUInt32(ByteBuffer output, int value) {
        while ((value & 0xffffff80) != 0) {
            output.put((byte) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        output.put((byte) value);
    }
}
