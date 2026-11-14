package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Codec for the Bedrock LoginPacket payload used by protocol 2225.
 *
 * <p>The packet payload consists of a big-endian protocol version followed by
 * a varuint32-length-prefixed UTF-8 connection request. The outer Bedrock
 * packet identifier and batch framing are handled by separate layers.</p>
 */
public final class BedrockLoginPacketCodec {
    /** Defensive cap for the encoded JWT/JSON connection request. */
    public static final int MAX_CONNECTION_REQUEST_BYTES = 1_048_576;

    private BedrockLoginPacketCodec() {
    }

    public static BedrockLoginPacket decodePayload(ByteBuffer payload) {
        Objects.requireNonNull(payload, "payload");
        int protocolVersion = BedrockBinary.readBigEndianInt32(payload);
        String connectionRequest = BedrockBinary.readString(
                payload, MAX_CONNECTION_REQUEST_BYTES
        );
        if (payload.hasRemaining()) {
            throw new IllegalArgumentException("Trailing bytes after Bedrock LoginPacket payload");
        }
        return new BedrockLoginPacket(protocolVersion, connectionRequest);
    }

    public static ByteBuffer encodePayload(BedrockLoginPacket packet) {
        Objects.requireNonNull(packet, "packet");
        byte[] request = packet.connectionRequest().getBytes(StandardCharsets.UTF_8);
        if (request.length > MAX_CONNECTION_REQUEST_BYTES) {
            throw new IllegalArgumentException("Connection request exceeds the configured byte limit");
        }

        ByteBuffer encoded = ByteBuffer.allocate(
                Integer.BYTES + varUInt32Size(request.length) + request.length
        );
        BedrockBinary.writeBigEndianInt32(encoded, packet.protocolVersion());
        BedrockBinary.writeVarUInt32(encoded, request.length);
        encoded.put(request);
        encoded.flip();
        return encoded.asReadOnlyBuffer();
    }

    private static int varUInt32Size(int value) {
        int size = 1;
        while ((value & 0xFFFFFF80) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}
