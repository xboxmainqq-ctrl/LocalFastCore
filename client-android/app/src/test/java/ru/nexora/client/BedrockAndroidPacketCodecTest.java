package ru.nexora.client;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;

import static org.junit.Assert.*;

public class BedrockAndroidPacketCodecTest {
    private static final String CONNECTION_REQUEST =
            "{\"AuthenticationType\":0,\"Token\":\"a.b.c\",\"Certificate\":\"chain\"}";

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
        byte[] encoded = BedrockAndroidPacketCodec.encodeLoginBatch(CONNECTION_REQUEST, settings);

        assertEquals(0xff, Byte.toUnsignedInt(encoded[0]));
        assertLoginBatch(ByteBuffer.wrap(encoded, 1, encoded.length - 1).slice());
    }

    @Test
    public void encodesLoginWithRawDeflateWhenThresholdIsReached() throws Exception {
        BedrockAndroidNetworkSettings settings =
                new BedrockAndroidNetworkSettings(1, 0, false, 0, 0.0f);
        byte[] encoded = BedrockAndroidPacketCodec.encodeLoginBatch(CONNECTION_REQUEST, settings);

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
        BedrockAndroidPacketCodec.encodeLoginBatch(CONNECTION_REQUEST, settings);
    }

    private static void assertLoginBatch(ByteBuffer batch) {
        int packetLength = readVarUInt32(batch);
        assertEquals(packetLength, batch.remaining());
        assertEquals(1, readVarUInt32(batch));
        assertEquals(2225, batch.getInt());
        int requestLength = readVarUInt32(batch);
        byte[] request = new byte[requestLength];
        batch.get(request);
        assertEquals(CONNECTION_REQUEST, new String(request, StandardCharsets.UTF_8));
        assertFalse(batch.hasRemaining());
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
