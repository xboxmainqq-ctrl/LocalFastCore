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
