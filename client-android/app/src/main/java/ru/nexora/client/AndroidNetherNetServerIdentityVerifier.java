package ru.nexora.client;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Android-side verifier for the operator assertion carried by an SDP answer. */
public final class AndroidNetherNetServerIdentityVerifier {
    public static final String TRUST_PREFERENCES = "nexora_operator_pins";
    private static final long CLOCK_SKEW_SECONDS = 120L;

    private AndroidNetherNetServerIdentityVerifier() {
    }

    public static VerificationResult verify(String answerSdp, boolean signalingWasTls,
                                             SharedPreferences trustStore) throws Exception {
        if (answerSdp == null || trustStore == null) {
            throw new IllegalArgumentException("SDP and trust store are required");
        }
        byte[] sdpBytes = answerSdp.getBytes(StandardCharsets.UTF_8);
        if (sdpBytes.length > 256 * 1024 || answerSdp.indexOf('\0') >= 0) {
            throw new SecurityException("SDP answer is invalid or too large");
        }

        String newline = answerSdp.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = answerSdp.split("\\r?\\n", -1);
        int identityIndex = -1;
        int firstMedia = -1;
        int lastSessionFingerprint = -1;
        List<String> fingerprintLines = new ArrayList<>();
        String encodedIdentity = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("m=") && firstMedia < 0) {
                firstMedia = i;
            }
            if (line.startsWith("a=fingerprint:")) {
                fingerprintLines.add(line);
                if (firstMedia < 0) {
                    lastSessionFingerprint = i;
                }
            }
            if (line.startsWith("a=identity:")) {
                if (encodedIdentity != null) {
                    throw new SecurityException("SDP answer contains duplicate a=identity");
                }
                if (firstMedia >= 0 || i == 0) {
                    throw new SecurityException("a=identity is not a session-level attribute");
                }
                identityIndex = i;
                encodedIdentity = line.substring("a=identity:".length()).trim();
            }
        }
        if (encodedIdentity == null || identityIndex <= lastSessionFingerprint
                || firstMedia < 0 || lastSessionFingerprint < 0) {
            throw new SecurityException("SDP answer has missing or misplaced identity/fingerprint attributes");
        }

        byte[] envelopeBytes = Base64.getDecoder().decode(encodedIdentity);
        JSONObject envelope = new JSONObject(decodeUtf8(envelopeBytes));
        JSONObject idp = envelope.getJSONObject("idp");
        String domain = idp.getString("domain");
        if (!"default".equals(idp.getString("protocol")) || domain.isEmpty()
                || domain.length() > 253 || containsControl(domain)) {
            throw new SecurityException("Unsupported operator identity provider");
        }
        JSONObject assertion = new JSONObject(envelope.getString("assertion"));
        String token = assertion.getString("token");
        String fingerprintsJws = assertion.getString("fingerprints");

        String[] jwt = token.split("\\.", -1);
        if (jwt.length != 3 || jwt[0].isEmpty() || jwt[1].isEmpty() || jwt[2].isEmpty()) {
            throw new SecurityException("Malformed operator identity JWT");
        }
        JSONObject jwtHeader = new JSONObject(decodeUtf8(decodeUrl(jwt[0])));
        if (!"ES384".equals(jwtHeader.getString("alg"))) {
            throw new SecurityException("Operator identity JWT must use ES384");
        }
        JSONObject claims = new JSONObject(decodeUtf8(decodeUrl(jwt[1])));
        JSONObject cpk = claims.getJSONObject("cpk");
        ECPublicKey operatorKey = publicKeyFromJwk(cpk);

        Signature jwtVerifier = Signature.getInstance("SHA384withECDSA");
        jwtVerifier.initVerify(operatorKey);
        jwtVerifier.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
        if (!jwtVerifier.verify(rawEcdsaToDer(decodeUrl(jwt[2])))) {
            throw new SecurityException("Operator identity JWT signature is invalid");
        }

        long now = Instant.now().getEpochSecond();
        if (claims.has("exp") && claims.getLong("exp") <= now - CLOCK_SKEW_SECONDS) {
            throw new SecurityException("Operator identity token has expired");
        }
        if (claims.has("iat") && claims.getLong("iat") > now + CLOCK_SKEW_SECONDS) {
            throw new SecurityException("Operator identity token was issued in the future");
        }

        String canonicalFingerprints = canonicalFingerprintJson(fingerprintLines);
        verifyDetachedFingerprints(fingerprintsJws, canonicalFingerprints, operatorKey);

        String thumbprint = jwkThumbprint(cpk);
        boolean pinned = trustStore.getBoolean("operator:" + thumbprint, false);
        TrustStatus trustStatus = signalingWasTls
                ? TrustStatus.TLS_AUTHENTICATED
                : (pinned ? TrustStatus.PINNED : TrustStatus.FIRST_USE_CONFIRMATION_REQUIRED);

        StringBuilder cleaned = new StringBuilder(answerSdp.length());
        for (int i = 0; i < lines.length; i++) {
            if (i == identityIndex) {
                continue;
            }
            if (i > 0) {
                cleaned.append(newline);
            }
            cleaned.append(lines[i]);
        }
        return new VerificationResult(domain, thumbprint, cleaned.toString(), trustStatus);
    }

    public static void rememberAfterUserApproval(SharedPreferences trustStore,
                                                  String fingerprint,
                                                  boolean approved) {
        if (!approved) {
            return;
        }
        trustStore.edit().putBoolean("operator:" + fingerprint, true).apply();
    }

    private static ECPublicKey publicKeyFromJwk(JSONObject jwk) throws Exception {
        if (!"EC".equals(jwk.getString("kty")) || !"P-384".equals(jwk.getString("crv"))
                || jwk.has("d")) {
            throw new SecurityException("Operator cpk must be a public P-384 JWK");
        }
        byte[] x = decodeUrl(jwk.getString("x"));
        byte[] y = decodeUrl(jwk.getString("y"));
        if (x.length != 48 || y.length != 48) {
            throw new SecurityException("Operator P-384 JWK coordinates must be 48 bytes");
        }
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp384r1"));
        ECParameterSpec ec = parameters.getParameterSpec(ECParameterSpec.class);
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), ec));
    }

    private static void verifyDetachedFingerprints(String compact, String payload,
                                                    ECPublicKey key) throws Exception {
        String[] parts = compact.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || !parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new SecurityException("Malformed detached fingerprint JWS");
        }
        JSONObject header = new JSONObject(decodeUtf8(decodeUrl(parts[0])));
        if (!"ES384".equals(header.getString("alg"))) {
            throw new SecurityException("Fingerprint JWS must use ES384");
        }
        Signature verifier = Signature.getInstance("SHA384withECDSA");
        verifier.initVerify(key);
        verifier.update((parts[0] + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(rawEcdsaToDer(decodeUrl(parts[2])))) {
            throw new SecurityException("Operator fingerprint signature is invalid");
        }
    }

    private static String canonicalFingerprintJson(List<String> lines) {
        if (lines.isEmpty()) {
            throw new SecurityException("SDP answer contains no DTLS fingerprints");
        }
        StringBuilder json = new StringBuilder("{\"fingerprint\":[");
        for (int i = 0; i < lines.size(); i++) {
            String value = lines.get(i).substring("a=fingerprint:".length()).trim();
            int split = value.indexOf(' ');
            if (split <= 0 || split == value.length() - 1) {
                throw new SecurityException("Malformed SDP fingerprint attribute");
            }
            String algorithm = value.substring(0, split).toLowerCase(Locale.ROOT);
            String digest = value.substring(split + 1).trim();
            int expectedBytes = switch (algorithm) {
                case "sha-256" -> 32;
                case "sha-384" -> 48;
                case "sha-512" -> 64;
                default -> throw new SecurityException("Unsupported DTLS fingerprint algorithm");
            };
            if (!digest.matches("(?i)([0-9a-f]{2}:){"
                    + (expectedBytes - 1) + "}[0-9a-f]{2}")) {
                throw new SecurityException("Malformed DTLS fingerprint digest");
            }
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"algorithm\":\"").append(algorithm)
                    .append("\",\"digest\":\"").append(digest).append("\"}");
        }
        return json.append("]}").toString();
    }

    private static String jwkThumbprint(JSONObject jwk) throws Exception {
        String canonical = "{\"crv\":\"P-384\",\"kty\":\"EC\",\"x\":\""
                + jwk.getString("x") + "\",\"y\":\"" + jwk.getString("y") + "\"}";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
        return toHex(digest);
    }

    private static byte[] rawEcdsaToDer(byte[] raw) {
        if (raw.length != 96) {
            throw new SecurityException("ES384 signature must contain 96 bytes");
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

    private static byte[] decodeUrl(String value) {
        if (value.indexOf('=') >= 0 || value.length() > 16 * 1024) {
            throw new SecurityException("Invalid Base64URL JWT/JWS segment");
        }
        return Base64.getUrlDecoder().decode(value);
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    public enum TrustStatus {
        TLS_AUTHENTICATED,
        PINNED,
        FIRST_USE_CONFIRMATION_REQUIRED
    }

    public record VerificationResult(String displayDomain, String keyFingerprint,
                                     String cleanedSdp, TrustStatus trustStatus) {
    }
}
