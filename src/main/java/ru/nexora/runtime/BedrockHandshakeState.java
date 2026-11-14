package ru.nexora.runtime;

/** Server-side phases for the Bedrock login and world-start sequence. */
public enum BedrockHandshakeState {
    AWAITING_NETWORK_SETTINGS_REQUEST,
    NETWORK_SETTINGS_PENDING,
    AWAITING_LOGIN,
    AUTHENTICATION_PENDING,
    LOGIN_SUCCESS_SENT,
    PLAY,
    CLOSED
}
