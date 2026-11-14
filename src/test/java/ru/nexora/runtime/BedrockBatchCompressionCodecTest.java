package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BedrockBatchCompressionCodecTest {
    private static final byte[] BATCH = new byte[] {
            5, (byte) 0xC1, 1, 0, 0, 8, (byte) 0xB1
    };

    @Test
    void leavesPreNegotiationBatchWithoutAnAlgorithmByte() {
        ByteBuffer encoded = BedrockBatchCompressionCodec.encodeBeforeNetworkSettings(
                ByteBuffer.wrap(BATCH)
        );
        assertEquals(BATCH.length, encoded.remaining());
        assertArrayEquals(BATCH, bytes(encoded));

        ByteBuffer decoded = BedrockBatchCompressionCodec.decodeBeforeNetworkSettings(
                encoded, 100
        );
        assertArrayEquals(BATCH, bytes(decoded));
    }

    @Test
    void usesFfIdentifierForNegotiatedNoCompression() {
        BedrockNetworkSettings settings = settings(
                BedrockNetworkSettings.CompressionAlgorithm.NONE, 1
        );
        ByteBuffer encoded = BedrockBatchCompressionCodec.encodeAfterNetworkSettings(
                ByteBuffer.wrap(BATCH), settings
        );

        assertEquals(0xFF, Byte.toUnsignedInt(encoded.get()));
        assertArrayEquals(BATCH, bytes(
                BedrockBatchCompressionCodec.decodeAfterNetworkSettings(encoded, settings)
        ));
    }

    @Test
    void usesFfWhenCompressionThresholdDisablesCompressionOrBatchIsSmall() {
        BedrockNetworkSettings disabled = settings(
                BedrockNetworkSettings.CompressionAlgorithm.ZLIB, 0
        );
        ByteBuffer disabledFrame = BedrockBatchCompressionCodec.encodeAfterNetworkSettings(
                ByteBuffer.wrap(BATCH), disabled
        );
        assertEquals(0xFF, Byte.toUnsignedInt(disabledFrame.get()));

        BedrockNetworkSettings highThreshold = settings(
                BedrockNetworkSettings.CompressionAlgorithm.ZLIB, BATCH.length + 1
        );
        ByteBuffer smallFrame = BedrockBatchCompressionCodec.encodeAfterNetworkSettings(
                ByteBuffer.wrap(BATCH), highThreshold
        );
        assertEquals(0xFF, Byte.toUnsignedInt(smallFrame.get()));
    }

    @Test
    void roundTripsRawDeflateWithoutZlibWrapper() {
        byte[] repetitive = new byte[4096];
        Arrays.fill(repetitive, (byte) 0x41);
        BedrockNetworkSettings settings = settings(
                BedrockNetworkSettings.CompressionAlgorithm.ZLIB, 1
        );

        ByteBuffer encoded = BedrockBatchCompressionCodec.encodeAfterNetworkSettings(
                ByteBuffer.wrap(repetitive), settings
        );

        assertEquals(0x00, Byte.toUnsignedInt(encoded.get(0)));
        assertTrue(encoded.remaining() < repetitive.length);
        assertArrayEquals(repetitive, bytes(
                BedrockBatchCompressionCodec.decodeAfterNetworkSettings(encoded, settings)
        ));
    }

    @Test
    void rejectsUnknownAlgorithmAndUnsupportedSnappy() {
        BedrockNetworkSettings snappy = settings(
                BedrockNetworkSettings.CompressionAlgorithm.SNAPPY, 1
        );
        assertThrows(UnsupportedOperationException.class,
                () -> BedrockBatchCompressionCodec.encodeAfterNetworkSettings(
                        ByteBuffer.wrap(BATCH), snappy
                ));
        assertThrows(UnsupportedOperationException.class,
                () -> BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                        ByteBuffer.wrap(new byte[] {1, 2, 3}), snappy
                ));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                        ByteBuffer.wrap(new byte[] {2, 1, 2}), snappy
                ));
    }

    @Test
    void rejectsWrongNegotiatedAlgorithmForDeflate() {
        BedrockNetworkSettings none = settings(
                BedrockNetworkSettings.CompressionAlgorithm.NONE, 1
        );
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                        ByteBuffer.wrap(new byte[] {0, 1, 2}), none
                ));
    }

    @Test
    void enforcesDecompressionLimitAndRejectsTruncatedData() {
        byte[] repetitive = new byte[4096];
        Arrays.fill(repetitive, (byte) 0x42);
        BedrockNetworkSettings settings = settings(
                BedrockNetworkSettings.CompressionAlgorithm.ZLIB, 1
        );
        ByteBuffer compressed = BedrockBatchCompressionCodec.encodeAfterNetworkSettings(
                ByteBuffer.wrap(repetitive), settings
        );

        assertThrows(IllegalArgumentException.class,
                () -> BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                        compressed, settings, 128
                ));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                        ByteBuffer.wrap(new byte[] {0, 1, 2}), settings
                ));
    }

    private static BedrockNetworkSettings settings(
            BedrockNetworkSettings.CompressionAlgorithm algorithm,
            int threshold
    ) {
        return new BedrockNetworkSettings(threshold, algorithm, false, 0, 0.0f);
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
}
