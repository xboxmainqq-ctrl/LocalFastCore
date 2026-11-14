package ru.nexora.runtime;

import java.util.Objects;

/**
 * A Bedrock Login packet that has passed protocol sequencing but is not yet
 * authenticated. The authentication service must verify the connection
 * request before calling {@link #markAuthenticated()}.
 */
public final class BedrockPendingLogin {
    private final TransportConnection connection;
    private final BedrockLoginPacket login;
    private final BedrockInitialHandshakeProcessor processor;

    BedrockPendingLogin(TransportConnection connection,
                        BedrockLoginPacket login,
                        BedrockInitialHandshakeProcessor processor) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.login = Objects.requireNonNull(login, "login");
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    public TransportConnection connection() {
        return connection;
    }

    public BedrockLoginPacket login() {
        return login;
    }

    /**
     * Advances the session to PLAY. Call only after identity/account
     * authentication of {@link BedrockLoginPacket#connectionRequest()} succeeds.
     */
    public BedrockHandshakeResult markAuthenticated() {
        return processor.markAuthenticated();
    }

    public BedrockHandshakeState state() {
        return processor.state();
    }
}
