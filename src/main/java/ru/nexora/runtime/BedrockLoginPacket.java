package ru.nexora.runtime;

import java.util.Objects;

/** Decoded fields of a Bedrock LoginPacket payload. */
public record BedrockLoginPacket(int protocolVersion, String connectionRequest) {
    public BedrockLoginPacket {
        Objects.requireNonNull(connectionRequest, "connectionRequest");
    }

    /** True only when this login targets the pinned NEXORA protocol version. */
    public boolean isSupportedProtocol() {
        return BedrockProtocolVersion.supportsProtocol(protocolVersion);
    }
}
