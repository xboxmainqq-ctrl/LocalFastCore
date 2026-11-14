package ru.nexora.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Bedrock packet-batch compression envelope for NetherNet payloads.
 *
 * <p>Before NetworkSettings negotiation the batch is sent without an algorithm
 * byte. Afterwards the envelope starts with 0x00 (raw DEFLATE), 0x01
 * (Snappy), or 0xFF (uncompressed). This implementation supports raw DEFLATE
 * and uncompressed batches; Snappy is explicitly not implemented.</p>
 */
public final class BedrockBatchCompressionCodec {
    public static final int DEFLATE_ID = 0x00;
    public static final int SNAPPY_ID = 0x01;
    public static final int UNCOMPRESSED_ID = 0xFF;
    public static final int DEFAULT_MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024;

    private static final int BUFFER_BYTES = 8192;

    private BedrockBatchCompressionCodec() {
    }

    /** Copies an uncompressed batch unchanged before NetworkSettings negotiation. */
    public static ByteBuffer encodeBeforeNetworkSettings(ByteBuffer batch) {
        return readOnlyCopy(batch);
    }

    /** Decodes a pre-negotiation batch, which has no compression identifier. */
    public static ByteBuffer decodeBeforeNetworkSettings(ByteBuffer frame, int maxBytes) {
        Objects.requireNonNull(frame, "frame");
        if (maxBytes <= 0 || frame.remaining() == 0 || frame.remaining() > maxBytes) {
            throw new IllegalArgumentException("Pre-negotiation batch size is outside the allowed range");
        }
        return readOnlyCopy(frame);
    }

    public static ByteBuffer encodeAfterNetworkSettings(
            ByteBuffer batch,
            BedrockNetworkSettings settings
    ) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(settings, "settings");
        if (!batch.hasRemaining()) {
            throw new IllegalArgumentException("Cannot encode an empty packet batch");
        }

        BedrockNetworkSettings.CompressionAlgorithm algorithm = settings.compressionAlgorithm();
        boolean compressionDisabled = settings.compressionThreshold() == 0;
        boolean belowThreshold = batch.remaining() < settings.compressionThreshold();

        if (algorithm == BedrockNetworkSettings.CompressionAlgorithm.NONE
                || compressionDisabled || belowThreshold) {
            return withIdentifier(UNCOMPRESSED_ID, batch);
        }

        if (algorithm == BedrockNetworkSettings.CompressionAlgorithm.SNAPPY) {
            throw new UnsupportedOperationException("Snappy batch compression is not implemented");
        }

        byte[] compressed = deflateRaw(batch);
        ByteBuffer output = ByteBuffer.allocate(1 + compressed.length);
        output.put((byte) DEFLATE_ID).put(compressed).flip();
        return output.asReadOnlyBuffer();
    }

    public static ByteBuffer decodeAfterNetworkSettings(
            ByteBuffer frame,
            BedrockNetworkSettings settings,
            int maxDecompressedBytes
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(settings, "settings");
        if (maxDecompressedBytes <= 0 || !frame.hasRemaining()\n                || frame.remaining() - 1 > DEFAULT_MAX_DECOMPRESSED_BYTES) {
            throw new IllegalArgumentException("Compressed batch frame is empty or limit is invalid");
        }

        int identifier = Byte.toUnsignedInt(frame.get());
        if (identifier == UNCOMPRESSED_ID) {
            if (frame.remaining() == 0 || frame.remaining() > maxDecompressedBytes) {
                throw new IllegalArgumentException("Uncompressed batch size is outside the allowed range");
            }
            return readOnlyCopy(frame);
        }
        if (identifier == SNAPPY_ID) {
            throw new UnsupportedOperationException("Snappy batch decompression is not implemented");
        }
        if (identifier != DEFLATE_ID) {
            throw new IllegalArgumentException("Unknown Bedrock batch compression identifier: " + identifier);
        }
        if (settings.compressionAlgorithm() != BedrockNetworkSettings.CompressionAlgorithm.ZLIB) {
            throw new IllegalArgumentException("Received DEFLATE batch without ZLIB negotiation");
        }

        byte[] compressed = new byte[frame.remaining()];
        frame.get(compressed);
        return ByteBuffer.wrap(inflateRaw(compressed, maxDecompressedBytes)).asReadOnlyBuffer();
    }

    public static ByteBuffer decodeAfterNetworkSettings(
            ByteBuffer frame,
            BedrockNetworkSettings settings
    ) {
        return decodeAfterNetworkSettings(frame, settings, DEFAULT_MAX_DECOMPRESSED_BYTES);
    }

    private static byte[] deflateRaw(ByteBuffer input) {
        byte[] source = new byte[input.remaining()];
        input.duplicate().get(source);
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(source);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(source.length, 8192));
            byte[] buffer = new byte[BUFFER_BYTES];
            while (!deflater.finished()) {
                int written = deflater.deflate(buffer);
                if (written == 0) {
                    throw new IllegalStateException("Raw DEFLATE encoder made no progress before finishing the batch");
                }
                output.write(buffer, 0, written);
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflateRaw(byte[] compressed, int maxOutputBytes) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.min(maxOutputBytes, Math.max(32, compressed.length * 2))
            );
            byte[] buffer = new byte[BUFFER_BYTES];
            while (!inflater.finished()) {
                int written;
                try {
                    written = inflater.inflate(buffer);
                } catch (DataFormatException malformed) {
                    throw new IllegalArgumentException("Malformed raw DEFLATE batch", malformed);
                }
                if (written > 0) {
                    if (output.size() > maxOutputBytes - written) {
                        throw new IllegalArgumentException("Decompressed batch exceeds the configured limit");
                    }
                    output.write(buffer, 0, written);
                    continue;
                }
                if (inflater.needsDictionary()) {
                    throw new IllegalArgumentException("Raw DEFLATE batch requires an unsupported dictionary");
                }
                if (inflater.needsInput()) {
                    throw new IllegalArgumentException("Truncated raw DEFLATE batch");
                }
                throw new IllegalArgumentException("Raw DEFLATE decoder made no progress");
            }
            if (inflater.getRemaining() != 0) {
                throw new IllegalArgumentException("Trailing bytes after raw DEFLATE batch");
            }
            if (output.size() == 0) {
                throw new IllegalArgumentException("Decompressed packet batch is empty");
            }
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static ByteBuffer withIdentifier(int identifier, ByteBuffer input) {
        ByteBuffer output = ByteBuffer.allocate(1 + input.remaining());
        output.put((byte) identifier).put(input.duplicate()).flip();
        return output.asReadOnlyBuffer();
    }

    private static ByteBuffer readOnlyCopy(ByteBuffer input) {
        Objects.requireNonNull(input, "input");
        ByteBuffer output = ByteBuffer.allocate(input.remaining());
        output.put(input.duplicate()).flip();
        return output.asReadOnlyBuffer();
    }
}
