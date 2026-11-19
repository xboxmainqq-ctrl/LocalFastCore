package ru.nexora.network.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Parses the Bedrock Login packet and its length-prefixed connection request. */
public final class BedrockLoginPacketParser {
    public static final int LOGIN_PACKET_ID = 1;
    public static final int MAX_CONNECTION_REQUEST_BYTES = 2 * 1024 * 1024;
    public static final int MAX_CERTIFICATE_CHAIN_ENTRIES = 32;
    public static final int MAX_CERTIFICATE_TOKEN_CHARS = 16_384;
    public static final int MAX_AUTH_TOKEN_CHARS = 65_536;
    public static final int MAX_CLIENT_DATA_TOKEN_CHARS = 1_048_576;
    private static final int MAX_JSON_NESTING_DEPTH = 64;

    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_JSON_NESTING_DEPTH)
                    .maxStringLength(MAX_CLIENT_DATA_TOKEN_CHARS)
                    .maxNumberLength(128)
                    .build())
            .build());

    private BedrockLoginPacketParser() {
    }

    public static LoginRequest parse(BedrockPacket packet) {
        if (packet.packetId() != LOGIN_PACKET_ID) {
            throw new IllegalArgumentException("Expected Bedrock Login packet (0x01)");
        }
        byte[] payload = packet.payload();
        if (payload.length < 5) throw new IllegalArgumentException("Truncated Bedrock Login packet");

        int clientProtocol = ByteBuffer.wrap(payload, 0, Integer.BYTES)
                .order(ByteOrder.BIG_ENDIAN).getInt();
        int[] offset = {Integer.BYTES};
        long requestLengthLong = readUnsignedVarInt(payload, offset);
        if (requestLengthLong < 1 || requestLengthLong > MAX_CONNECTION_REQUEST_BYTES) {
            throw new IllegalArgumentException("Invalid Login connection-request length");
        }
        int requestLength = (int) requestLengthLong;
        if (offset[0] > payload.length - requestLength) {
            throw new IllegalArgumentException("Truncated Login connection request");
        }
        if (offset[0] + requestLength != payload.length) {
            throw new IllegalArgumentException("Trailing bytes after Login connection request");
        }

        byte[] connectionRequest = new byte[requestLength];
        System.arraycopy(payload, offset[0], connectionRequest, 0, requestLength);
        return parseConnectionRequest(clientProtocol, connectionRequest);
    }

    private static LoginRequest parseConnectionRequest(int clientProtocol, byte[] data) {
        int[] offset = {0};
        int chainLength = readLittleEndianInt(data, offset);
        if (chainLength < 1 || chainLength > MAX_CONNECTION_REQUEST_BYTES
                || offset[0] > data.length - chainLength) {
            throw new IllegalArgumentException("Invalid Login certificate-chain JSON length");
        }
        String chainJson = new String(data, offset[0], chainLength, StandardCharsets.UTF_8);
        offset[0] += chainLength;

        int rawTokenLength = readLittleEndianInt(data, offset);
        if (rawTokenLength < 1 || rawTokenLength > MAX_CONNECTION_REQUEST_BYTES
                || offset[0] > data.length - rawTokenLength) {
            throw new IllegalArgumentException("Invalid Login client-data token length");
        }
        String rawClientDataToken = new String(
                data, offset[0], rawTokenLength, StandardCharsets.UTF_8);
        offset[0] += rawTokenLength;
        if (offset[0] != data.length) {
            throw new IllegalArgumentException("Trailing bytes in Login connection request");
        }

        try {
            JsonNode root = MAPPER.readTree(chainJson);
            int authenticationType = root.path("AuthenticationType").asInt(0);
            String multiplayerToken = root.path("Token").asText("");
            if (multiplayerToken.isBlank()) {
                throw new IllegalArgumentException("Login request is missing its multiplayer token");
            }
            if (multiplayerToken.length() > MAX_AUTH_TOKEN_CHARS) {
                throw new IllegalArgumentException("Login multiplayer token exceeds size limit");
            }
            if (rawClientDataToken.length() > MAX_CLIENT_DATA_TOKEN_CHARS) {
                throw new IllegalArgumentException("Login client-data token exceeds size limit");
            }

            List<String> chain = readCertificateChain(root);
            return new LoginRequest(clientProtocol, authenticationType,
                    multiplayerToken, rawClientDataToken, chain);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Malformed Login certificate-chain JSON", exception);
        }
    }

    private static List<String> readCertificateChain(JsonNode root) {
        JsonNode certificate = root.get("Certificate");
        JsonNode chainNode = null;
        if (certificate != null && certificate.isTextual() && !certificate.asText().isBlank()) {
            try {
                chainNode = MAPPER.readTree(certificate.asText()).get("chain");
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException("Malformed Login Certificate JSON", exception);
            }
        } else if (certificate != null && certificate.isObject()) {
            chainNode = certificate.get("chain");
        }
        if (chainNode == null) chainNode = root.get("chain");
        if (chainNode == null || !chainNode.isArray()) return List.of();

        if (chainNode.size() > MAX_CERTIFICATE_CHAIN_ENTRIES) {
            throw new IllegalArgumentException("Login certificate chain contains too many entries");
        }
        List<String> result = new ArrayList<>(chainNode.size());
        for (JsonNode token : chainNode) {
            if (!token.isTextual() || token.asText().isBlank()
                    || token.asText().length() > MAX_CERTIFICATE_TOKEN_CHARS) {
                throw new IllegalArgumentException("Invalid or oversized token in Login certificate chain");
            }
            result.add(token.asText());
        }
        return List.copyOf(result);
    }

    private static int readLittleEndianInt(byte[] data, int[] offset) {
        if (offset[0] > data.length - Integer.BYTES) {
            throw new IllegalArgumentException("Truncated Login connection request");
        }
        int value = ByteBuffer.wrap(data, offset[0], Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).getInt();
        offset[0] += Integer.BYTES;
        return value;
    }

    private static long readUnsignedVarInt(byte[] input, int[] offset) {
        long value = 0;
        for (int index = 0; index < 5; index++) {
            if (offset[0] >= input.length) throw new IllegalArgumentException("Truncated Login ByteSlice length");
            int current = Byte.toUnsignedInt(input[offset[0]++]);
            if (index == 4 && (current & 0xf0) != 0) {
                throw new IllegalArgumentException("Login ByteSlice length exceeds 32 bits");
            }
            value |= (long) (current & 0x7f) << (index * 7);
            if ((current & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("Login ByteSlice length exceeds five bytes");
    }

    public record LoginRequest(
            int clientProtocol,
            int authenticationType,
            String multiplayerToken,
            String rawClientDataToken,
            List<String> certificateChain
    ) {
        public LoginRequest {
            if (authenticationType < 0 || authenticationType > 255) {
                throw new IllegalArgumentException("Invalid Login authentication type");
            }
            if (multiplayerToken == null || multiplayerToken.isBlank()) {
                throw new IllegalArgumentException("multiplayerToken must not be blank");
            }
            if (rawClientDataToken == null || rawClientDataToken.isBlank()) {
                throw new IllegalArgumentException("rawClientDataToken must not be blank");
            }
            certificateChain = List.copyOf(certificateChain);
        }
    }
}
