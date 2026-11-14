package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetIdentityAssertionTest {
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final String ISSUER = "https://authorization.franchise.minecraft-services.net/";
    private static final String AUDIENCE = "api://auth-minecraft-services/multiplayer";
    private static final String CPK = Base64.getEncoder().encodeToString(new byte[91]);

    private static String b64(String value) {
        return URL.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String jwt(String algorithm, boolean includeCpk) {
        String header = "{\"alg\":\"" + algorithm + "\",\"typ\":\"JWT\",\"kid\":\"test-key\"}";
        String claims = "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE
                + "\",\"exp\":9999999999,\"nbf\":1,\"xid\":\"123\",\"xname\":\"Player\""
                + (includeCpk ? ",\"cpk\":\"" + CPK + "\"" : "") + "}";
        return b64(header) + "." + b64(claims) + "." + URL.encodeToString(new byte[] {1, 2, 3});
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
    void parsesCurrentGameServerTokenClaimsAndDetachedJwsShape() {
        String token = jwt("RS256", true);

        NetherNetIdentityAssertion parsed = NetherNetIdentityAssertion.parse(
                document("authorization.franchise.minecraft-services.net", "default",
                        token, detachedJws()));

        assertEquals("authorization.franchise.minecraft-services.net", parsed.identityProviderDomain());
        assertEquals("default", parsed.identityProviderProtocol());
        assertEquals(token, parsed.token());
        assertEquals("test-key", parsed.jwtKeyId());
        assertEquals("RS256", parsed.jwtAlgorithm());
        assertEquals(CPK, parsed.clientPublicKeySpkiBase64());
        assertEquals(ISSUER, parsed.issuer());
        assertEquals(AUDIENCE, parsed.audience());
        assertEquals(9999999999L, parsed.expiresAtEpochSeconds());
        assertEquals(1L, parsed.notBeforeEpochSeconds());
        assertEquals("123", parsed.xuid());
        assertEquals("Player", parsed.displayName());
        assertEquals("ES384", parsed.jwsAlgorithm());
    }

    @Test
    void rejectsUnsupportedIdentityProviderProtocol() {
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetIdentityAssertion.parse(
                        document("auth.example", "other", jwt("RS256", true), detachedJws())));
    }

    @Test
    void rejectsJwtWithoutCpkOrWithUnsupportedAlgorithm() {
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetIdentityAssertion.parse(
                        document("auth.example", "default", jwt("RS256", false), detachedJws())));
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetIdentityAssertion.parse(
                        document("auth.example", "default", jwt("none", true), detachedJws())));
    }

    @Test
    void rejectsMalformedDetachedJwsAndOversizedFields() {
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetIdentityAssertion.parse(
                        document("auth.example", "default", jwt("RS256", true), "a.b.c")));
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetIdentityAssertion.parse(
                        document("auth.example", "default", jwt("RS256", true), detachedJws()), 8, 8));
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

    @Test
    void rejectsWrongIssuerAndAudience() {
        String token = b64("{\"alg\":\"RS256\",\"kid\":\"k\"}") + "."
                + b64("{\"iss\":\"https://wrong.example/\",\"aud\":\"" + AUDIENCE
                + "\",\"exp\":9999999999,\"cpk\":\"" + CPK + "\"}") + "."
                + URL.encodeToString(new byte[] {1});
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetIdentityAssertion.parse(
                        document("auth.example", "default", token, detachedJws())));
    }
}
