package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetFingerprintJwsVerifierTest {
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final String DIGEST_A =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
            + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";
    private static final String DIGEST_B =
            "11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:"
            + "11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00";

    @Test
    void canonicalizesSdpFingerprintAndVerifiesEs384DetachedSignature() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair keyPair = generator.generateKeyPair();

        NetherNetFingerprintPayload payload = new NetherNetFingerprintPayload(
                List.of(new NetherNetFingerprintPayload.Fingerprint("sha-256", DIGEST_A)));

        String protectedHeader = URL.encodeToString(
                "{\"alg\":\"ES384\"}".getBytes(StandardCharsets.UTF_8));
        String signingInput = protectedHeader + "."
                + URL.encodeToString(payload.canonicalJsonBytes());
        Signature signer = Signature.getInstance("SHA384withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        byte[] der = signer.sign();
        byte[] raw = derToRaw(der, 48);
        String jws = protectedHeader + ".." + URL.encodeToString(raw);

        assertTrue(NetherNetFingerprintJwsVerifier.verify(jws, payload, keyPair.getPublic()));

        NetherNetFingerprintPayload tampered = new NetherNetFingerprintPayload(
                List.of(new NetherNetFingerprintPayload.Fingerprint("sha-256", DIGEST_B)));
        assertFalse(NetherNetFingerprintJwsVerifier.verify(jws, tampered, keyPair.getPublic()));
    }

    @Test
    void serializesFingerprintPayloadWithExactCanonicalFieldOrder() {
        NetherNetFingerprintPayload payload = new NetherNetFingerprintPayload(
                List.of(
                        new NetherNetFingerprintPayload.Fingerprint("sha-256", DIGEST_A),
                        new NetherNetFingerprintPayload.Fingerprint("sha-384",
                                "CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:"
                                + "CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:"
                                + "CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB")));

        assertEquals("{\"fingerprint\":[{\"algorithm\":\"sha-256\",\"digest\":\"" + DIGEST_A
                + "\"},{\"algorithm\":\"sha-384\",\"digest\":\""
                + "CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:"
                + "CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:"
                + "CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB"
                + "\"}]}",
                payload.canonicalJson());
    }

    @Test
    void rejectsUnsupportedFingerprintAlgorithmAndMalformedDigest() {
        String identity = Base64.getEncoder().encodeToString(
                "{\"idp\":{\"domain\":\"auth.example\",\"protocol\":\"default\"},"
                        .concat("\"assertion\":\"{}\"}").getBytes(StandardCharsets.UTF_8));
        String unsupported = "v=0\na=fingerprint:md5 AA:BB\na=identity:" + identity
                + "\nm=application 9 x";
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetFingerprintPayload.fromSdpText(unsupported));
        assertThrows(IllegalArgumentException.class, () ->
                new NetherNetFingerprintPayload.Fingerprint("sha-256", "AA:BB"));
    }

    private static byte[] derToRaw(byte[] der, int coordinateBytes) {
        int offset = 2;
        int rLength = der[offset + 1] & 0xff;
        byte[] r = java.util.Arrays.copyOfRange(der, offset + 2, offset + 2 + rLength);
        offset += 2 + rLength;
        int sLength = der[offset + 1] & 0xff;
        byte[] s = java.util.Arrays.copyOfRange(der, offset + 2, offset + 2 + sLength);
        byte[] raw = new byte[coordinateBytes * 2];
        copyInteger(r, raw, 0, coordinateBytes);
        copyInteger(s, raw, coordinateBytes, coordinateBytes);
        return raw;
    }

    private static void copyInteger(byte[] value, byte[] target, int offset, int size) {
        int sourceOffset = value.length > size && value[0] == 0 ? 1 : 0;
        int length = value.length - sourceOffset;
        System.arraycopy(value, sourceOffset, target, offset + size - length, length);
    }
}
