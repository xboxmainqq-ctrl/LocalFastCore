package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Bedrock game-packet batch codec used above NetherNet data channels.
 *
 * NetherNet does not use RakNet's 0xFE batch marker or RakNet application
 * encryption. Before NetworkSettings, batches are uncompressed and have no
 * compression byte. After negotiation, each message starts with 0x00 (raw
 * DEFLATE) or 0xFF (uncompressed).
 */
public final class BedrockBatchCodec {
    public static final int REQUEST_NETWORK_SETTINGS_PACKET_ID = 0xC1;
    public static final int NETWORK_SETTINGS_PACKET_ID = 0x8F;
    public static final int PLAY_STATUS_PACKET_ID = 0x02;
    public static final int CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID = 0x04;
    public static final int RESOURCE_PACKS_INFO_PACKET_ID = 0x06;
    public static final int RESOURCE_PACK_STACK_PACKET_ID = 0x07;
    public static final int RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID = 0x08;

    public static final int COMPRESSION_ZLIB = 0;
    public static final int COMPRESSION_SNAPPY = 1;
    public static final int COMPRESSION_NONE = 0xffff;

    public static final int DEFAULT_MAX_BATCH_BYTES = 4 * 1024 * 1024;
    public static final int MAX_PACKETS_PER_BATCH = 812;

    private static final int COMPRESSION_HEADER_ZLIB = 0x00;
    private static final int COMPRESSION_HEADER_SNAPPY = 0x01;
    private static final int COMPRESSION_HEADER_NONE = 0xff;

    private BedrockBatchCodec() {
    }

    public static List<BedrockPacket> decode(byte[] message, boolean compressionNegotiated) {
        return decode(message, compressionNegotiated, DEFAULT_MAX_BATCH_BYTES);
    }

    public static List<BedrockPacket> decode(
            byte[] message,
            boolean compressionNegotiated,
            int maxBatchBytes
    ) {
        Objects.requireNonNull(message, "message");
        if (maxBatchBytes < 1) throw new IllegalArgumentException("maxBatchBytes must be >= 1");
        if (message.length > maxBatchBytes + 65_536) {
            throw new IllegalArgumentException("Bedrock message exceeds encoded size limit");
        }
        byte[] batch = compressionNegotiated
                ? decodeCompressionFrame(message, maxBatchBytes)
                : message;
        if (batch.length > maxBatchBytes) throw new IllegalArgumentException("Bedrock batch exceeds size limit");

        List<BedrockPacket> packets = new ArrayList<>();
        int[] offset = {0};
        while (offset[0] < batch.length) {
            if (packets.size() >= MAX_PACKETS_PER_BATCH) {
                throw new IllegalArgumentException("Bedrock batch contains too many packets");
            }
            long packetLengthLong = readUnsignedVarInt(batch, offset, batch.length);
            if (packetLengthLong < 1 || packetLengthLong > maxBatchBytes) {
                throw new IllegalArgumentException("Invalid Bedrock packet length");
            }
            int packetLength = (int) packetLengthLong;
            if (offset[0] > batch.length - packetLength) {
                throw new IllegalArgumentException("Truncated Bedrock packet in batch");
            }

            int packetEnd = offset[0] + packetLength;
            long header = readUnsignedVarInt(batch, offset, packetEnd);
            if (header > 0x3fff) throw new IllegalArgumentException("Invalid Bedrock packet header");
            int packetId = (int) (header & 0x3ff);
            int sender = (int) ((header >>> 10) & 0x3);
            int target = (int) ((header >>> 12) & 0x3);
            byte[] payload = new byte[packetEnd - offset[0]];
            System.arraycopy(batch, offset[0], payload, 0, payload.length);
            offset[0] = packetEnd;
            packets.add(new BedrockPacket(packetId, sender, target, payload));
        }
        if (packets.isEmpty()) throw new IllegalArgumentException("Empty Bedrock packet batch");
        return List.copyOf(packets);
    }

    public static byte[] encode(List<BedrockPacket> packets, boolean compressionNegotiated, int threshold) {
        return encode(packets, compressionNegotiated, threshold, DEFAULT_MAX_BATCH_BYTES);
    }

