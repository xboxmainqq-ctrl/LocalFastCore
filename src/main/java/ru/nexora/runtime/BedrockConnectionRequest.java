package ru.nexora.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Structurally decoded Bedrock Connection Request for protocol 2225.
 *
 * <p>This class deliberately does not trust or authenticate any JWT. It only
 * extracts the certificate-chain tokens and client-data token so a separate
 * cryptographic verifier can validate their signatures and claims.</p>
 */
public record BedrockConnectionRequest(List<String> chain, String clientDataJwt) {
    public static final int MAX_JSON_BYTES = BedrockLoginPacketCodec.MAX_CONNECTION_REQUEST_BYTES;
    public static final int MAX_CHAIN_TOKENS = 3;
    public static final int MAX_JWT_CHARS = 512 * 1024;
    private static final int MAX_TOP_LEVEL_FIELDS = 4096;

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(64)
                    .maxStringLength(MAX_JWT_CHARS)
                    .maxNumberLength(128)
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    public BedrockConnectionRequest {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(clientDataJwt, "clientDataJwt");
        chain = List.copyOf(chain);
        if (chain.isEmpty() || chain.size() > MAX_CHAIN_TOKENS) {
            throw new IllegalArgumentException("Bedrock certificate chain must contain 1 to 3 JWTs");
        }
        for (String token : chain) {
            requireCompactJwt(token, "certificate-chain JWT");
        }
        requireCompactJwt(clientDataJwt, "client-data JWT");
    }

    /**
     * Parses the JSON object containing the signed certificate chain and
     * client-data JWT. Unknown top-level fields are skipped for forward
     * compatibility, while duplicate required or unknown keys are rejected.
     */
    public static BedrockConnectionRequest parse(String json) {
        Objects.requireNonNull(json, "json");
        if (json.isBlank() || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("Bedrock connection request is empty or too large");
        }

        List<String> chain = null;
        String clientData = null;
        int topLevelFieldCount = 0;
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Bedrock connection request must be a JSON object");
            }
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token == null || token != JsonToken.FIELD_NAME) {
                    throw new IllegalArgumentException("Malformed Bedrock connection request object");
                }
                if (++tokenCount > MAX_JSON_TOKENS) {
                    throw new IllegalArgumentException("Bedrock connection request has too many top-level fields");
                }
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (value == null) {
                    throw new IllegalArgumentException("Missing value for connection request field");
                }
                if ("chain".equals(field)) {
                    if (value != JsonToken.START_ARRAY) {
                        throw new IllegalArgumentException("Connection request chain must be an array");
                    }
                    chain = readChain(parser);
                } else if ("clientData".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) {
                        throw new IllegalArgumentException("Connection request clientData must be a string");
                    }
                    clientData = parser.getText();
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("Trailing JSON content after connection request");
            }
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid Bedrock connection request JSON", failure);
        }

        if (chain == null || clientData == null) {
            throw new IllegalArgumentException("Connection request must contain chain and clientData");
        }
        return new BedrockConnectionRequest(chain, clientData);
    }

    private static List<String> readChain(JsonParser parser) throws Exception {
        List<String> values = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null || token != JsonToken.VALUE_STRING) {
                throw new IllegalArgumentException("Every certificate-chain entry must be a JWT string");
            }
            if (values.size() == MAX_CHAIN_TOKENS) {
                throw new IllegalArgumentException("Certificate chain contains too many JWTs");
            }
            values.add(parser.getText());
        }
        return values;
    }

    private static void requireCompactJwt(String jwt, String field) {
        if (jwt == null || jwt.isEmpty() || jwt.length() > MAX_JWT_CHARS) {
            throw new IllegalArgumentException(field + " is empty or too large");
        }
        int firstDot = jwt.indexOf('.');
        int secondDot = firstDot < 0 ? -1 : jwt.indexOf('.', firstDot + 1);
        if (firstDot <= 0 || secondDot <= firstDot + 1
                || secondDot == jwt.length() - 1 || jwt.indexOf('.', secondDot + 1) >= 0) {
            throw new IllegalArgumentException(field + " is not a compact three-part JWT");
        }
        if (!isBase64Url(jwt, 0, firstDot)
                || !isBase64Url(jwt, firstDot + 1, secondDot)
                || !isBase64Url(jwt, secondDot + 1, jwt.length())) {
            throw new IllegalArgumentException(field + " contains invalid compact JWT characters");
        }
    }

    private static boolean isBase64Url(String value, int start, int end) {
        if (start >= end) return false;
        for (int i = start; i < end; i++) {
            char c = value.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '=')) {
                return false;
            }
        }
        return true;
    }
}
