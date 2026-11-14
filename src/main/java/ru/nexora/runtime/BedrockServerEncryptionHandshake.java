package ru.nexora.runtime;

import javax.crypto.KeyAgreement;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Creates the Bedrock server-to-client encryption handshake token and derives
 * the session AES key from a validated P-384 client identity key.
 *
 * <p>The caller must authenticate the Login request and obtain the client's
 * identity public key from its verified certificate/token chain before
 * calling this class. This class does not authenticate a player.</p>
 *
 * <p>The generated token is an ES384 JWT with the server public key in x5u
 * and a random salt claim. The derived key material is SHA-256(salt || ECDH-secret).
 * Transport-specific encryption must be selected separately; NetherNet already
 * provides DTLS protection and must not inherit an unverified legacy cipher mode.</p>
 */
public final class BedrockServerEncryptionHandshake {
    public static final int SALT_BYTES = 16;
    public static final int SESSION_KEY_BYTES = 32;
    private static final int P384_BYTES = 48;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private BedrockServerEncryptionHandshake() {
    }

    public static Result create(KeyPair serverKeyPair, ECPublicKey authenticatedClientPublicKey) {
        return create(serverKeyPair, authenticatedClientPublicKey, new SecureRandom());
    }

    static Result create(KeyPair serverKeyPair, ECPublicKey authenticatedClientPublicKey,
                         SecureRandom random) {
        Objects.requireNonNull(serverKeyPair, "serverKeyPair");
        Objects.requireNonNull(authenticatedClientPublicKey, "authenticatedClientPublicKey");
        Objects.requireNonNull(random, "random");

        if (!(serverKeyPair.getPrivate() instanceof ECPrivateKey serverPrivate)
                || !(serverKeyPair.getPublic() instanceof ECPublicKey serverPublic)) {
            throw new IllegalArgumentException("Bedrock server handshake requires an EC key pair");
        }
        requireP384(serverPrivate, "server private key");
        requireP384(serverPublic, "server public key");
        requireP384(authenticatedClientPublicKey, "authenticated client public key");

        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] sharedSecret = null;
        byte[] sessionKey = null;
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(serverPrivate);
            agreement.doPhase(authenticatedClientPublicKey, true);
            sharedSecret = agreement.generateSecret();

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(salt);
            sessionKey = sha256.digest(sharedSecret);

            String header = "{\"alg\":\"ES384\",\"x5u\":\""
                    + Base64.getEncoder().encodeToString(serverPublic.getEncoded()) + "\"}";
            String payload = "{\"salt\":\""
                    + Base64.getEncoder().encodeToString(salt) + "\"}";
            String encodedHeader = URL_ENCODER.encodeToString(header.getBytes(StandardCharsets.UTF_8));
            String encodedPayload = URL_ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            String signingInput = encodedHeader + "." + encodedPayload;

            Signature signer = Signature.getInstance("SHA384withECDSA");
            signer.initSign(serverPrivate, random);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            byte[] joseSignature = derToJose(signer.sign());
            String token = signingInput + "." + URL_ENCODER.encodeToString(joseSignature);
            return new Result(token, sessionKey, salt);
        } catch (GeneralSecurityException failure) {
            Arrays.fill(salt, (byte) 0);
            if (sessionKey != null) Arrays.fill(sessionKey, (byte) 0);
            throw new IllegalStateException("Could not create Bedrock encryption handshake", failure);
        } finally {
            if (sharedSecret != null) Arrays.fill(sharedSecret, (byte) 0);
        }
    }

    private static void requireP384(java.security.interfaces.ECKey key, String label) {
        if (key.getParams() == null
                || key.getParams().getOrder().bitLength() != 384
                || key.getParams().getCurve().getField().getFieldSize() != 384
                || key.getParams().getCofactor() != 1) {
            throw new IllegalArgumentException(label + " must use the NIST P-384 curve");
        }
    }

    private static byte[] derToJose(byte[] der) throws GeneralSecurityException {
        int[] offset = {0};
        if (readByte(der, offset) != 0x30) throw new GeneralSecurityException("Invalid ECDSA DER sequence");
        int sequenceLength = readLength(der, offset);
        if (sequenceLength != der.length - offset[0]) throw new GeneralSecurityException("Invalid ECDSA DER length");
        byte[] r = readInteger(der, offset);
        byte[] s = readInteger(der, offset);
        if (offset[0] != der.length) throw new GeneralSecurityException("Trailing ECDSA DER bytes");
        byte[] jose = new byte[P384_BYTES * 2];
        copyUnsignedInteger(r, jose, 0);
        copyUnsignedInteger(s, jose, P384_BYTES);
        return jose;
    }

    private static int readByte(byte[] input, int[] offset) throws GeneralSecurityException {
        if (offset[0] >= input.length) throw new GeneralSecurityException("Truncated ECDSA DER");
        return input[offset[0]++] & 0xff;
    }

    private static int readLength(byte[] input, int[] offset) throws GeneralSecurityException {
        int first = readByte(input, offset);
        if (first < 0x80) return first;
        int count = first & 0x7f;
        if (count == 0 || count > 2) throw new GeneralSecurityException("Invalid ECDSA DER length");
        int length = 0;
        for (int i = 0; i < count; i++) length = (length << 8) | readByte(input, offset);
        if (length < 0x80) throw new GeneralSecurityException("Non-canonical ECDSA DER length");
        return length;
    }

    private static byte[] readInteger(byte[] input, int[] offset) throws GeneralSecurityException {
        if (readByte(input, offset) != 0x02) throw new GeneralSecurityException("Invalid ECDSA DER integer");
        int length = readLength(input, offset);
        if (length < 1 || length > P384_BYTES + 1 || length > input.length - offset[0]) {
            throw new GeneralSecurityException("Invalid ECDSA DER integer length");
        }
        byte[] value = Arrays.copyOfRange(input, offset[0], offset[0] + length);
        offset[0] += length;
        if ((value[0] & 0x80) != 0) throw new GeneralSecurityException("Negative ECDSA integer");
        int start = value.length > 1 && value[0] == 0 ? 1 : 0;
        if (value.length - start > P384_BYTES) throw new GeneralSecurityException("ECDSA integer is too large");
        return Arrays.copyOfRange(value, start, value.length);
    }

    private static void copyUnsignedInteger(byte[] value, byte[] destination, int offset)
            throws GeneralSecurityException {
        if (value.length == 0 || value.length > P384_BYTES) {
            throw new GeneralSecurityException("ECDSA integer has an invalid size");
        }
        System.arraycopy(value, 0, destination, offset + P384_BYTES - value.length, value.length);
    }

    public static final class Result {
        private final String token;
        private final byte[] encryptionKey;
        private final byte[] salt;

        private Result(String token, byte[] encryptionKey, byte[] salt) {
            this.token = Objects.requireNonNull(token, "token");
            this.encryptionKey = encryptionKey.clone();
            this.salt = salt.clone();
        }

        public String token() {
            return token;
        }

        public byte[] encryptionKey() {
            return encryptionKey.clone();
        }

        public byte[] salt() {
            return salt.clone();
        }


    }
}
