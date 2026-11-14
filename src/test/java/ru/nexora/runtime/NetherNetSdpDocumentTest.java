package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetSdpDocumentTest {
    private static final String ENVELOPE = "{\"idp\":{\"domain\":\"example.test\",\"protocol\":\"default\"},\"assertion\":\"opaque\"}";
    private static final String IDENTITY = Base64.getEncoder()
            .encodeToString(ENVELOPE.getBytes(StandardCharsets.UTF_8));

    @Test
    void extractsIdentityAndRemovesOnlyThatLineForWebRtc() {
        String sdp = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n"
                + "a=fingerprint:sha-256 AA:BB\r\n"
                + "a=identity:" + IDENTITY + "\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
                + "a=sctp-port:5000\r\n";

        NetherNetSdpDocument parsed = NetherNetSdpDocument.parseOffer(sdp);

        assertEquals(sdp, parsed.originalSdp());
        assertEquals(ENVELOPE, parsed.identityEnvelopeJson());
        assertEquals(1, parsed.fingerprintLines().size());
        assertFalse(parsed.webRtcSdp().contains("a=identity:"));
        assertTrue(parsed.webRtcSdp().contains("a=fingerprint:sha-256 AA:BB\r\n"));
        assertTrue(parsed.webRtcSdp().contains("m=application"));
    }

    @Test
    void rejectsMissingDuplicateOrMisplacedIdentity() {
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetSdpDocument.parseOffer("v=0\na=fingerprint:sha-256 AA:BB\nm=application 9 x"));
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetSdpDocument.parseOffer("v=0\na=fingerprint:sha-256 AA:BB\n"
                        + "a=identity:" + IDENTITY + "\na=identity:" + IDENTITY));
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetSdpDocument.parseOffer("v=0\nm=application 9 x\na=identity:" + IDENTITY));
    }

    @Test
    void rejectsInvalidBase64Utf8AndOversizedInput() {
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetSdpDocument.parseOffer("v=0\na=fingerprint:sha-256 AA:BB\n"
                        + "a=identity:%%%"));
        String invalidUtf8 = Base64.getEncoder().encodeToString(new byte[] {(byte) 0xC3, 0x28});
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetSdpDocument.parseOffer("v=0\na=fingerprint:sha-256 AA:BB\n"
                        + "a=identity:" + invalidUtf8));
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetSdpDocument.parseOffer("v=0", 1, 1));
    }

    @Test
    void permitsMediaLevelFingerprintsAfterSessionIdentity() {
        String sdp = "v=0\na=fingerprint:sha-256 AA:BB\na=identity:" + IDENTITY
                + "\nm=application 9 x\na=fingerprint:sha-256 CC:DD";
        NetherNetSdpDocument parsed = NetherNetSdpDocument.parseOffer(sdp);
        assertEquals(2, parsed.fingerprintLines().size());
        assertTrue(parsed.webRtcSdp().contains("a=fingerprint:sha-256 CC:DD"));
    }

    @Test
    void permitsMissingIdentityOnlyWhenExplicitlyConfigured() {
        String sdp = "v=0\na=fingerprint:sha-256 AA:BB\nm=application 9 x";
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetSdpDocument.parseOffer(sdp));
        NetherNetSdpDocument parsed = NetherNetSdpDocument.parseOffer(
                sdp, 256 * 1024, 16 * 1024, false);
        assertNull(parsed.identityEnvelopeJson());
        assertEquals(sdp, parsed.webRtcSdp());
    }

    @Test
    void rejectsIdentityWithoutFingerprint() {
        assertThrows(IllegalArgumentException.class, () ->
                NetherNetSdpDocument.parseOffer("v=0\na=identity:" + IDENTITY));
    }
}
