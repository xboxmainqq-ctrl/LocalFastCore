package ru.nexora.runtime;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Produces the operator identity assertion required in every NetherNet SDP answer.
 *
 * <p>The supplied long-lived P-384 private key is never serialized or persisted
 * by this class. The caller is responsible for loading it from secure storage.</p>
 */
public final class NetherNetServerIdentitySigner {
    public static final int MAX_SDP_BYTES = NetherNetSdpDocument.DEFAULT_MAX_SDP_BYTES;
    private static final long MAX_TOKEN_LIFETIME_SECONDS = Duration.ofDays(3650).toSeconds();

    private NetherNetServerIdentitySigner() {
    }

    public static String addIdentity(String answerSdp, String partnerDomain,
                                     KeyPair operatorKeyPair,
                                     Instant issuedAt, Instant expiresAt)
            throws GeneralSecurityException {
        Objects.requireNonNull(answerSdp, "answerSdp");
        Objects.requireNonNull(partnerDomain, "partnerDomain");
        Objects.requireNonNull(operatorKeyPair, "operatorKeyPair");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");

        validateDomain(partnerDomain);
        if (answerSdp.getBytes(StandardCharsets.UTF_8).length > MAX_SDP_BYTES
                || answerSdp.indexOf('\\0') >= 0) {
            throw new IllegalArgumentException("SDP answer is invalid or exceeds configured limit");
        }
        long iat = issuedAt.getEpochSecond();
        long exp = expiresAt.getEpochSecond();
        long lifetime;
        try {
            lifetime = Math.subtractExact(exp, iat);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Server identity token lifetime is invalid", overflow);
        }
        if (lifetime <= 0 || lifetime > MAX_TOKEN_LIFETIME_SECONDS) {
            throw new IllegalArgumentException("Server identity token lifetime is invalid");
        }
        ECPublicKey publicKey = requireP384PublicKey(operatorKeyPair.getPublic());
        ECPrivateKey privateKey = requireP384PrivateKey(operatorKeyPair.getPrivate());
        verifyKeyPair(privateKey, publicKey);

        String newline = answerSdp.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = answerSdp.split("\\r?\\n", -1);
        int firstMedia = -1;
        int lastSessionFingerprint = -1;
        List<String> fingerprints = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("a=identity:")) {
                throw new IllegalArgumentException("SDP answer already contains a=identity");
            }
            if (line.startsWith("m=") && firstMedia < 0) {
                firstMedia = i;
            }
            if (line.startsWith("a=fingerprint:")) {
                fingerprints.add(line);
                if (firstMedia < 0) {
                    lastSessionFingerprint = i;
                }
            }
        }
        if (firstMedia < 0 || lastSessionFingerprint < 0) {
            throw new IllegalArgumentException(
                    "SDP answer requires a session fingerprint before its first media section");
        }

        NetherNetFingerprintPayload payload =
                NetherNetFingerprintPayload.fromFingerprintLines(fingerprints);
        String canonicalJwk = publicJwk(publicKey);
        String jwt = createServerJwt(partnerDomain, canonicalJwk, iat, exp, privateKey);
        String detachedJws = createDetachedJws(payload, privateKey);
        String assertion = "{\"token\":" + quote(jwt)
                + ",\"fingerprints\":" + quote(detachedJws) + "}";
        String envelope = "{\"idp\":{\"domain\":" + quote(partnerDomain)
                + ",\"protocol\":\"default\"},\"assertion\":" + quote(assertion) + "}";
        String identityLine = "a=identity:"
                + Base64.getEncoder().encodeToString(envelope.getBytes(StandardCharsets.UTF_8));

        List<String> output = new ArrayList<>(List.of(lines));
        output.add(lastSessionFingerprint + 1, identityLine);
        String result = String.join(newline, output);
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_SDP_BYTES) {
            throw new IllegalArgumentException("SDP answer with identity exceeds configured limit");
        }
        return result;
    }

    private static String createServerJwt(String domain, String jwk, long iat, long exp,
                                          ECPrivateKey privateKey)
            throws GeneralSecurityException {
        String header = "{\"alg\":\"ES384\",\"typ\":\"JWT\"}";
        String claims = "{\"cpk\":" + jwk + ",\"iat\":" + iat + ",\"exp\":" + exp
                + ",\"iss\":" + quote(domain) + "}";
        return signCompact(header, claims, privateKey);
    }

    private static String createDetachedJws(NetherNetFingerprintPayload payload,
                                            ECPrivateKey privateKey)
            throws GeneralSecurityException {
        String header = "{\"alg\":\"ES384\"}";
        String encodedHeader = base64Url(header.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = base64Url(payload.canonicalJsonBytes());
        String signingInput = encodedHeader + "." + encodedPayload;
        return encodedHeader + ".." + base64Url(sign(signingInput, privateKey));
    }

    private static String signCompact(String header, String payload, ECPrivateKey privateKey)
            throws GeneralSecurityException {
        String encodedHeader = base64Url(header.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = base64Url(payload.getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + encodedPayload;
        return signingInput + "." + base64Url(sign(signingInput, privateKey));
    }

    private static byte[] sign(String signingInput, ECPrivateKey privateKey)
            throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA384withECDSA");
        signer.initSign(privateKey);
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return derEcdsaToRaw(signer.sign(), 48);
    }

    private static String publicJwk(ECPublicKey key) {
        return "{\"kty\":\"EC\",\"crv\":\"P-384\",\"x\":"
                + quote(base64Url(unsignedFixed(key.getW().getAffineX(), 48)))
                + ",\"y\":" + quote(base64Url(unsignedFixed(key.getW().getAffineY(), 48))) + "}";
    }

    private static byte[] unsignedFixed(BigInteger value, int length) {
        byte[] encoded = value.toByteArray();
        int start = encoded.length > 1 && encoded[0] == 0 ? 1 : 0;
        int bytes = encoded.length - start;
        if (bytes > length) {
            throw new IllegalArgumentException("EC coordinate exceeds P-384 width");
        }
        byte[] result = new byte[length];
        System.arraycopy(encoded, start, result, length - bytes, bytes);
        return result;
    }

    private static byte[] derEcdsaToRaw(byte[] der, int coordinateBytes)
            throws GeneralSecurityException {
        try {
            int offset = 0;
            if ((der[offset++] & 0xff) != 0x30) {
                throw new IllegalArgumentException("ECDSA signature is not a DER sequence");
            }
            int sequenceLength = der[offset++] & 0xff;
            if (sequenceLength != der.length - offset || (der[offset++] & 0xff) != 0x02) {
                throw new IllegalArgumentException("Malformed DER ECDSA signature");
            }
            int rLength = der[offset++] & 0xff;
            byte[] r = java.util.Arrays.copyOfRange(der, offset, offset + rLength);
            offset += rLength;
            if ((der[offset++] & 0xff) != 0x02) {
                throw new IllegalArgumentException("Missing ECDSA s integer");
            }
            int sLength = der[offset++] & 0xff;
            byte[] s = java.util.Arrays.copyOfRange(der, offset, offset + sLength);
            if (offset + sLength != der.length) {
                throw new IllegalArgumentException("Trailing DER ECDSA signature data");
            }
            byte[] raw = new byte[coordinateBytes * 2];
            copyInteger(r, raw, 0, coordinateBytes);
            copyInteger(s, raw, coordinateBytes, coordinateBytes);
            return raw;
        } catch (RuntimeException malformed) {
            throw new GeneralSecurityException("Could not encode ES384 signature", malformed);
        }
    }

    private static void copyInteger(byte[] value, byte[] target, int offset, int size) {
        int sourceOffset = value.length > size && value.length > 0 && value[0] == 0 ? 1 : 0;
        int length = value.length - sourceOffset;
        if (length < 1 || length > size) {
            throw new IllegalArgumentException("ECDSA integer has invalid width");
        }
        System.arraycopy(value, sourceOffset, target, offset + size - length, length);
    }

    private static ECPublicKey requireP384PublicKey(java.security.PublicKey key)
            throws GeneralSecurityException {
        if (!(key instanceof ECPublicKey ec)
                || ec.getParams().getCurve().getField().getFieldSize() != 384) {
            throw new GeneralSecurityException("Operator public key must be P-384");
        }
        return ec;
    }

    private static ECPrivateKey requireP384PrivateKey(java.security.PrivateKey key)
            throws GeneralSecurityException {
        if (!(key instanceof ECPrivateKey ec)
                || ec.getParams().getCurve().getField().getFieldSize() != 384) {
            throw new GeneralSecurityException("Operator private key must be P-384");
        }
        return ec;
    }

    private static void verifyKeyPair(ECPrivateKey privateKey, ECPublicKey publicKey)
            throws GeneralSecurityException {
        byte[] challenge = "NEXORA-NetherNet-operator-key-check".getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("SHA384withECDSA");
        signer.initSign(privateKey);
        signer.update(challenge);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA384withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(challenge);
        if (!verifier.verify(signature)) {
            throw new GeneralSecurityException("Operator public and private keys do not match");
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static void validateDomain(String domain) {
        if (domain.isBlank() || domain.length() > 253) {
            throw new IllegalArgumentException("Partner domain must be a valid ASCII domain name");
        }
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) {
                throw new IllegalArgumentException("Partner domain must be a valid ASCII domain name");
            }
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
