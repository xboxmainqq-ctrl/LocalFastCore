package ru.nexora.network.raknet;

import java.util.Objects;

/**
 * Values advertised to Bedrock clients in the RakNet unconnected-pong response.
 */
public record BedrockServerStatus(
        String motd,
        int protocolVersion,
        String versionName,
        int playerCount,
        int maxPlayers,
        String subMotd,
        String gameMode,
        int gameModeId,
        int ipv4Port,
        int ipv6Port
) {
    public BedrockServerStatus {
        motd = requireSingleLine(motd, "motd");
        versionName = requireSingleLine(versionName, "versionName");
        subMotd = requireSingleLine(subMotd, "subMotd");
        gameMode = requireSingleLine(gameMode, "gameMode");
        if (protocolVersion < 0) throw new IllegalArgumentException("protocolVersion must be >= 0");
        if (playerCount < 0 || maxPlayers < 1 || playerCount > maxPlayers) {
            throw new IllegalArgumentException("Invalid player counts");
        }
        if (gameModeId < 0) throw new IllegalArgumentException("gameModeId must be >= 0");
        checkPort(ipv4Port, "ipv4Port");
        checkPort(ipv6Port, "ipv6Port");
    }

    private static String requireSingleLine(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.indexOf(';') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " must not contain semicolons or line breaks");
        }
        return value;
    }

    private static void checkPort(int port, String field) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException(field + " must be 1..65535");
    }

    public String toBedrockAdvertisement(long serverId) {
        return String.join(";",
                "MCPE",
                motd,
                Integer.toString(protocolVersion),
                versionName,
                Integer.toString(playerCount),
                Integer.toString(maxPlayers),
                Long.toUnsignedString(serverId),
                subMotd,
                gameMode,
                Integer.toString(gameModeId),
                Integer.toString(ipv4Port),
                Integer.toString(ipv6Port)
        );
    }
}
