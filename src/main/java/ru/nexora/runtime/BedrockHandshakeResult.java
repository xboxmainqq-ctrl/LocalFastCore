package ru.nexora.runtime;

/** Outcome of a server-side Bedrock handshake transition. */
public enum BedrockHandshakeResult {
    ACCEPTED,
    UNSUPPORTED_PROTOCOL,
    LOGIN_PROTOCOL_MISMATCH,
    INVALID_STATE,
    SESSION_CLOSED
}
