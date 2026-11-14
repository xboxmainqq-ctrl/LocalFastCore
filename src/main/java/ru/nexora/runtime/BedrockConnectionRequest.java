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
 * <p>Supports both the legacy {@code chain}/{@code clientData} object and the
 * newer {@code AuthenticationType}/{@code Token}/{@code Certificate} envelope.
 * This class deliberately does not trust or authenticate either representation;
 * a separate verifier must validate signatures, issuers, claims, and key binding.</p>
 */
public record BedrockConnectionRequest(
        Format format,
        List<String> chain,
        String clientDataJwt,
        Integer authenticationType,
        String token,
        String certificate
) {
    public enum Format {
        LEGACY_CHAIN,
        AUTHENTICATION_ENVELOPE
    }

    public BedrockConnectionRequest(List<String> chain, String clientDataJwt) {
        this(Format.LEGACY_CHAIN, chain, clientDataJwt, null, null, null);
    }
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
        Objects.requireNonNull(format, "format");
        chain = List.copyOf(Objects.requireNonNull(chain, "chain"));
        if (format == Format.LEGACY_CHAIN) {
            Objects.requireNonNull(clientDataJwt, "clientDataJwt");
            if (authenticationType != null || token != null || certificate != null) {
                throw new IllegalArgumentException("Legacy connection request contains modern envelope fields");
            }
            if (chain.isEmpty() || chain.size() > MAX_CHAIN_TOKENS) {
                throw new IllegalArgumentException("Bedrock certificate chain must contain 1 to 3 JWTs");
            }
            for (String jwt : chain) {
                requireCompactJwt(jwt, "certificate-chain JWT");
            }
            requireCompactJwt(clientDataJwt, "client-data JWT");
        } else {
            if (!chain.isEmpty() || clientDataJwt != null
                    || authenticationType == null || token == null || certificate == null) {
                throw new IllegalArgumentException("Modern authentication envelope is incomplete or mixed");
            }
            if (authenticationType < 0 || authenticationType > 255
                    || token.isBlank() || token.length() > MAX_JWT_CHARS
                    || certificate.isBlank() || certificate.length() > MAX_JWT_CHARS) {
                throw new IllegalArgumentException("Modern authentication envelope fields are invalid");
            }
        }
    }

    public boolean isLegacyChain() {
        return format == Format.LEGACY_CHAIN;
    }

    public boolean isAuthenticationEnvelope() {
        return format == Format.AUTHENTICATION_ENVELOPE;
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
        Integer authenticationType = null;
        String tokenValue = null;
        String certificateValue = null;
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
                if (++topLevelFieldCount > MAX_TOP_LEVEL_FIELDS) {
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
                } else if ("AuthenticationType".equals(field)
                        || "authenticationType".equals(field)) {
                    if (value != JsonToken.VALUE_NUMBER_INT || authenticationType != null) {
                        throw new IllegalArgumentException("AuthenticationType must be a unique integer");
                    }
                    authenticationType = parser.getIntValue();
                } else if ("Token".equals(field) || "token".equals(field)) {
                    if (value != JsonToken.VALUE_STRING || tokenValue != null) {
                        throw new IllegalArgumentException("Token must be a unique string");
                    }
                    tokenValue = parser.getText();
                } else if ("Certificate".equals(field) || "certificate".equals(field)) {
                    if (value != JsonToken.VALUE_STRING || certificateValue != null) {
                        throw new IllegalArgumentException("Certificate must be a unique string");
                    }
                    certificateValue = parser.getText();
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

        boolean hasLegacy = chain != null || clientData != null;
        boolean hasModern = authenticationType != null || tokenValue != null || certificateValue != null;
        if (hasLegacy && hasModern) {
            throw new IllegalArgumentException("Connection request mixes legacy and modern authentication formats");
        }
        if (hasModern) {
            if (authenticationType == null || tokenValue == null || certificateValue == null) {
                throw new IllegalArgumentException("Modern connection request requires AuthenticationType, Token, and Certificate");
            }
            return new BedrockConnectionRequest(Format.AUTHENTICATION_ENVELOPE,
                    List.of(), null, authenticationType, tokenValue, certificateValue);
        }
        if (chain == null || clientData == null) {
            throw new IllegalArgumentException("Connection request must contain legacy chain/clientData or modern authentication envelope");
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
