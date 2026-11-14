package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Codec for the Bedrock LoginPacket JWT block used by protocol 2225.
 *
 * <p>The payload is a big-endian protocol version followed by a varuint32
 * block length. The block contains little-endian int32 length-prefixed UTF-8
 * authentication JSON and client-data JWT strings.</p>
 */
public final class BedrockLoginPacketCodec {
    public static final int MAX_CONNECTION_REQUEST_BYTES = 1_048_576;
    public static final int MAX_CLIENT_DATA_JWT_BYTES = 1_048_576;
    public static final int MAX_JWT_BLOCK_BYTES =
            BedrockPacketBatchCodec.MAX_PACKET_BYTES - 32;

    private BedrockLoginPacketCodec() {
    }

    public static BedrockLoginPacket decodePayload(ByteBuffer payload) {
        Objects.requireNonNull(payload, "payload");
        int protocolVersion = BedrockBinary.readBigEndianInt32(payload);
        int blockLength = BedrockBinary.readVarUInt32(payload);
        if (blockLength < 2 * Integer.BYTES || blockLength > MAX_JWT_BLOCK_BYTES
                || blockLength != payload.remaining()) {
            throw new IllegalArgumentException("Bedrock Login JWT block length is invalid");
        }

        ByteBuffer block = payload.slice().order(ByteOrder.LITTLE_ENDIAN);
        int authLength = readLength(block, MAX_CONNECTION_REQUEST_BYTES, "authentication payload");
        String connectionRequest = readUtf8(block, authLength, "authentication payload");
        int clientDataLength = readLength(block, MAX_CLIENT_DATA_JWT_BYTES, "client-data JWT");
        String clientDataJwt = readUtf8(block, clientDataLength, "client-data JWT");
        if (block.hasRemaining()) {
            throw new IllegalArgumentException("Trailing bytes in Bedrock Login JWT block");
        }
        payload.position(payload.position() + blockLength);
        if (payload.hasRemaining()) {
            throw new IllegalArgumentException("Trailing bytes after Bedrock LoginPacket payload");
        }
        return new BedrockLoginPacket(protocolVersion, connectionRequest, clientDataJwt);
    }

    public static ByteBuffer encodePayload(BedrockLoginPacket packet) {
        Objects.requireNonNull(packet, "packet");
        byte[] auth = packet.connectionRequest().getBytes(StandardCharsets.UTF_8);
        byte[] clientData = packet.clientDataJwt().getBytes(StandardCharsets.UTF_8);
        if (auth.length == 0 || auth.length > MAX_CONNECTION_REQUEST_BYTES) {
            throw new IllegalArgumentException("Authentication payload exceeds the configured byte limit");
        }
        if (clientData.length > MAX_CLIENT_DATA_JWT_BYTES) {
            throw new IllegalArgumentException("Client-data JWT exceeds the configured byte limit");
        }
        int blockLength = 2 * Integer.BYTES + auth.length + clientData.length;
        if (blockLength > MAX_JWT_BLOCK_BYTES) {
            throw new IllegalArgumentException("Bedrock Login JWT block exceeds the configured byte limit");
        }

        ByteBuffer encoded = ByteBuffer.allocate(
                Integer.BYTES + varUInt32Size(blockLength) + blockLength);
        BedrockBinary.writeBigEndianInt32(encoded, packet.protocolVersion());
        BedrockBinary.writeVarUInt32(encoded, blockLength);
        encoded.order(ByteOrder.LITTLE_ENDIAN);
        encoded.putInt(auth.length).put(auth);
        encoded.putInt(clientData.length).put(clientData);
        encoded.flip();
        return encoded.asReadOnlyBuffer();
    }

    private static int readLength(ByteBuffer input, int maxBytes, String label) {
        if (input.remaining() < Integer.BYTES) {
            throw new IllegalArgumentException("Truncated " + label + " length");
        }
        int length = input.getInt();
        if (length < 0 || length > maxBytes || length > input.remaining()) {
            throw new IllegalArgumentException(label + " length is invalid");
        }
        return length;
    }

    private static String readUtf8(ByteBuffer input, int length, String label) {
        ByteBuffer bytes = input.slice();
        bytes.limit(length);
        input.position(input.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException(label + " is not valid UTF-8", invalid);
        }
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
