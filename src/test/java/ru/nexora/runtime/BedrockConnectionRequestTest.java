package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockConnectionRequestTest {
    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2ln";

    @Test
    void extractsChainAndClientDataWithoutClaimingAuthentication() {
        BedrockConnectionRequest request = BedrockConnectionRequest.parse(
                "{\"chain\":[\"" + JWT + "\"],\"clientData\":\"" + JWT + "\"}");

        assertEquals(List.of(JWT), request.chain());
        assertEquals(JWT, request.clientDataJwt());
        assertThrows(UnsupportedOperationException.class, () -> request.chain().add(JWT));
    }

    @Test
    void extractsModernAuthenticationEnvelopeWithoutTrustingIt() {
        BedrockConnectionRequest request = BedrockConnectionRequest.parse(
                "{\"AuthenticationType\":0,\"Token\":\"" + JWT
                        + "\",\"Certificate\":\"\"}");

        assertTrue(request.isAuthenticationEnvelope());
        assertTrue(request.isTokenPayload());
        assertFalse(request.isCertificateChainPayload());
        assertEquals(0, request.authenticationType());
        assertEquals(JWT, request.token());
        assertEquals("", request.certificate());
        assertTrue(request.chain().isEmpty());
        assertNull(request.clientDataJwt());
    }


    @Test
    void extractsModernCertificateChainPayload() {
        String certificate = "{\\\"chain\\\":[\\\"" + JWT + "\\"]}";
        BedrockConnectionRequest request = BedrockConnectionRequest.parse(
                "{\"AuthenticationType\":2,\"Token\":\"\",\"Certificate\":\""
                        + certificate + "\"}");

        assertTrue(request.isCertificateChainPayload());
        assertFalse(request.isTokenPayload());
        assertEquals(List.of(JWT), request.chain());
        assertEquals("", request.token());
        assertEquals(certificate, request.certificate());
    }

    @Test
    void rejectsIncompleteOrMixedAuthenticationEnvelopes() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockConnectionRequest.parse(
                        "{\"AuthenticationType\":0,\"Token\":\"" + JWT + "\"}"));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockConnectionRequest.parse(
                        "{\"chain\":[\"" + JWT + "\"],\"clientData\":\"" + JWT
                                + "\",\"AuthenticationType\":0,\"Token\":\"" + JWT
                                + "\",\"Certificate\":\"\"}"));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockConnectionRequest.parse(
                        "{\"AuthenticationType\":3,\"Token\":\"" + JWT
                                + "\",\"Certificate\":\"\"}"));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockConnectionRequest.parse(
                        "{\"AuthenticationType\":0,\"Token\":\"\",\"Certificate\":\"\"}"));
    }

    @Test
    void acceptsMaximumThreeChainTokens() {
        BedrockConnectionRequest request = BedrockConnectionRequest.parse(
                "{\"chain\":[\"" + JWT + "\",\"" + JWT + "\",\"" + JWT
                        + "\"],\"clientData\":\"" + JWT + "\"}");
        assertEquals(3, request.chain().size());
    }

    @Test
    void rejectsMissingFieldsAndWrongTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockConnectionRequest.parse("{\"chain\":[\"" + JWT + "\"]}"));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockConnectionRequest.parse("{\"chain\":\"not-an-array\",\"clientData\":\"" + JWT + "\"}"));
    }

    @Test
    void rejectsDuplicateKeysAndTrailingJson() {
        String valid = "\"chain\":[\"" + JWT + "\"],\"clientData\":\"" + JWT + "\"";
        assertThrows(IllegalArgumentException.class,
                () -> BedrockConnectionRequest.parse("{" + valid + ",\"clientData\":\"" + JWT + "\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockConnectionRequest.parse("{" + valid + "} {}"));
    }

    @Test
    void rejectsInvalidJwtShapeAndExcessiveNesting() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockConnectionRequest.parse("{\"chain\":[\"not-a-jwt\"],\"clientData\":\"" + JWT + "\"}"));
        String nested = "{\"chain\":[\"" + JWT + "\"],\"clientData\":\"" + JWT
                + "\",\"extra\":" + "[".repeat(66) + "0" + "]".repeat(66) + "}";
        assertThrows(IllegalArgumentException.class, () -> BedrockConnectionRequest.parse(nested));
    }
}
