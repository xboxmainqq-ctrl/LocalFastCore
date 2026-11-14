package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetIdentityAssertionTest {
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private static String b64(String value) {
        return URL.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String jwt(String claims) {
        return b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "."
                + b64(claims) + "." + URL.encodeToString(new byte[] {1, 2, 3});
    }

    private static String detachedJws() {
        return b64("{\"alg\":\"ES384\"}") + ".."
                + URL.encodeToString(new byte[96]);
    }

    private static NetherNetSdpDocument document(String domain, String protocol,
                                                  String token, String fingerprints) {
        String assertion = "{\"token\":\"" + token + "\",\"fingerprints\":\""
                + fingerprints + "\"}";
        String envelope = "{\"idp\":{\"domain\":\"" + domain
                + "\",\"protocol\":\"" + protocol + "\"},\"assertion\":\""
                + assertion.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"}";
        String identity = Base64.getEncoder().encodeToString(
                envelope.getBytes(StandardCharsets.UTF_8));
        String sdp = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n"
                + "a=fingerprint:sha-256 AA:BB\r\n"
                + "a=identity:" + identity + "\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";
        return NetherNetSdpDocument.parseOffer(sdp);
    }

    @Test
    void parsesIdentityEnvelopeAndCompactJwtAndDetachedJwsShapes() {
        String token = jwt("{\"xuid\":\"123\",\"cpk\":{\"kty\":\"EC\",\"crv\":\"P-384\"}}");

        NetherNetIdentityAssertion parsed = NetherNetIdentityAssertion.parse(
                document("auth.minecraftservices.com", "default", token, detachedJws()));

        assertEquals("auth.minecraftservices.com", parsed.identityProviderDomain());
        assertEquals("default", parsed.identityProviderProtocol());
        assertEquals(token, parsed.token());
        assertEquals("ES384", parsed.jwsAlgorithm());
        assertTrue(parsed.detachedFingerprintsJws().contains(".."));
    }

    @Test
    void rejectsUnsupportedIdentityProviderProtocol() {
        String token = jwt("{\"cpk\":{\"kty\":\"EC\"}}");
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetIdentityAssertion.parse(
                        document("auth.example", "other", token, detachedJws())));
    }

    @Test
    void rejectsJwtWithoutCpkOrWithNoneAlgorithm() {
        String noCpk = jwt("{\"xuid\":\"123\"}");
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetIdentityAssertion.parse(
                        document("auth.example", "default", noCpk, detachedJws())));

        String none = b64("{\"alg\":\"none\"}") + "."
                + b64("{\"cpk\":{\"kty\":\"EC\"}}") + "."
                + URL.encodeToString(new byte[] {1});
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetIdentityAssertion.parse(
                        document("auth.example", "default", none, detachedJws())));
    }

    @Test
    void rejectsMalformedDetachedJwsAndOversizedFields() {
        String token = jwt("{\"cpk\":{\"kty\":\"EC\"}}");
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetIdentityAssertion.parse(
                        document("auth.example", "default", token, "a.b.c")));
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetIdentityAssertion.parse(
                        document("auth.example", "default", token, detachedJws()), 8, 8));
    }

    @Test
    void rejectsMalformedNestedJsonAndDuplicateKeys() {
        String identityAssertion = "{\"token\":\"bad\",\"token\":\"duplicate\",\"fingerprints\":\"x\"}";
        String envelope = "{\"idp\":{\"domain\":\"auth.example\",\"protocol\":\"default\"},"
                + "\"assertion\":\"" + identityAssertion.replace("\"", "\\\"") + "\"}";
        String encoded = Base64.getEncoder().encodeToString(envelope.getBytes(StandardCharsets.UTF_8));
        String sdp = "v=0\na=fingerprint:sha-256 AA:BB\na=identity:" + encoded
                + "\nm=application 9 x";
        NetherNetSdpDocument parsed = NetherNetSdpDocument.parseOffer(sdp);
        assertThrows(IllegalArgumentException.class, () -> NetherNetIdentityAssertion.parse(parsed));
    }
}
