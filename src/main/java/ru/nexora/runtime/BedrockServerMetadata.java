package ru.nexora.runtime;

import java.util.Objects;

/**
 * Immutable public metadata returned by the NetherNet GET /v1/join endpoint.
 *
 * <p>The JSON shape follows Mojang's NetherNet onboarding guide. This value
 * object does not start an HTTP server or perform signaling.</p>
 */
public record BedrockServerMetadata(
        String name,
        String level,
        int players,
        int maxPlayers,
        int gameType
) {
    public BedrockServerMetadata {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(level, "level");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Server name must not be blank");
        }
        if (level.isBlank()) {
            throw new IllegalArgumentException("Level name must not be blank");
        }
        if (players < 0) {
            throw new IllegalArgumentException("Player count must not be negative");
        }
        if (maxPlayers < 1 || players > maxPlayers) {
            throw new IllegalArgumentException("Player count must be between 0 and maxPlayers");
        }
        if (gameType < 0 || gameType > 2) {
            throw new IllegalArgumentException("Game type must be 0 (survival), 1 (creative), or 2 (adventure)");
        }
    }

    /** Serializes the exact metadata shape expected by NetherNet discovery. */
    public String toJson() {
        return "{"
                + "\"name\":" + quote(name) + ","
                + "\"protocol\":" + BedrockProtocolVersion.PROTOCOL_VERSION + ","
                + "\"version\":" + quote(BedrockProtocolVersion.GAME_VERSION) + ","
                + "\"level\":" + quote(level) + ","
                + "\"players\":" + players + ","
                + "\"maxPlayers\":" + maxPlayers + ","
                + "\"gameType\":" + gameType
                + "}";
    }

    private static String quote(String value) {
        StringBuilder json = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}
