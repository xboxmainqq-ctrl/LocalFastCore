package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import javax.crypto.KeyAgreement;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class BedrockServerEncryptionHandshakeTest {
    @Test
    void createsEs384TokenAndDerivesSameSessionKeyAsClient() throws Exception {
        KeyPair server = p384();
        KeyPair client = p384();

        BedrockServerEncryptionHandshake.Result result =
                BedrockServerEncryptionHandshake.create(server, (java.security.interfaces.ECPublicKey) client.getPublic());

        String[] jwt = result.token().split("\\.", -1);
        assertEquals(3, jwt.length);
        assertEquals(96, Base64.getUrlDecoder().decode(jwt[2]).length);

        String header = new String(Base64.getUrlDecoder().decode(jwt[0]), StandardCharsets.UTF_8);
        String payload = new String(Base64.getUrlDecoder().decode(jwt[1]), StandardCharsets.UTF_8);
        assertTrue(header.contains("\"alg\":\"ES384\""));
        assertTrue(header.contains(Base64.getEncoder().encodeToString(server.getPublic().getEncoded())));
        assertTrue(payload.contains(Base64.getEncoder().encodeToString(result.salt())));

        Signature verifier = Signature.getInstance("SHA384withECDSA");
        verifier.initVerify(server.getPublic());
        verifier.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(joseToDer(Base64.getUrlDecoder().decode(jwt[2]))));

        KeyAgreement clientAgreement = KeyAgreement.getInstance("ECDH");
        clientAgreement.init(client.getPrivate());
        clientAgreement.doPhase(server.getPublic(), true);
        byte[] clientSecret = clientAgreement.generateSecret();
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        sha256.update(result.salt());
        byte[] expectedKey = sha256.digest(clientSecret);
        assertArrayEquals(expectedKey, result.encryptionKey());
        assertEquals(32, result.encryptionKey().length);
    }

    @Test
    void rejectsNonP384ClientKeys() throws Exception {
        KeyPair server = p384();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair client = generator.generateKeyPair();

        assertThrows(IllegalArgumentException.class, () ->
                BedrockServerEncryptionHandshake.create(server,
                        (java.security.interfaces.ECPublicKey) client.getPublic()));
    }

    private static KeyPair p384() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        return generator.generateKeyPair();
    }

    private static byte[] joseToDer(byte[] jose) {
        assertEquals(96, jose.length);
        byte[] r = unsignedInteger(Arrays.copyOfRange(jose, 0, 48));
        byte[] s = unsignedInteger(Arrays.copyOfRange(jose, 48, 96));
        int contentLength = 2 + r.length + 2 + s.length;
        byte[] result = new byte[2 + contentLength];
        int offset = 0;
        result[offset++] = 0x30;
        result[offset++] = (byte) contentLength;
        result[offset++] = 0x02;
        result[offset++] = (byte) r.length;
        System.arraycopy(r, 0, result, offset, r.length);
        offset += r.length;
        result[offset++] = 0x02;
        result[offset++] = (byte) s.length;
        System.arraycopy(s, 0, result, offset, s.length);
        return result;
    }

    private static byte[] unsignedInteger(byte[] value) {
        int first = 0;
        while (first < value.length - 1 && value[first] == 0) first++;
        boolean prefix = (value[first] & 0x80) != 0;
        byte[] result = new byte[value.length - first + (prefix ? 1 : 0)];
        System.arraycopy(value, first, result, prefix ? 1 : 0, value.length - first);
        return result;
    }
}
