package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded codec for the uncompressed Bedrock packet-batch body.
 *
 * <p>Each packet is encoded as varuint32(packet-byte-length), followed by
 * varuint32(packet-header), followed by its payload. Compression negotiation
 * and the optional compression algorithm byte are deliberately handled by a
 * separate layer.</p>
 */
public final class BedrockPacketBatchCodec {
    public static final int DEFAULT_MAX_BATCH_BYTES = 8 * 1024 * 1024;
    public static final int DEFAULT_MAX_PACKET_BYTES = 2 * 1024 * 1024;
    public static final int DEFAULT_MAX_PACKETS = 1024;

    private BedrockPacketBatchCodec() {
    }

    public static List<BedrockGamePacket> decodeUncompressed(
            ByteBuffer batch,
            int maxBatchBytes,
            int maxPacketBytes,
            int maxPackets
    ) {
        Objects.requireNonNull(batch, "batch");
        validateLimits(maxBatchBytes, maxPacketBytes, maxPackets);
        int batchLength = batch.remaining();
        if (batchLength == 0 || batchLength > maxBatchBytes) {
            throw new IllegalArgumentException("Batch size is outside the allowed range");
        }

        List<BedrockGamePacket> packets = new ArrayList<>();
        while (batch.hasRemaining()) {
            if (packets.size() >= maxPackets) {
                throw new IllegalArgumentException("Batch contains too many packets");
            }

            int packetLength = BedrockBinary.readVarUInt32(batch);
            if (packetLength <= 0 || packetLength > maxPacketBytes) {
                throw new IllegalArgumentException("Packet length is outside the allowed range");
            }
            if (packetLength > batch.remaining()) {
                throw new IllegalArgumentException("Truncated packet in batch");
            }

            ByteBuffer packetBytes = batch.slice();
            packetBytes.limit(packetLength);
            int headerValue = BedrockBinary.readVarUInt32(packetBytes);
            BedrockPacketHeader header = BedrockPacketHeader.fromWireValue(headerValue);

            byte[] payload = new byte[packetBytes.remaining()];
            packetBytes.get(payload);
            packets.add(new BedrockGamePacket(header, payload));

            batch.position(batch.position() + packetLength);
        }
        return List.copyOf(packets);
    }

    public static List<BedrockGamePacket> decodeUncompressed(ByteBuffer batch) {
        return decodeUncompressed(
                batch,
                DEFAULT_MAX_BATCH_BYTES,
                DEFAULT_MAX_PACKET_BYTES,
                DEFAULT_MAX_PACKETS
        );
    }

    public static ByteBuffer encodeUncompressed(List<BedrockGamePacket> packets) {
        Objects.requireNonNull(packets, "packets");
        if (packets.isEmpty() || packets.size() > DEFAULT_MAX_PACKETS) {
            throw new IllegalArgumentException("Packet count is outside the allowed range");
        }

        long totalBytes = 0;
        for (BedrockGamePacket packet : packets) {
            Objects.requireNonNull(packet, "packet");
            int packetBytes = packet.encodedSize();
            if (packetBytes <= 0 || packetBytes > DEFAULT_MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("Packet size is outside the allowed range");
            }
            totalBytes += BedrockGamePacket.varUInt32Size(packetBytes) + (long) packetBytes;
            if (totalBytes > DEFAULT_MAX_BATCH_BYTES) {
                throw new IllegalArgumentException("Batch exceeds the configured byte limit");
            }
        }

        ByteBuffer output = ByteBuffer.allocate((int) totalBytes);
        for (BedrockGamePacket packet : packets) {
            int packetBytes = packet.encodedSize();
            BedrockBinary.writeVarUInt32(output, packetBytes);
            BedrockBinary.writeVarUInt32(output, packet.header().toWireValue());
            output.put(packet.payload());
        }
        output.flip();
        return output.asReadOnlyBuffer();
    }

    private static void validateLimits(int maxBatchBytes, int maxPacketBytes, int maxPackets) {
        if (maxBatchBytes <= 0 || maxPacketBytes <= 0 || maxPacketBytes > maxBatchBytes
                || maxPackets <= 0) {
            throw new IllegalArgumentException("Invalid packet batch limits");
        }
    }
}
