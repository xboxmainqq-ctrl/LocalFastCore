package ru.nexora.network.nethernet;

import java.util.Objects;

/** Public server information returned by GET /v1/join. */
public record NetherNetServerMetadata(
        String name,
        int protocol,
        String version,
        String level,
        int players,
        int maxPlayers,
        int gameType
) {
    public NetherNetServerMetadata {
        name = requireText(name, "name");
        version = requireText(version, "version");
        level = requireText(level, "level");
        if (protocol < 0) throw new IllegalArgumentException("protocol must be >= 0");
        if (players < 0 || maxPlayers < 1 || players > maxPlayers) {
            throw new IllegalArgumentException("Invalid player counts");
        }
        if (gameType < 0 || gameType > 2) {
            throw new IllegalArgumentException("gameType must be 0 (Survival), 1 (Creative), or 2 (Adventure)");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public String toJson() {
        return "{"
                + "\"name\":\"" + escape(name) + "\","
                + "\"protocol\":" + protocol + ","
                + "\"version\":\"" + escape(version) + "\","
                + "\"level\":\"" + escape(level) + "\","
                + "\"players\":" + players + ","
                + "\"maxPlayers\":" + maxPlayers + ","
                + "\"gameType\":" + gameType
                + "}";
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.toString();
    }
}
