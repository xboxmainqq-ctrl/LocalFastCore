package ru.nexora.runtime;

/**
 * Pinned Bedrock wire-protocol target for this NEXORA development line.
 *
 * <p>This is a compatibility target, not a claim that the login sequence or
 * packet codec is implemented. Keep the game version and protocol number
 * together so metadata cannot advertise mismatched values.</p>
 */
public final class BedrockProtocolVersion {
    public static final String GAME_VERSION = "1.26.60";
    public static final int PROTOCOL_VERSION = 2225;

    private BedrockProtocolVersion() {
    }

    /** True when a client reports the exact pinned protocol number. */
    public static boolean supportsProtocol(int protocol) {
        return protocol == PROTOCOL_VERSION;
    }
}
