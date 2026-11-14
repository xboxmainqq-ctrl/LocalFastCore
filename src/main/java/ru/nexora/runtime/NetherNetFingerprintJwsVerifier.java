package ru.nexora.runtime;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Objects;

/**
 * Verifies the detached ES384 signature over canonical SDP fingerprints.
 *
 * <p>The public key must come from a GameServerToken whose issuer signature
 * and claims have already been verified. This class does not authenticate the
 * JWT or establish that the supplied key is trusted.</p>
 */
public final class NetherNetFingerprintJwsVerifier {
    private NetherNetFingerprintJwsVerifier() {
    }

    public static boolean verify(String detachedJws,
                                 NetherNetFingerprintPayload payload,
                                 PublicKey verifiedClientPublicKey)
            throws GeneralSecurityException {
        Objects.requireNonNull(detachedJws, "detachedJws");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(verifiedClientPublicKey, "verifiedClientPublicKey");

        if (!(verifiedClientPublicKey instanceof ECPublicKey ecKey)
                || ecKey.getParams().getCurve().getField().getFieldSize() != 384) {
            throw new GeneralSecurityException("NetherNet ES384 requires a P-384 public key");
        }

        String[] parts = detachedJws.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || !parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new GeneralSecurityException("Malformed detached compact JWS");
        }
        byte[] headerBytes = decode(parts[0]);
        byte[] signatureBytes = decode(parts[2]);
        if (signatureBytes.length != 96) {
            throw new GeneralSecurityException("ES384 signature must contain 96 raw bytes");
        }
        String header = new String(headerBytes, StandardCharsets.UTF_8);
        if (!header.matches("\\{\\s*\"alg\"\\s*:\\s*\"ES384\"\\s*}")) {
            throw new GeneralSecurityException("Only a strict ES384 protected header is accepted");
        }

        String signingInput = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.canonicalJsonBytes());
        Signature verifier = Signature.getInstance("SHA384withECDSA");
        verifier.initVerify(verifiedClientPublicKey);
        verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return verifier.verify(rawEcdsaToDer(signatureBytes));
    }

    private static byte[] decode(String value) throws GeneralSecurityException {
        if (value.indexOf('=') >= 0) {
            throw new GeneralSecurityException("Detached JWS must use unpadded Base64URL");
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Invalid detached JWS Base64URL", e);
        }
    }

    private static byte[] rawEcdsaToDer(byte[] raw) {
        int half = raw.length / 2;
        byte[] r = derInteger(new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, half)));
        byte[] s = derInteger(new BigInteger(1, java.util.Arrays.copyOfRange(raw, half, raw.length)));
        ByteArrayOutputStream sequence = new ByteArrayOutputStream();
        sequence.write(0x30);
        sequence.write(r.length + s.length);
        sequence.writeBytes(r);
        sequence.writeBytes(s);
        return sequence.toByteArray();
    }

    private static byte[] derInteger(BigInteger value) {
        byte[] bytes = value.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x02);
        out.write(bytes.length);
        out.writeBytes(bytes);
        return out.toByteArray();
    }
}
