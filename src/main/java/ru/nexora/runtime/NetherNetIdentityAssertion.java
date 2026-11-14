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
 * <p>This class validates bounded JSON/JWT/JWS structure and expected token
 * issuer/audience fields. It does not verify the GameServerToken signature,
 * expiry, or detached fingerprint signature.</p>
 */
public record NetherNetIdentityAssertion(
        String identityProviderDomain,
        String identityProviderProtocol,
        String token,
        String jwtKeyId,
        String jwtAlgorithm,
        String clientPublicKeySpkiBase64,
        String issuer,
        String audience,
        long expiresAtEpochSeconds,
        Long notBeforeEpochSeconds,
        String xuid,
        String displayName,
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
        Objects.requireNonNull(jwtKeyId, "jwtKeyId");
        Objects.requireNonNull(jwtAlgorithm, "jwtAlgorithm");
        Objects.requireNonNull(clientPublicKeySpkiBase64, "clientPublicKeySpkiBase64");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(audience, "audience");
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
        Map<String, String> idp = readObjectField(document.identityEnvelopeJson(),
                "idp", "domain", "protocol");
        String domain = required(idp, "domain");
        String protocol = required(idp, "protocol");

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
        JwtShape jwt = validateJwtShape(token);
        String algorithm = validateDetachedJwsShape(jws);
        return new NetherNetIdentityAssertion(domain, protocol, token, jwt.keyId(),
                jwt.algorithm(), jwt.cpk(), jwt.issuer(), jwt.audience(), jwt.exp(),
                jwt.nbf(), jwt.xuid(), jwt.displayName(), jws, algorithm);
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

    private static Map<String, String> readObjectField(String json, String objectField,
                                                         String... wanted) {
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Identity envelope must be a JSON object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if (objectField.equals(name)) {
                    if (value != JsonToken.START_OBJECT) {
                        throw new IllegalArgumentException(objectField + " must be a JSON object");
                    }
                    return readFields(parser, wanted);
                }
                parser.skipChildren();
            }
            throw new IllegalArgumentException("Identity envelope is missing " + objectField);
        } catch (IOException e) {
            throw invalid("Malformed identity provider JSON", e);
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

    private static JwtShape validateJwtShape(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new IllegalArgumentException("GameServerToken must use three-part compact JWT form");
        }
        byte[] headerBytes = decodeBase64Url(parts[0], "JWT header");
        byte[] claimsBytes = decodeBase64Url(parts[1], "JWT claims");
        byte[] signatureBytes = decodeBase64Url(parts[2], "JWT signature");
        if (signatureBytes.length == 0) {
            throw new IllegalArgumentException("JWT signature must not be empty");
        }
        String headerJson = decodeUtf8(headerBytes, "JWT header");
        String claimsJson = decodeUtf8(claimsBytes, "JWT claims");
        Map<String, String> header = readStringObject(headerJson,
                "JWT protected header", "alg", "kid");
        String algorithm = required(header, "alg");
        String keyId = required(header, "kid");
        if (!"RS256".equals(algorithm)) {
            throw new IllegalArgumentException("GameServerToken must use RS256");
        }
        Map<String, String> claims = readStringObject(claimsJson, "JWT claims",
                "iss", "aud", "cpk", "xid", "xname");
        String issuer = required(claims, "iss");
        String audience = required(claims, "aud");
        String cpk = required(claims, "cpk");
        if (!"https://authorization.franchise.minecraft-services.net/".equals(issuer)) {
            throw new IllegalArgumentException("GameServerToken issuer is not trusted");
        }
        if (!"api://auth-minecraft-services/multiplayer".equals(audience)) {
            throw new IllegalArgumentException("GameServerToken audience is not multiplayer");
        }
        try {
            byte[] spki = Base64.getDecoder().decode(cpk);
            if (spki.length < 64 || spki.length > 1024) {
                throw new IllegalArgumentException("Client public key SPKI length is invalid");
            }
        } catch (IllegalArgumentException invalidKey) {
            throw new IllegalArgumentException("cpk must be a Base64 DER SubjectPublicKeyInfo", invalidKey);
        }
        long exp = readLongClaim(claimsJson, "exp", true, 0L);
        Long nbf = readLongClaim(claimsJson, "nbf", false, null);
        return new JwtShape(keyId, algorithm, cpk, issuer, audience, exp, nbf,
                claims.get("xid"), claims.get("xname"));
    }

    private static Long readLongClaim(String json, String name, boolean required, Long fallback) {
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("JWT claims must be a JSON object");
            }
            Long found = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (name.equals(field)) {
                    if (value != JsonToken.VALUE_NUMBER_INT) {
                        throw new IllegalArgumentException("JWT " + name + " claim must be an integer");
                    }
                    found = parser.getLongValue();
                } else {
                    parser.skipChildren();
                }
            }
            if (required && found == null) {
                throw new IllegalArgumentException("JWT claims are missing " + name);
            }
            return found == null ? fallback : found;
        } catch (IOException e) {
            throw invalid("Malformed JWT numeric claim " + name, e);
        }
    }

    private record JwtShape(String keyId, String algorithm, String cpk, String issuer,
                            String audience, long exp, Long nbf,
                            String xuid, String displayName) {
    }

    private static String validateDetachedJwsShape(String jws) {
        String[] parts = jws.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || !parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new IllegalArgumentException("Fingerprints must be a detached compact JWS");
        }
        byte[] headerBytes = decodeBase64Url(parts[0], "JWS header");
        byte[] signatureBytes = decodeBase64Url(parts[2], "JWS signature");
        if (signatureBytes.length == 0) {
            throw new IllegalArgumentException("Detached JWS signature must not be empty");
        }
        String headerJson = decodeUtf8(headerBytes, "JWS protected header");
        Map<String, String> header = readStringObject(headerJson, "JWS protected header", "alg");
        String algorithm = required(header, "alg");
        if (!"ES384".equals(algorithm)) {
            throw new IllegalArgumentException("NetherNet client fingerprint JWS must use ES384");
        }
        return algorithm;
    }

    private static String decodeUtf8(byte[] bytes, String label) {
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw invalid(label + " is not valid UTF-8", e);
        }
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
