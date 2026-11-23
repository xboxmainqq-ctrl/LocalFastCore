package ru.nexora.network.bedrock;

/**
 * Wire protocol and base-game metadata pinned by the NEXORA protocol-2225
 * GameData build. Bedrock 1.26.62 uses this same wire protocol; the base-game
 * version sent in StartGame and ResourcePackStack must describe the GameData
 * resources actually bundled with this server.
 */
public final class BedrockProtocolVersion {
    public static final int PROTOCOL = 2225;
    public static final String BASE_GAME_VERSION = "1.26.60";

    private BedrockProtocolVersion() {
    }
}