    public static byte[] encode(
            List<BedrockPacket> packets,
            boolean compressionNegotiated,
            int threshold,
            int maxBatchBytes
    ) {
        return encode(packets, compressionNegotiated, threshold, COMPRESSION_ZLIB, maxBatchBytes);
    }

    public static byte[] encode(
            List<BedrockPacket> packets,
            boolean compressionNegotiated,
            int threshold,
            int compressionAlgorithm,
            int maxBatchBytes
    ) {
        Objects.requireNonNull(packets, "packets");
        if (packets.isEmpty()) throw new IllegalArgumentException("Cannot encode an empty Bedrock batch");
        if (packets.size() > MAX_PACKETS_PER_BATCH) {
            throw new IllegalArgumentException("Bedrock batch contains too many packets");
        }
        if (threshold < 1 || threshold > 0xffff) {
            throw new IllegalArgumentException("Compression threshold must be 1..65535");
        }
        if (maxBatchBytes < 1) throw new IllegalArgumentException("maxBatchBytes must be >= 1");

        long encodedLength = 0;
        for (BedrockPacket packet : packets) {
            Objects.requireNonNull(packet, "packet");
            int header = packet.packetId()
                    | (packet.senderSubClientId() << 10)
                    | (packet.targetSubClientId() << 12);
            long packetLength = unsignedVarIntSize(header) + (long) packet.payloadLength();
            encodedLength += unsignedVarIntSize(packetLength) + packetLength;
            if (encodedLength > maxBatchBytes) {
                throw new IllegalArgumentException("Encoded Bedrock batch exceeds size limit");
            }
        }

        BatchBuffer batch = new BatchBuffer((int) encodedLength);
        for (BedrockPacket packet : packets) {
            int header = packet.packetId()
                    | (packet.senderSubClientId() << 10)
                    | (packet.targetSubClientId() << 12);
            int packetLength = unsignedVarIntSize(header) + packet.payloadLength();
            writeUnsignedVarInt(batch, packetLength);
            writeUnsignedVarInt(batch, header);
            packet.writePayloadTo(batch);
        }

        byte[] raw = batch.toOwnedByteArray();
        if (!compressionNegotiated) return raw;
        if (compressionAlgorithm == COMPRESSION_NONE) {
            return withCompressionHeader(COMPRESSION_HEADER_NONE, raw);
        }
        if (compressionAlgorithm != COMPRESSION_ZLIB) {
            throw new UnsupportedOperationException("Only Zlib and no compression are supported");
        }
        if (raw.length < threshold) return withCompressionHeader(COMPRESSION_HEADER_NONE, raw);
        return withCompressionHeader(COMPRESSION_HEADER_ZLIB, deflateRaw(raw));
    }

