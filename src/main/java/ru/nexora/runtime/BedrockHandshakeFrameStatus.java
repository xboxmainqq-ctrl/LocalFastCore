package ru.nexora.runtime;

/** Result category returned while processing an initial Bedrock handshake frame. */
public enum BedrockHandshakeFrameStatus {
    NETWORK_SETTINGS_RESPONSE_READY,
    AUTHENTICATION_REQUIRED,
    REJECTED_PACKET,
    MALFORMED_FRAME,
    UNSUPPORTED_COMPRESSION,
    INVALID_STATE,
    SESSION_CLOSED
}
