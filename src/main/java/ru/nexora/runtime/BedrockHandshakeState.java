package ru.nexora.runtime;

/** Server-side phases for the initial Bedrock login handshake. */
public enum BedrockHandshakeState {
    AWAITING_NETWORK_SETTINGS_REQUEST,
    NETWORK_SETTINGS_PENDING,
    AWAITING_LOGIN,
    AUTHENTICATION_PENDING,
    PLAY,
    CLOSED
}
