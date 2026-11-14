package ru.nexora.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Verifies the operator assertion carried by a NetherNet SDP answer. */
public final class NetherNetServerIdentityVerifier {
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(16 * 1024).maxNumberLength(64).build())
            .build();

    private final Clock clock;
    private final long clockSkewSeconds;

    public NetherNetServerIdentityVerifier() {
        this(Clock.systemUTC(), NetherNetGameServerTokenVerifier.DEFAULT_CLOCK_SKEW_SECONDS);
    }

    public NetherNetServerIdentityVerifier(Clock clock, long clockSkewSeconds) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (clockSkewSeconds < 0 || clockSkewSeconds > 600) {
            throw new IllegalArgumentException("Clock skew must be between 0 and 600 seconds");
        }
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public VerifiedOperatorIdentity verify(String answerSdp,
                                           boolean signalingWasTlsAuthenticated,
                                           NetherNetOperatorTrustStore trustStore)
            throws Exception {
        Objects.requireNonNull(trustStore, "trustStore");
        NetherNetSdpDocument document = NetherNetSdpDocument.parseOffer(answerSdp);
        Map<String, String> envelope = readStringObject(
                document.identityEnvelopeJson(), "identity envelope", "assertion");
        Map<String, String> idp = readObjectField(
                document.identityEnvelopeJson(), "idp", "domain", "protocol");
        String domain = required(idp, "domain");
        if (!"default".equals(required(idp, "protocol"))) {
            throw new SecurityException("Unsupported operator identity provider protocol");
        }
        if (domain.length() > 253 || domain.isBlank()
                || domain.chars().anyMatch(Character::isISOControl)) {
            throw new SecurityException("Invalid operator identity display domain");
        }

        Map<String, String> assertion = readStringObject(
                required(envelope, "assertion"), "operator assertion", "token", "fingerprints");
        String token = required(assertion, "token");
        String[] jwt = token.split("\\.", -1);
        if (jwt.length != 3 || jwt[0].isEmpty() || jwt[1].isEmpty() || jwt[2].isEmpty()) {
            throw new SecurityException("Malformed operator identity JWT");
        }
        Map<String, String> header = readStringObject(
                decodeUtf8(decodeUrl(jwt[0])), "operator JWT header", "alg");
        if (!"ES384".equals(required(header, "alg"))) {
            throw new SecurityException("Operator JWT must use ES384");
        }
        ServerClaims claims = readServerClaims(decodeUtf8(decodeUrl(jwt[1])));
        ECPublicKey operatorKey = createPublicKey(claims.jwk());

        Signature jwtVerifier = Signature.getInstance("SHA384withECDSA");
        jwtVerifier.initVerify(operatorKey);
        jwtVerifier.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
        if (!jwtVerifier.verify(rawEcdsaToDer(decodeUrl(jwt[2])))) {
            throw new SecurityException("Operator identity JWT signature is invalid");
        }

        long now = clock.instant().getEpochSecond();
        if (claims.exp() != null && claims.exp() <= now - clockSkewSeconds) {
            throw new SecurityException("Operator identity JWT has expired");
        }
        if (claims.iat() != null && claims.iat() > now + clockSkewSeconds) {
            throw new SecurityException("Operator identity JWT was issued in the future");
        }

        NetherNetFingerprintPayload fingerprints = NetherNetFingerprintPayload.fromSdp(document);
        if (!NetherNetFingerprintJwsVerifier.verify(
                required(assertion, "fingerprints"), fingerprints, operatorKey)) {
            throw new SecurityException("Operator DTLS fingerprint signature is invalid");
        }

        String keyFingerprint = jwkThumbprint(operatorKey);
        TrustStatus trustStatus;
        if (signalingWasTlsAuthenticated) {
            trustStatus = TrustStatus.TLS_AUTHENTICATED;
        } else if (trustStore.isTrusted(keyFingerprint)) {
            trustStatus = TrustStatus.PINNED;
        } else {
            trustStatus = TrustStatus.FIRST_USE_CONFIRMATION_REQUIRED;
        }
        return new VerifiedOperatorIdentity(domain, claims.issuer(), operatorKey,
                keyFingerprint, trustStatus);
    }

    private static String jwkThumbprint(ECPublicKey key) throws Exception {
        String x = Base64.getUrlEncoder().withoutPadding().encodeToString(
                unsignedFixed(key.getW().getAffineX(), 48));
        String y = Base64.getUrlEncoder().withoutPadding().encodeToString(
                unsignedFixed(key.getW().getAffineY(), 48));
        String canonicalJwk = "{\"crv\":\"P-384\",\"kty\":\"EC\",\"x\":\""
                + x + "\",\"y\":\"" + y + "\"}";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalJwk.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private static byte[] unsignedFixed(BigInteger value, int length) {
        byte[] encoded = value.toByteArray();
        int start = encoded.length > 1 && encoded[0] == 0 ? 1 : 0;
        int count = encoded.length - start;
        if (count > length) {
            throw new IllegalArgumentException("EC coordinate exceeds P-384 width");
        }
        byte[] result = new byte[length];
        System.arraycopy(encoded, start, result, length - count, count);
        return result;
    }

    private static ServerClaims readServerClaims(String json) throws IOException {
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Operator JWT claims must be an object");
            }
            Map<String, String> jwk = null;
            String issuer = null;
            Long exp = null;
            Long iat = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (name) {
                    case "cpk" -> {
                        if (value != JsonToken.START_OBJECT) {
                            throw new IOException("Operator cpk claim must be a JWK object");
                        }
                        jwk = readFields(parser, "kty", "crv", "x", "y", "d");
                    }
                    case "iss" -> {
                        if (value != JsonToken.VALUE_STRING) {
                            throw new IOException("Operator iss claim must be a string");
                        }
                        issuer = parser.getText();
                    }
                    case "exp" -> {
                        if (value != JsonToken.VALUE_NUMBER_INT) {
                            throw new IOException("Operator exp claim must be an integer");
                        }
                        exp = parser.getLongValue();
                    }
                    case "iat" -> {
                        if (value != JsonToken.VALUE_NUMBER_INT) {
                            throw new IOException("Operator iat claim must be an integer");
                        }
                        iat = parser.getLongValue();
                    }
                    default -> parser.skipChildren();
                }
            }
            if (parser.nextToken() != null || jwk == null) {
                throw new IOException("Operator JWT claims are malformed or missing cpk");
            }
            return new ServerClaims(jwk, issuer, exp, iat);
        }
    }

    private static ECPublicKey createPublicKey(Map<String, String> jwk) throws Exception {
        if (!"EC".equals(jwk.get("kty")) || !"P-384".equals(jwk.get("crv"))
                || jwk.containsKey("d")) {
            throw new SecurityException("Operator cpk must be a public P-384 JWK");
        }
        byte[] x = decodeUrl(required(jwk, "x"));
        byte[] y = decodeUrl(required(jwk, "y"));
        if (x.length != 48 || y.length != 48) {
            throw new SecurityException("Operator P-384 JWK coordinates must be 48 bytes");
        }
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp384r1"));
        ECParameterSpec ec = parameters.getParameterSpec(ECParameterSpec.class);
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), ec));
    }

    private static Map<String, String> readObjectField(String json, String objectField,
                                                        String... wanted) throws IOException {
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Identity envelope must be an object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if (objectField.equals(name)) {
                    if (value != JsonToken.START_OBJECT) {
                        throw new IOException(objectField + " must be an object");
                    }
                    return readFields(parser, wanted);
                }
                parser.skipChildren();
            }
        }
        throw new IOException("Identity envelope is missing " + objectField);
    }

    private static Map<String, String> readStringObject(String json, String label,
                                                         String... wanted) throws IOException {
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException(label + " must be an object");
            }
            Map<String, String> result = readFields(parser, wanted);
            if (parser.nextToken() != null) {
                throw new IOException(label + " contains trailing JSON");
            }
            return result;
        }
    }

    private static Map<String, String> readFields(JsonParser parser, String... wanted)
            throws IOException {
        Map<String, String> result = new HashMap<>();
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
                    throw new IOException(name + " must be a string");
                }
                result.put(name, parser.getText());
            } else {
                parser.skipChildren();
            }
        }
        return result;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new SecurityException("Missing or empty " + key);
        }
        return value;
    }

    private static byte[] decodeUrl(String value) {
        if (value.indexOf('=') >= 0 || value.length() > 16 * 1024) {
            throw new SecurityException("JWT/JWS value must be bounded unpadded Base64URL");
        }
        return Base64.getUrlDecoder().decode(value);
    }

    private static String decodeUtf8(byte[] value) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(value)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IOException("JWT JSON is not valid UTF-8", e);
        }
    }

    private static byte[] rawEcdsaToDer(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 96) {
            throw new GeneralSecurityException("ES384 signature must contain 96 bytes");
        }
        byte[] r = derInteger(new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, 48)));
        byte[] s = derInteger(new BigInteger(1, java.util.Arrays.copyOfRange(raw, 48, 96)));
        byte[] result = new byte[2 + r.length + s.length];
        result[0] = 0x30;
        result[1] = (byte) (r.length + s.length);
        System.arraycopy(r, 0, result, 2, r.length);
        System.arraycopy(s, 0, result, 2 + r.length, s.length);
        return result;
    }

    private static byte[] derInteger(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] result = new byte[bytes.length + 2];
        result[0] = 0x02;
        result[1] = (byte) bytes.length;
        System.arraycopy(bytes, 0, result, 2, bytes.length);
        return result;
    }

    private record ServerClaims(Map<String, String> jwk, String issuer, Long exp, Long iat) {
    }

    public enum TrustStatus {
        TLS_AUTHENTICATED,
        PINNED,
        FIRST_USE_CONFIRMATION_REQUIRED
    }

    public record VerifiedOperatorIdentity(String displayDomain, String issuer,
                                            ECPublicKey publicKey,
                                            String sha256PublicKeyFingerprint,
                                            TrustStatus trustStatus) {
    }
}
