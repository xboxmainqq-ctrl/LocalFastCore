package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class BedrockClientDataJwtVerifierTest {
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final String CLIENT_DATA = "{\"ClientRandomId\":123,\"DeviceOS\":7}";

    @Test
    void verifiesEs384ClientDataWithAuthenticatedP384Key() throws Exception {
        KeyPair client = createKeyPair();
        String jwt = signedJwt(client, CLIENT_DATA, "ES384");

        assertEquals(CLIENT_DATA,
                BedrockClientDataJwtVerifier.verifyAndReadPayload(jwt,
                        (java.security.interfaces.ECPublicKey) client.getPublic()));
    }

    @Test
    void rejectsTamperedPayloadAndWrongClientKey() throws Exception {
        KeyPair client = createKeyPair();
        KeyPair other = createKeyPair();
        String jwt = signedJwt(client, CLIENT_DATA, "ES384");
        String[] parts = jwt.split("\\.", -1);
        String tampered = parts[0] + "." + URL.encodeToString(
                "{\"ClientRandomId\":999}".getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        assertThrows(SecurityException.class, () ->
                BedrockClientDataJwtVerifier.verifyAndReadPayload(tampered,
                        (java.security.interfaces.ECPublicKey) client.getPublic()));
        assertThrows(SecurityException.class, () ->
                BedrockClientDataJwtVerifier.verifyAndReadPayload(jwt,
                        (java.security.interfaces.ECPublicKey) other.getPublic()));
    }

    @Test
    void rejectsWrongAlgorithmAndMalformedPayloadJson() throws Exception {
        KeyPair client = createKeyPair();
        assertThrows(SecurityException.class, () ->
                BedrockClientDataJwtVerifier.verifyAndReadPayload(
                        signedJwt(client, CLIENT_DATA, "ES256"),
                        (java.security.interfaces.ECPublicKey) client.getPublic()));
        assertThrows(SecurityException.class, () ->
                BedrockClientDataJwtVerifier.verifyAndReadPayload(
                        signedJwt(client, "not-json", "ES384"),
                        (java.security.interfaces.ECPublicKey) client.getPublic()));
    }

    private static KeyPair createKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        return generator.generateKeyPair();
    }

    private static String signedJwt(KeyPair key, String payload, String algorithm) throws Exception {
        String header = URL.encodeToString(("{\"alg\":\"" + algorithm + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String body = URL.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String input = header + "." + body;
        Signature signer = Signature.getInstance("SHA384withECDSA");
        signer.initSign(key.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + URL.encodeToString(derToRaw(signer.sign(), 48));
    }

    private static byte[] derToRaw(byte[] der, int coordinateBytes) {
        int offset = 2;
        int rLength = der[offset + 1] & 0xff;
        byte[] r = Arrays.copyOfRange(der, offset + 2, offset + 2 + rLength);
        offset += 2 + rLength;
        int sLength = der[offset + 1] & 0xff;
        byte[] s = Arrays.copyOfRange(der, offset + 2, offset + 2 + sLength);
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
