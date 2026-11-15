package ru.nexora.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetServerIdentitySignerTest {
    private static final String FINGERPRINT =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
            + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    @Test
    void addsOperatorAssertionAndBothSignaturesVerify() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair operator = generator.generateKeyPair();
        String answer = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
                + "a=fingerprint:sha-256 " + FINGERPRINT + "\r\n";
        String signed = NetherNetServerIdentitySigner.addIdentity(answer, "nexora.example",
                operator, Instant.ofEpochSecond(1000), Instant.ofEpochSecond(5000));

        assertTrue(signed.indexOf("a=identity:") > signed.indexOf("a=fingerprint:"));
        assertTrue(signed.indexOf("a=identity:") < signed.indexOf("m=application"));
        NetherNetSdpDocument parsed = NetherNetSdpDocument.parseOffer(signed);
        String envelope = parsed.identityEnvelopeJson();
        assertTrue(envelope.contains("\"protocol\":\"default\""));
        assertTrue(envelope.contains("\\\"protocol\\\":\\\"default\\\""));

        Map<String, String> assertion = readStringObject(envelope, "assertion");
        Map<String, String> inner = readStringObject(assertion.get("assertion"), "token", "fingerprints");
        String token = inner.get("token");
        String[] jwt = token.split("\\.", -1);
        assertEquals(3, jwt.length);
        String jwtClaims = new String(Base64.getUrlDecoder().decode(jwt[1]), StandardCharsets.UTF_8);
        assertTrue(jwtClaims.contains("\"crv\":\"P-384\""));
        Signature jwtVerifier = Signature.getInstance("SHA384withECDSA");
        jwtVerifier.initVerify(operator.getPublic());
        jwtVerifier.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(jwtVerifier.verify(rawToDer(Base64.getUrlDecoder().decode(jwt[2]))));

        NetherNetFingerprintPayload payload = NetherNetFingerprintPayload.fromSdp(parsed);
        assertTrue(NetherNetFingerprintJwsVerifier.verify(
                inner.get("fingerprints"), payload, operator.getPublic()));

        NetherNetServerIdentityVerifier identityVerifier = new NetherNetServerIdentityVerifier(
                java.time.Clock.fixed(Instant.ofEpochSecond(2000), java.time.ZoneOffset.UTC), 0L);
        NetherNetServerIdentityVerifier.VerifiedOperatorIdentity firstUse =
                identityVerifier.verify(signed, false, fingerprint -> false);
        assertEquals(NetherNetServerIdentityVerifier.TrustStatus.FIRST_USE_CONFIRMATION_REQUIRED,
                firstUse.trustStatus());
        assertEquals(64, firstUse.sha256PublicKeyFingerprint().length());
        NetherNetServerIdentityVerifier.VerifiedOperatorIdentity pinned =
                identityVerifier.verify(signed, false, fingerprint -> true);
        assertEquals(NetherNetServerIdentityVerifier.TrustStatus.PINNED, pinned.trustStatus());
        NetherNetServerIdentityVerifier.VerifiedOperatorIdentity tls =
                identityVerifier.verify(signed, true, fingerprint -> false);
        assertEquals(NetherNetServerIdentityVerifier.TrustStatus.TLS_AUTHENTICATED,
                tls.trustStatus());
    }

    @Test
    void rejectsExistingIdentityMissingSessionFingerprintAndInvalidLifetime() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair operator = generator.generateKeyPair();
        String noFingerprint = "v=0\nm=application 9 x";
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetServerIdentitySigner.addIdentity(noFingerprint, "nexora.example",
                        operator, Instant.ofEpochSecond(1), Instant.ofEpochSecond(2)));

        String existing = "v=0\na=fingerprint:sha-256 " + FINGERPRINT
                + "\na=identity:already\nm=application 9 x";
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetServerIdentitySigner.addIdentity(existing, "nexora.example",
                        operator, Instant.ofEpochSecond(1), Instant.ofEpochSecond(2)));

        String answer = "v=0\na=fingerprint:sha-256 " + FINGERPRINT + "\nm=application 9 x";
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetServerIdentitySigner.addIdentity(answer, "nexora.example",
                        operator, Instant.ofEpochSecond(2), Instant.ofEpochSecond(1)));
    }

    private static Map<String, String> readStringObject(String json, String... wanted)
            throws Exception {
        java.util.HashMap<String, String> result = new java.util.HashMap<>();
        JsonFactory factory = JsonFactory.builder().build();
        try (JsonParser parser = factory.createParser(json)) {
            assertEquals(JsonToken.START_OBJECT, parser.nextToken());
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                boolean capture = java.util.Arrays.asList(wanted).contains(name);
                if (capture) {
                    assertEquals(JsonToken.VALUE_STRING, value);
                    result.put(name, parser.getText());
                } else {
                    parser.skipChildren();
                }
            }
        }
        return result;
    }

    private static byte[] rawToDer(byte[] raw) {
        int half = raw.length / 2;
        byte[] r = derInteger(java.util.Arrays.copyOfRange(raw, 0, half));
        byte[] s = derInteger(java.util.Arrays.copyOfRange(raw, half, raw.length));
        byte[] result = new byte[2 + r.length + s.length];
        result[0] = 0x30;
        result[1] = (byte) (r.length + s.length);
        System.arraycopy(r, 0, result, 2, r.length);
        System.arraycopy(s, 0, result, 2 + r.length, s.length);
        return result;
    }

    private static byte[] derInteger(byte[] raw) {
        int first = 0;
        while (first < raw.length - 1 && raw[first] == 0) {
            first++;
        }
        boolean needsZero = (raw[first] & 0x80) != 0;
        int length = raw.length - first + (needsZero ? 1 : 0);
        byte[] result = new byte[length + 2];
        result[0] = 0x02;
        result[1] = (byte) length;
        System.arraycopy(raw, first, result, needsZero ? 3 : 2, raw.length - first);
        return result;
    }
}