    /** Parses the first RequestNetworkSettings packet's four-byte big-endian protocol value. */
    public static int readRequestedProtocol(BedrockPacket packet) {
        Objects.requireNonNull(packet, "packet");
        if (packet.packetId() != REQUEST_NETWORK_SETTINGS_PACKET_ID) {
            throw new IllegalArgumentException("First Bedrock packet must be RequestNetworkSettings (0xC1)");
        }
        byte[] payload = packet.payload();
        if (payload.length != Integer.BYTES) {
            throw new IllegalArgumentException("RequestNetworkSettings payload must be exactly four bytes");
        }
        return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    /** Builds a PlayStatus packet. Status is a four-byte big-endian enum value. */
    public static BedrockPacket createPlayStatus(int status) {
        ByteBuffer body = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
        body.putInt(status);
        return new BedrockPacket(PLAY_STATUS_PACKET_ID, 0, 0, body.array());
    }

    /** Builds an empty ResourcePacksInfo packet for a server that has no custom packs. */
    public static BedrockPacket createResourcePacksInfo() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0); // texture packs not required
        body.write(0); // no addons
        body.write(0); // no scripts
        body.write(0); // do not force-disable vibrant visuals
        body.writeBytes(new byte[16]); // empty world-template UUID
        writeString(body, "");
        writeUnsignedVarInt(body, 0); // no texture packs
        return new BedrockPacket(RESOURCE_PACKS_INFO_PACKET_ID, 0, 0, body.toByteArray());
    }

    /** Builds an empty ResourcePackStack packet. */
    public static BedrockPacket createResourcePackStack(String baseGameVersion) {
        Objects.requireNonNull(baseGameVersion, "baseGameVersion");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0); // texture packs not required
        writeUnsignedVarInt(body, 0); // no texture packs in the stack
        writeString(body, baseGameVersion);
        body.writeBytes(new byte[Integer.BYTES]); // no experiments (uint32 count)
        body.write(0); // experiments were not previously toggled
        body.write(0); // not an editor world
        return new BedrockPacket(RESOURCE_PACK_STACK_PACKET_ID, 0, 0, body.toByteArray());
    }

    /** Parses the response enum and display string from ResourcePackClientResponse. */
    public static ResourcePackClientResponseData parseResourcePackClientResponse(BedrockPacket packet) {
        Objects.requireNonNull(packet, "packet");
        if (packet.packetId() != RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID) {
            throw new IllegalArgumentException("Expected ResourcePackClientResponse (0x08)");
        }
        byte[] payload = packet.payload();
        int[] offset = {0};
        // Protocol 2225 serializes ResourcePackResponse as a length-prefixed
        // string, not as a numeric enum followed by a string.
        String name = readString(payload, offset);
        int response = switch (name) {
            case "Cancel" -> 1;
            case "Downloading" -> 2;
            case "DownloadingFinished" -> 3;
            case "ResourcePackStackFinished" -> 4;
            default -> throw new IllegalArgumentException("Unknown resource-pack response: " + name);
        };
        List<String> downloadingPacks = new ArrayList<>();
        if (response == 2) {
            long count = readUnsignedVarInt(payload, offset, payload.length);
            if (count > 128) throw new IllegalArgumentException("Too many requested resource packs");
            for (int i = 0; i < count; i++) downloadingPacks.add(readString(payload, offset));
        }
        if (offset[0] != payload.length) {
            throw new IllegalArgumentException("Trailing bytes in ResourcePackClientResponse");
        }
        return new ResourcePackClientResponseData(response, name, List.copyOf(downloadingPacks));
    }

    /**
     * Exact-sized batch output that can transfer its backing array to the encoder.
     * The buffer never escapes before encoding completes, so an exact-sized array
     * is safe to return without ByteArrayOutputStream's defensive final copy.
     */
    private static final class BatchBuffer extends ByteArrayOutputStream {
        private BatchBuffer(int capacity) {
            super(capacity);
        }

        private byte[] toOwnedByteArray() {
            return count == buf.length ? buf : java.util.Arrays.copyOf(buf, count);
        }
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeUnsignedVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static String readString(byte[] input, int[] offset) {
        long length = readUnsignedVarInt(input, offset, input.length);
        if (length > 1_048_576 || offset[0] > input.length - (int) length) {
            throw new IllegalArgumentException("Invalid Bedrock string length");
        }
        String value = new String(input, offset[0], (int) length,
                java.nio.charset.StandardCharsets.UTF_8);
        offset[0] += (int) length;
        return value;
    }

    public record ResourcePackClientResponseData(int response, String name, List<String> downloadingPacks) {
    }

    /** Builds NetworkSettings (packet 0x8F), to be sent before compression framing is enabled. */
    public static BedrockPacket createNetworkSettings(int compressionThreshold, int compressionAlgorithm) {
        if (compressionThreshold < 1 || compressionThreshold > 0xffff) {
            throw new IllegalArgumentException("Compression threshold must be 1..65535");
        }
        if (compressionAlgorithm != COMPRESSION_ZLIB && compressionAlgorithm != COMPRESSION_NONE) {
            throw new IllegalArgumentException("This build supports only Zlib and no compression");
        }

        ByteBuffer body = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        body.putShort((short) compressionThreshold);
        body.putShort((short) compressionAlgorithm);
        body.put((byte) 0); // client throttle disabled
        body.put((byte) 0); // throttle threshold
        body.putFloat(0.0f); // throttle scalar
        return new BedrockPacket(NETWORK_SETTINGS_PACKET_ID, 0, 0, body.array());
    }

    private static byte[] decodeCompressionFrame(byte[] message, int maxBatchBytes) {
        if (message.length < 2) throw new IllegalArgumentException("Missing Bedrock compression frame");
        int algorithm = Byte.toUnsignedInt(message[0]);
        int payloadLength = message.length - 1;

        return switch (algorithm) {
            case COMPRESSION_HEADER_NONE -> {
                if (payloadLength > maxBatchBytes) {
                    throw new IllegalArgumentException("Uncompressed Bedrock batch exceeds size limit");
                }
                yield java.util.Arrays.copyOfRange(message, 1, message.length);
            }
            case COMPRESSION_HEADER_ZLIB -> inflateRaw(message, 1, payloadLength, maxBatchBytes);
            case COMPRESSION_HEADER_SNAPPY ->
                    throw new UnsupportedOperationException("Snappy compression is not enabled in this build");
            default -> throw new IllegalArgumentException("Unknown Bedrock compression header: " + algorithm);
        };
    }

    private static byte[] withCompressionHeader(int header, byte[] payload) {
        byte[] result = new byte[payload.length + 1];
        result[0] = (byte) header;
        System.arraycopy(payload, 0, result, 1, payload.length);
        return result;
    }

    private static byte[] deflateRaw(byte[] input) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count == 0 && !deflater.finished()) {
                    throw new IllegalStateException("Raw DEFLATE encoder made no progress");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflateRaw(byte[] input, int offset, int length, int maxOutputBytes) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(input, offset, length);
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(input.length * 2, 8192));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count;
                try {
                    count = inflater.inflate(buffer);
                } catch (DataFormatException exception) {
                    throw new IllegalArgumentException("Invalid raw DEFLATE Bedrock batch", exception);
                }
                if (count > 0) {
                    if (output.size() + count > maxOutputBytes) {
                        throw new IllegalArgumentException("Decompressed Bedrock batch exceeds size limit");
                    }
                    output.write(buffer, 0, count);
                } else if (inflater.needsDictionary()) {
                    throw new IllegalArgumentException("Bedrock DEFLATE batch unexpectedly requires a dictionary");
                } else if (inflater.needsInput()) {
                    throw new IllegalArgumentException("Truncated raw DEFLATE Bedrock batch");
                } else {
                    throw new IllegalArgumentException("Raw DEFLATE decoder made no progress");
                }
            }
            if (inflater.getRemaining() != 0) {
                throw new IllegalArgumentException("Trailing bytes after compressed Bedrock batch");
            }
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static long readUnsignedVarInt(byte[] input, int[] offset, int limit) {
        long value = 0;
        for (int index = 0; index < 5; index++) {
            if (offset[0] >= limit) throw new IllegalArgumentException("Truncated Bedrock VarInt");
            int current = Byte.toUnsignedInt(input[offset[0]++]);
            if (index == 4 && (current & 0xf0) != 0) {
                throw new IllegalArgumentException("Bedrock VarInt exceeds 32 bits");
            }
            value |= (long) (current & 0x7f) << (index * 7);
            if ((current & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("Bedrock VarInt exceeds five bytes");
    }

    private static int unsignedVarIntSize(long value) {
        if (value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException("Bedrock VarInt must be an unsigned 32-bit value");
        }
        int size = 1;
        while ((value >>>= 7) != 0) size++;
        return size;
    }

    private static byte[] writeUnsignedVarInt(long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(5);
        writeUnsignedVarInt(output, value);
        return output.toByteArray();
    }

    private static void writeUnsignedVarInt(ByteArrayOutputStream output, long value) {
        if (value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException("Bedrock VarInt must be an unsigned 32-bit value");
        }
        do {
            int current = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) current |= 0x80;
            output.write(current);
        } while (value != 0);
    }
}
