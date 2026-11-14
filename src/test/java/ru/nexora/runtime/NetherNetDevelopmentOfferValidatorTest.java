package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetDevelopmentOfferValidatorTest {
    @Test
    void requiresExplicitEnableAndOnlyAcceptsIdentityFreeOffers() {
        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetDevelopmentOfferValidator(false));

        NetherNetDevelopmentOfferValidator validator =
                new NetherNetDevelopmentOfferValidator(true, id -> id.startsWith("test-"));
        assertFalse(validator.requiresIdentity());

        String offer = "v=0\na=fingerprint:sha-256 "
                + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
                + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel";
        NetherNetSdpDocument parsed = NetherNetSdpDocument.parseOffer(
                offer, 256 * 1024, 16 * 1024, validator.requiresIdentity());

        assertDoesNotThrow(() -> validator.validate("test-client", parsed));
        assertThrows(SecurityException.class, () -> validator.validate("other-client", parsed));
    }
}
