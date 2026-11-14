package ru.nexora.runtime;

import java.util.Objects;

/**
 * Small server-side state machine for the initial Bedrock login sequence.
 *
 * <p>This class coordinates already-decoded packets; it does not read from a
 * socket, send NetworkSettings, verify identity JWTs, or authenticate users.
 * The transport/session owner must call {@link #markNetworkSettingsSent()}
 * only after the NetworkSettings response has actually been handed off, and
 * call {@link #markAuthenticated()} only after authentication succeeds and LoginSuccess has been accepted by the transport.</p>
 *
 * <p>All methods are synchronized so a transport callback and an authentication
 * callback cannot race state transitions.</p>
 */
public final class BedrockSessionHandshake {
    private BedrockHandshakeState state =
            BedrockHandshakeState.AWAITING_NETWORK_SETTINGS_REQUEST;
    private Integer clientProtocolVersion;

    public synchronized BedrockHandshakeState state() {
        return state;
    }

    /** Returns the protocol reported by the first received request, if any. */
    public synchronized Integer clientProtocolVersion() {
        return clientProtocolVersion;
    }

    /**
     * Accepts the initial RequestNetworkSettingsPacket payload.
     * Unsupported clients are rejected by closing this handshake.
     */
    public synchronized BedrockHandshakeResult receiveNetworkSettingsRequest(
            BedrockRequestNetworkSettingsPacket packet
    ) {
        Objects.requireNonNull(packet, "packet");
        if (state == BedrockHandshakeState.CLOSED) {
            return BedrockHandshakeResult.SESSION_CLOSED;
        }
        if (state != BedrockHandshakeState.AWAITING_NETWORK_SETTINGS_REQUEST) {
            return BedrockHandshakeResult.INVALID_STATE;
        }

        clientProtocolVersion = packet.clientNetworkVersion();
        if (!packet.isSupportedProtocol()) {
            state = BedrockHandshakeState.CLOSED;
            return BedrockHandshakeResult.UNSUPPORTED_PROTOCOL;
        }

        state = BedrockHandshakeState.NETWORK_SETTINGS_PENDING;
        return BedrockHandshakeResult.ACCEPTED;
    }

    /**
     * Records that the server's NetworkSettings response was successfully
     * handed to the transport. Merely constructing or queueing it elsewhere
     * must not advance this state.
     */
    public synchronized BedrockHandshakeResult markNetworkSettingsSent() {
        if (state == BedrockHandshakeState.CLOSED) {
            return BedrockHandshakeResult.SESSION_CLOSED;
        }
        if (state != BedrockHandshakeState.NETWORK_SETTINGS_PENDING) {
            return BedrockHandshakeResult.INVALID_STATE;
        }

        state = BedrockHandshakeState.AWAITING_LOGIN;
        return BedrockHandshakeResult.ACCEPTED;
    }

    /** Accepts Login only after NetworkSettings has been sent. */
    public synchronized BedrockHandshakeResult receiveLogin(BedrockLoginPacket packet) {
        Objects.requireNonNull(packet, "packet");
        if (state == BedrockHandshakeState.CLOSED) {
            return BedrockHandshakeResult.SESSION_CLOSED;
        }
        if (state != BedrockHandshakeState.AWAITING_LOGIN) {
            return BedrockHandshakeResult.INVALID_STATE;
        }

        if (!packet.isSupportedProtocol()
                || clientProtocolVersion == null
                || packet.protocolVersion() != clientProtocolVersion) {
            state = BedrockHandshakeState.CLOSED;
            return BedrockHandshakeResult.LOGIN_PROTOCOL_MISMATCH;
        }

        state = BedrockHandshakeState.AUTHENTICATION_PENDING;
        return BedrockHandshakeResult.ACCEPTED;
    }

    /**
     * Records that authentication succeeded and LoginSuccess was sent.
     * Resource-pack negotiation and world startup still remain before PLAY.
     */
    public synchronized BedrockHandshakeResult markAuthenticated() {
        if (state == BedrockHandshakeState.CLOSED) {
            return BedrockHandshakeResult.SESSION_CLOSED;
        }
        if (state != BedrockHandshakeState.AUTHENTICATION_PENDING) {
            return BedrockHandshakeResult.INVALID_STATE;
        }

        state = BedrockHandshakeState.LOGIN_SUCCESS_SENT;
        return BedrockHandshakeResult.ACCEPTED;
    }

    /**
     * Moves to PLAY only after resource-pack negotiation and the StartGame/world
     * initialization sequence have completed and the host has sent its startup data.
     */
    public synchronized BedrockHandshakeResult markPlayReady() {
        if (state == BedrockHandshakeState.CLOSED) {
            return BedrockHandshakeResult.SESSION_CLOSED;
        }
        if (state != BedrockHandshakeState.LOGIN_SUCCESS_SENT) {
            return BedrockHandshakeResult.INVALID_STATE;
        }
        state = BedrockHandshakeState.PLAY;
        return BedrockHandshakeResult.ACCEPTED;
    }

    /** Closes the handshake; all later packet transitions are rejected. */
    public synchronized void close() {
        state = BedrockHandshakeState.CLOSED;
    }
}
