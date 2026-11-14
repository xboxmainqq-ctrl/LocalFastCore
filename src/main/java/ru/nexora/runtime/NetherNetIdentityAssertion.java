package ru.nexora.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Parses the public structure of a NetherNet identity assertion.
 *
 * <p>This class only validates encoding and shape. It does not validate the
 * GameServerToken issuer/signature, trust claims, or verify the detached JWS.</p>
 */
public record NetherNetIdentityAssertion(
        String identityProviderDomain,
        String identityProviderProtocol,
        String token,
        String detachedFingerprintsJws,
        String jwsAlgorithm
) {
    public static final int DEFAULT_MAX_TOKEN_CHARS = 12 * 1024;
    public static final int DEFAULT_MAX_JWS_CHARS = 4 * 1024;

    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8)
                    .maxStringLength(16 * 1024)
                    .maxNumberLength(64)
                    .build())
            .build();

    public NetherNetIdentityAssertion {
        Objects.requireNonNull(identityProviderDomain, "identityProviderDomain");
        Objects.requireNonNull(identityProviderProtocol, "identityProviderProtocol");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(detachedFingerprintsJws, "detachedFingerprintsJws");
        Objects.requireNonNull(jwsAlgorithm, "jwsAlgorithm");
    }

    public static NetherNetIdentityAssertion parse(NetherNetSdpDocument document) {
        return parse(document, DEFAULT_MAX_TOKEN_CHARS, DEFAULT_MAX_JWS_CHARS);
    }

    public static NetherNetIdentityAssertion parse(NetherNetSdpDocument document,
                                                     int maxTokenChars, int maxJwsChars) {
        Objects.requireNonNull(document, "document");
        if (maxTokenChars < 1 || maxJwsChars < 1) {
            throw new IllegalArgumentException("Token and JWS limits must be positive");
        }

        Map<String, String> envelope = readStringObject(document.identityEnvelopeJson(),
                "identity envelope", "assertion");
        String domain;
        String protocol;
        try {
            Map<String, String> idp = readStringObject(readRawObjectField(
                    document.identityEnvelopeJson(), "idp"), "identity provider", "domain", "protocol");
            domain = required(idp, "domain");
            protocol = required(idp, "protocol");
        } catch (IOException e) {
            throw invalid("Invalid identity provider object", e);
        }

        if (domain.isBlank() || domain.length() > 253 || containsControl(domain)) {
            throw new IllegalArgumentException("Identity provider domain is invalid");
        }
        if (!"default".equals(protocol)) {
            throw new IllegalArgumentException("Unsupported identity provider protocol");
        }

        String assertionJson = required(envelope, "assertion");
        Map<String, String> assertion = readStringObject(assertionJson,
                "identity assertion", "token", "fingerprints");
        String token = required(assertion, "token");
        String jws = required(assertion, "fingerprints");
        if (token.length() > maxTokenChars || jws.length() > maxJwsChars) {
            throw new IllegalArgumentException("Identity token or JWS exceeds configured limit");
        }
        validateJwtShape(token);
        String algorithm = validateDetachedJwsShape(jws);
        return new NetherNetIdentityAssertion(domain, protocol, token, jws, algorithm);
    }

    private static Map<String, String> readStringObject(String json, String label,
                                                         String... requiredFields) {
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException(label + " must be a JSON object");
            }
            Map<String, String> values = readFields(parser, requiredFields);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException(label + " contains trailing JSON data");
            }
            return values;
        } catch (IOException e) {
            throw invalid("Malformed " + label + " JSON", e);
        }
    }

    private static String readRawObjectField(String json, String field) throws IOException {
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Identity envelope must be a JSON object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if (field.equals(name)) {
                    if (value != JsonToken.START_OBJECT) {
                        throw new IllegalArgumentException("Identity provider must be a JSON object");
                    }
                    return parser.readValueAsTree().toString();
                }
                parser.skipChildren();
            }
            throw new IllegalArgumentException("Identity envelope is missing " + field);
        }
    }

    private static Map<String, String> readFields(JsonParser parser, String... wanted)
            throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            boolean capture = false;
            for (String key : wanted) {
                if (key.equals(name)) {
                    capture = true;
                    break;
                }
            }
            if (capture) {
                if (value != JsonToken.VALUE_STRING) {
                    throw new IllegalArgumentException(name + " must be a JSON string");
                }
                values.put(name, parser.getText());
            } else {
                parser.skipChildren();
            }
        }
        return values;
    }

    private static void validateJwtShape(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new IllegalArgumentException("GameServerToken must use three-part compact JWT form");
        }
        decodeBase64Url(parts[0], "JWT header");
        decodeBase64Url(parts[1], "JWT claims");
        decodeBase64Url(parts[2], "JWT signature");
    }

    private static String validateDetachedJwsShape(String jws) {
        String[] parts = jws.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || !parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new IllegalArgumentException("Fingerprints must be a detached compact JWS");
        }
        byte[] headerBytes = decodeBase64Url(parts[0], "JWS header");
        decodeBase64Url(parts[2], "JWS signature");
        String headerJson = new String(headerBytes, java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> header = readStringObject(headerJson, "JWS protected header", "alg");
        String algorithm = required(header, "alg");
        if ("none".equalsIgnoreCase(algorithm) || algorithm.length() > 32) {
            throw new IllegalArgumentException("JWS algorithm is not permitted");
        }
        return algorithm;
    }

    private static byte[] decodeBase64Url(String value, String label) {
        if (value.indexOf('=') >= 0 || value.length() > 16 * 1024) {
            throw new IllegalArgumentException(label + " is not unpadded Base64URL");
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw invalid(label + " is not valid Base64URL", e);
        }
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing or empty " + key);
        }
        return value;
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static IllegalArgumentException invalid(String message, Exception cause) {
        return new IllegalArgumentException(message, cause);
    }
}
