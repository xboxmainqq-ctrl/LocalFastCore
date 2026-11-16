package ru.nexora.network.nethernet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class DefaultNetherNetIdentityServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SDP = "v=0\r\n"
            + "o=- 1 1 IN IP4 127.0.0.1\r\n"
            + "s=-\r\n"
            + "t=0 0\r\n"
            + "a=fingerprint:sha-256 11:22:33:44:55:66:77:88\r\n"
            + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
            + "a=sctp-port:5000\r\n";

    @TempDir
    Path tempDirectory;

    @Test
    void createsStableServerIdentityAndSignsJwtAndFingerprints() throws Exception {
        DefaultNetherNetIdentityService first =
                new DefaultNetherNetIdentityService(tempDirectory);
        String answer = first.appendServerIdentityAssertion(SDP);
        assertTrue(answer.contains("a=fingerprint:sha-256"));
        assertTrue(answer.contains("\r\na=identity:"));
        assertTrue(answer.contains("\r\nm=application"));
        assertTrue(answer.endsWith("\r\n"));

        JsonNode envelope = readEnvelope(answer);
        assertEquals("self", envelope.path("idp").path("domain").asText());
        assertEquals("default", envelope.path("idp").path("protocol").asText());
        JsonNode assertion = MAPPER.readTree(envelope.path("assertion").asText());
        String token = assertion.path("token").asText();
        String detached = assertion.path("fingerprints").asText();

        String[] tokenParts = token.split("\\.", -1);
        assertEquals(3, tokenParts.length);
        JsonNode tokenClaims = MAPPER.readTree(Base64.getUrlDecoder().decode(tokenParts[1]));
        JsonNode cpk = tokenClaims.path("cpk");
        assertEquals("EC", cpk.path("kty").asText());
        assertEquals("P-384", cpk.path("crv").asText());
        ECPublicKey publicKey = publicKey(cpk);
        verifyJws(tokenParts, publicKey);

        String[] fingerprintParts = detached.split("\\.", -1);
        assertEquals(3, fingerprintParts.length);
        assertEquals("", fingerprintParts[1]);
        String canonical = "{\"fingerprint\":[{\"algorithm\":\"sha-256\",\"digest\":\"11:22:33:44:55:66:77:88\"}]}";
        verifyDetached(fingerprintParts, canonical, publicKey);

        DefaultNetherNetIdentityService second =
                new DefaultNetherNetIdentityService(tempDirectory);
        JsonNode secondEnvelope = readEnvelope(second.appendServerIdentityAssertion(SDP));
        JsonNode secondAssertion = MAPPER.readTree(secondEnvelope.path("assertion").asText());
        JsonNode secondClaims = MAPPER.readTree(
                Base64.getUrlDecoder().decode(secondAssertion.path("token").asText().split("\\.", -1)[1]));
        assertEquals(cpk.path("x").asText(), secondClaims.path("cpk").path("x").asText());
        assertEquals(cpk.path("y").asText(), secondClaims.path("cpk").path("y").asText());
    }

    @Test
    void refusesToAppendDuplicateIdentity() throws Exception {
        DefaultNetherNetIdentityService service =
                new DefaultNetherNetIdentityService(tempDirectory);
        String alreadySigned = service.appendServerIdentityAssertion(SDP);
        assertThrows(SecurityException.class,
                () -> service.appendServerIdentityAssertion(alreadySigned));
    }

    private static JsonNode readEnvelope(String sdp) throws Exception {
        String identity = sdp.lines()
                .filter(line -> line.startsWith("a=identity:"))
                .map(line -> line.substring("a=identity:".length()))
                .findFirst().orElseThrow();
        return MAPPER.readTree(Base64.getDecoder().decode(identity));
    }

    private static ECPublicKey publicKey(JsonNode jwk) throws Exception {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp384r1"));
        ECParameterSpec curve = parameters.getParameterSpec(ECParameterSpec.class);
        var point = new java.security.spec.ECPoint(
                new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("x").asText())),
                new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("y").asText())));
        return (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(point, curve));
    }

    private static void verifyJws(String[] parts, ECPublicKey key) throws Exception {
        JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertEquals("ES384", header.path("alg").asText());
        Signature verifier = Signature.getInstance("SHA384withECDSA");
        verifier.initVerify(key);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(joseToDer(Base64.getUrlDecoder().decode(parts[2]))));
    }

    private static void verifyDetached(String[] parts, String payload, ECPublicKey key) throws Exception {
        JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertEquals("ES384", header.path("alg").asText());
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Signature verifier = Signature.getInstance("SHA384withECDSA");
        verifier.initVerify(key);
        verifier.update((parts[0] + "." + encodedPayload).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(joseToDer(Base64.getUrlDecoder().decode(parts[2]))));
    }

    private static byte[] joseToDer(byte[] jose) {
        assertEquals(96, jose.length);
        byte[] r = derInteger(jose, 0);
        byte[] s = derInteger(jose, 48);
        int bodyLength = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + bodyLength];
        int offset = 0;
        der[offset++] = 0x30;
        der[offset++] = (byte) bodyLength;
        der[offset++] = 0x02;
        der[offset++] = (byte) r.length;
        System.arraycopy(r, 0, der, offset, r.length);
        offset += r.length;
        der[offset++] = 0x02;
        der[offset++] = (byte) s.length;
        System.arraycopy(s, 0, der, offset, s.length);
        return der;
    }

    private static byte[] derInteger(byte[] jose, int offset) {
        int first = offset;
        int end = offset + 48;
        while (first < end - 1 && jose[first] == 0) first++;
        int length = end - first;
        boolean needsZero = (jose[first] & 0x80) != 0;
        byte[] result = new byte[length + (needsZero ? 1 : 0)];
        System.arraycopy(jose, first, result, result.length - length, length);
        return result;
    }
}
