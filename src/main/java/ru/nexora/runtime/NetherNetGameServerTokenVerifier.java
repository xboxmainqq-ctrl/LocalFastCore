package ru.nexora.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Verifies the GameServerToken issuer signature and time claims, then decodes
 * the cpk public key for the detached fingerprint proof.
 */
public final class NetherNetGameServerTokenVerifier {
    public static final long DEFAULT_CLOCK_SKEW_SECONDS = 120L;
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(16 * 1024).maxNumberLength(64).build())
            .build();

    private final NetherNetSigningKeyResolver keyResolver;
    private final Clock clock;
    private final long clockSkewSeconds;

    public NetherNetGameServerTokenVerifier(NetherNetSigningKeyResolver keyResolver) {
        this(keyResolver, Clock.systemUTC(), DEFAULT_CLOCK_SKEW_SECONDS);
    }

    public NetherNetGameServerTokenVerifier(NetherNetSigningKeyResolver keyResolver,
                                             Clock clock, long clockSkewSeconds) {
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (clockSkewSeconds < 0 || clockSkewSeconds > 600) {
            throw new IllegalArgumentException("Clock skew must be between 0 and 600 seconds");
        }
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public VerifiedClientIdentity verify(NetherNetIdentityAssertion assertion) throws Exception {
        Objects.requireNonNull(assertion, "assertion");
        VerifiedClientIdentity identity = verifyToken(assertion.token());
        String[] parts = assertion.token().split("\\.", -1);
        verifyParsedFieldsMatchSignedToken(parts, assertion);
        return identity;
    }

    /**
     * Verifies a raw modern Bedrock multiplayer token without requiring the
     * NetherNet SDP fingerprint assertion that carries the same token.
     * The returned cpk still requires a separate proof-of-possession handshake.
     */
    public VerifiedClientIdentity verifyToken(String token) throws Exception {
        Objects.requireNonNull(token, "token");
        if (token.isBlank() || token.length() > 16 * 1024) {
            throw new SecurityException("GameServerToken is empty or exceeds the configured limit");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new SecurityException("Malformed GameServerToken");
        }

        String headerJson = decodeUtf8(decodeUrl(parts[0]));
        String claimsJson = decodeUtf8(decodeUrl(parts[1]));
        Map<String, String> header = readStringFields(headerJson, "alg", "kid");
        Map<String, String> claims = readStringFields(claimsJson,
                "iss", "aud", "cpk", "xid", "xname");
        Long exp = readLong(claimsJson, "exp");
        Long nbf = readLong(claimsJson, "nbf");
        String algorithm = header.get("alg");
        String keyId = header.get("kid");
        String issuer = claims.get("iss");
        String audience = claims.get("aud");
        String cpk = claims.get("cpk");
        String xuid = claims.get("xid");
        String displayName = claims.get("xname");
        if (!"RS256".equals(algorithm)
                || keyId == null || keyId.isBlank()
                || !"https://authorization.franchise.minecraft-services.net/".equals(issuer)
                || !"api://auth-minecraft-services/multiplayer".equals(audience)
                || cpk == null || xuid == null || xuid.isBlank()
                || displayName == null || displayName.isBlank() || exp == null) {
            throw new SecurityException("GameServerToken header or claims are not trusted");
        }

        RSAPublicKey signingKey = keyResolver.resolve(keyId);
        if (signingKey == null) {
            throw new SecurityException("Unknown GameServerToken signing key");
        }
        byte[] signatureBytes = decodeUrl(parts[2]);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(signingKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        try {
            if (!verifier.verify(signatureBytes)) {
                throw new SecurityException("GameServerToken signature verification failed");
            }
        } catch (java.security.SignatureException malformedSignature) {
            throw new SecurityException("GameServerToken signature is malformed", malformedSignature);
        }

        long now = clock.instant().getEpochSecond();
        if (exp <= now - clockSkewSeconds) {
            throw new SecurityException("GameServerToken has expired");
        }
        if (nbf != null && nbf > now + clockSkewSeconds) {
            throw new SecurityException("GameServerToken is not yet valid");
        }

        byte[] spki;
        try {
            spki = Base64.getDecoder().decode(cpk);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Invalid cpk SubjectPublicKeyInfo", e);
        }
        java.security.PublicKey decodedClientKey = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(spki));
        if (!(decodedClientKey instanceof ECPublicKey clientKey)
                || clientKey.getParams().getCurve().getField().getFieldSize() != 384) {
            throw new SecurityException("GameServerToken cpk must be a P-384 public key");
        }
        return new VerifiedClientIdentity(xuid, displayName, clientKey, exp);
    }

    private static void verifyParsedFieldsMatchSignedToken(String[] parts,
                                                            NetherNetIdentityAssertion assertion)
            throws Exception {
        String headerJson = decodeUtf8(decodeUrl(parts[0]));
        String claimsJson = decodeUtf8(decodeUrl(parts[1]));
        Map<String, String> header = readStringFields(headerJson, "alg", "kid");
        Map<String, String> claims = readStringFields(claimsJson,
                "iss", "aud", "cpk", "xid", "xname");
        Long exp = readLong(claimsJson, "exp");
        Long nbf = readLong(claimsJson, "nbf");
        if (!"RS256".equals(header.get("alg"))
                || !assertion.jwtKeyId().equals(header.get("kid"))
                || !assertion.issuer().equals(claims.get("iss"))
                || !assertion.audience().equals(claims.get("aud"))
                || !assertion.clientPublicKeySpkiBase64().equals(claims.get("cpk"))
                || !Objects.equals(assertion.xuid(), claims.get("xid"))
                || !Objects.equals(assertion.displayName(), claims.get("xname"))
                || exp == null || exp.longValue() != assertion.expiresAtEpochSeconds()
                || !Objects.equals(assertion.notBeforeEpochSeconds(), nbf)) {
            throw new SecurityException("Parsed identity fields do not match signed JWT contents");
        }
    }

    private static Map<String, String> readStringFields(String json, String... wanted)
            throws IOException {
        Map<String, String> values = new HashMap<>();
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("JWT JSON must be an object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                boolean capture = false;
                for (String field : wanted) {
                    if (field.equals(name)) {
                        capture = true;
                        break;
                    }
                }
                if (capture) {
                    if (value != JsonToken.VALUE_STRING) {
                        throw new IOException("JWT field " + name + " must be a string");
                    }
                    values.put(name, parser.getText());
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                throw new IOException("JWT JSON contains trailing data");
            }
        }
        return values;
    }

    private static Long readLong(String json, String wanted) throws IOException {
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("JWT claims must be an object");
            }
            Long result = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if (wanted.equals(name)) {
                    if (value != JsonToken.VALUE_NUMBER_INT) {
                        throw new IOException("JWT " + wanted + " must be an integer");
                    }
                    result = parser.getLongValue();
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                throw new IOException("JWT claims contain trailing data");
            }
            return result;
        }
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IOException("JWT JSON is not valid UTF-8", e);
        }
    }

    private static byte[] decodeUrl(String value) {
        if (value.indexOf('=') >= 0) {
            throw new IllegalArgumentException("JWT signature must be unpadded Base64URL");
        }
        return Base64.getUrlDecoder().decode(value);
    }

    public record VerifiedClientIdentity(String xuid, String displayName,
                                         ECPublicKey clientPublicKey,
                                         long expiresAtEpochSeconds) {
        public VerifiedClientIdentity {
            Objects.requireNonNull(clientPublicKey, "clientPublicKey");
        }
    }
}
