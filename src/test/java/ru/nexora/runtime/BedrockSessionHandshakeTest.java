package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSessionHandshakeTest {
    @Test
    void followsRequestSettingsLoginAuthenticationSequence() {
        BedrockSessionHandshake handshake = new BedrockSessionHandshake();

        assertEquals(BedrockHandshakeState.AWAITING_NETWORK_SETTINGS_REQUEST, handshake.state());
        assertEquals(BedrockHandshakeResult.ACCEPTED,
                handshake.receiveNetworkSettingsRequest(
                        new BedrockRequestNetworkSettingsPacket(2225)));
        assertEquals(BedrockHandshakeState.NETWORK_SETTINGS_PENDING, handshake.state());
        assertEquals(2225, handshake.clientProtocolVersion());

        assertEquals(BedrockHandshakeResult.ACCEPTED, handshake.markNetworkSettingsSent());
        assertEquals(BedrockHandshakeState.AWAITING_LOGIN, handshake.state());

        assertEquals(BedrockHandshakeResult.ACCEPTED,
                handshake.receiveLogin(new BedrockLoginPacket(2225, "opaque-request")));
        assertEquals(BedrockHandshakeState.AUTHENTICATION_PENDING, handshake.state());

        assertEquals(BedrockHandshakeResult.ACCEPTED, handshake.markAuthenticated());
        assertEquals(BedrockHandshakeState.PLAY, handshake.state());
    }

    @Test
    void rejectsUnsupportedProtocolAndClosesHandshake() {
        BedrockSessionHandshake handshake = new BedrockSessionHandshake();

        assertEquals(BedrockHandshakeResult.UNSUPPORTED_PROTOCOL,
                handshake.receiveNetworkSettingsRequest(
                        new BedrockRequestNetworkSettingsPacket(2224)));
        assertEquals(BedrockHandshakeState.CLOSED, handshake.state());
        assertEquals(BedrockHandshakeResult.SESSION_CLOSED,
                handshake.markNetworkSettingsSent());
    }

    @Test
    void rejectsOutOfOrderTransitionsWithoutAdvancingState() {
        BedrockSessionHandshake handshake = new BedrockSessionHandshake();

        assertEquals(BedrockHandshakeResult.INVALID_STATE, handshake.markNetworkSettingsSent());
        assertEquals(BedrockHandshakeResult.INVALID_STATE,
                handshake.receiveLogin(new BedrockLoginPacket(2225, "opaque-request")));
        assertEquals(BedrockHandshakeResult.INVALID_STATE, handshake.markAuthenticated());
        assertEquals(BedrockHandshakeState.AWAITING_NETWORK_SETTINGS_REQUEST, handshake.state());
    }

    @Test
    void rejectsLoginWithDifferentOrUnsupportedProtocol() {
        BedrockSessionHandshake mismatch = acceptedThroughSettings();
        assertEquals(BedrockHandshakeResult.LOGIN_PROTOCOL_MISMATCH,
                mismatch.receiveLogin(new BedrockLoginPacket(2224, "opaque-request")));
        assertEquals(BedrockHandshakeState.CLOSED, mismatch.state());

        BedrockSessionHandshake unsupported = acceptedThroughSettings();
        assertEquals(BedrockHandshakeResult.LOGIN_PROTOCOL_MISMATCH,
                unsupported.receiveLogin(new BedrockLoginPacket(2226, "opaque-request")));
        assertEquals(BedrockHandshakeState.CLOSED, unsupported.state());
    }

    @Test
    void rejectsRepeatedInitialRequest() {
        BedrockSessionHandshake handshake = new BedrockSessionHandshake();
        assertEquals(BedrockHandshakeResult.ACCEPTED,
                handshake.receiveNetworkSettingsRequest(
                        new BedrockRequestNetworkSettingsPacket(2225)));

        assertEquals(BedrockHandshakeResult.INVALID_STATE,
                handshake.receiveNetworkSettingsRequest(
                        new BedrockRequestNetworkSettingsPacket(2225)));
        assertEquals(BedrockHandshakeState.NETWORK_SETTINGS_PENDING, handshake.state());
    }

    @Test
    void authenticationCannotBeSkippedAndCloseIsTerminal() {
        BedrockSessionHandshake handshake = acceptedThroughSettings();
        assertEquals(BedrockHandshakeResult.ACCEPTED,
                handshake.receiveLogin(new BedrockLoginPacket(2225, "opaque-request")));

        assertEquals(BedrockHandshakeResult.ACCEPTED, handshake.markAuthenticated());
        handshake.close();

        assertEquals(BedrockHandshakeState.CLOSED, handshake.state());
        assertEquals(BedrockHandshakeResult.SESSION_CLOSED,
                handshake.receiveLogin(new BedrockLoginPacket(2225, "another-request")));
    }

    private static BedrockSessionHandshake acceptedThroughSettings() {
        BedrockSessionHandshake handshake = new BedrockSessionHandshake();
        assertEquals(BedrockHandshakeResult.ACCEPTED,
                handshake.receiveNetworkSettingsRequest(
                        new BedrockRequestNetworkSettingsPacket(2225)));
        assertEquals(BedrockHandshakeResult.ACCEPTED, handshake.markNetworkSettingsSent());
        return handshake;
    }
}
