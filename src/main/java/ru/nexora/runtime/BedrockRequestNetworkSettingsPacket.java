package ru.nexora.runtime;

/** Fields of the initial Bedrock RequestNetworkSettingsPacket. */
public record BedrockRequestNetworkSettingsPacket(int clientNetworkVersion) {
    /** True only for the protocol version pinned by this NEXORA line. */
    public boolean isSupportedProtocol() {
        return BedrockProtocolVersion.supportsProtocol(clientNetworkVersion);
    }
}
