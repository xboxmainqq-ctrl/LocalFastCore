package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetIdentityValidatorTest {
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final String ISSUER = "https://authorization.franchise.minecraft-services.net/";
    private static final String AUDIENCE = "api://auth-minecraft-services/multiplayer";
    private static final String FINGERPRINT =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
            + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    @Test
    void acceptsOnlyWhenJwtAndFingerprintProofBothVerify() throws Exception {
        KeyPairGenerator rsaGenerator = KeyPairGenerator.getInstance("RSA");
        rsaGenerator.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
        KeyPair issuerKey = rsaGenerator.generateKeyPair();
        KeyPairGenerator ecGenerator = KeyPairGenerator.getInstance("EC");
        ecGenerator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = ecGenerator.generateKeyPair();

        String token = signedToken(issuerKey, clientKey.getPublic().getEncoded());
        String fingerprintPayload = "{\"fingerprint\":[{\"algorithm\":\"sha-256\","
                + "\"digest\":\"" + FINGERPRINT + "\"}]}";
        String jwsHeader = b64("{\"alg\":\"ES384\"}");
        String jwsInput = jwsHeader + "." + b64(fingerprintPayload);
        Signature jwsSigner = Signature.getInstance("SHA384withECDSA");
        jwsSigner.initSign(clientKey.getPrivate());
        jwsSigner.update(jwsInput.getBytes(StandardCharsets.US_ASCII));
        String detachedJws = jwsHeader + ".." + URL.encodeToString(derToRaw(jwsSigner.sign(), 48));

        String assertion = "{\"token\":\"" + token + "\",\"fingerprints\":\""
                + detachedJws + "\"}";
        String envelope = "{\"idp\":{\"domain\":\"authorization.franchise.minecraft-services.net\","
                + "\"protocol\":\"default\"},\"assertion\":\""
                + assertion.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        String identity = Base64.getEncoder().encodeToString(envelope.getBytes(StandardCharsets.UTF_8));
        String sdp = "v=0\na=fingerprint:sha-256 " + FINGERPRINT + "\na=identity:"
                + identity + "\nm=application 9 x";
        NetherNetSdpDocument offer = NetherNetSdpDocument.parseOffer(sdp);

        NetherNetGameServerTokenVerifier tokenVerifier = new NetherNetGameServerTokenVerifier(
                kid -> "issuer-key".equals(kid)
                        ? (java.security.interfaces.RSAPublicKey) issuerKey.getPublic() : null,
                Clock.fixed(Instant.ofEpochSecond(1000L), ZoneOffset.UTC), 0L);
        NetherNetIdentityValidator validator = new NetherNetIdentityValidator(tokenVerifier);

        NetherNetIdentityValidator.VerifiedIdentity identityResult = validator.validate(offer);

        assertEquals("123456789", identityResult.xuid());
        assertEquals("NexoraTester", identityResult.displayName());
        assertArrayEquals(clientKey.getPublic().getEncoded(),
                identityResult.clientPublicKey().getEncoded());
    }

    private static String signedToken(KeyPair issuerKey, byte[] cpkSpki) throws Exception {
        String header = b64("{\"alg\":\"RS256\",\"kid\":\"issuer-key\"}");
        String claims = b64("{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE
                + "\",\"exp\":5000,\"xid\":\"123456789\",\"xname\":\"NexoraTester\","
                + "\"cpk\":\"" + Base64.getEncoder().encodeToString(cpkSpki) + "\"}");
        String input = header + "." + claims;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(issuerKey.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + URL.encodeToString(signer.sign());
    }

    private static String b64(String value) {
        return URL.encodeToString(value.getBytes(StandardCharsets.UTF_8));
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
