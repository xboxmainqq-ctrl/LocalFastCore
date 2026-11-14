package ru.nexora.runtime;

import java.util.Objects;

/** Public metadata returned by GET /v1/join. */
public record NetherNetServerInfo(
        String name,
        int protocol,
        String version,
        String level,
        int players,
        int maxPlayers,
        int gameType
) {
    public NetherNetServerInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(level, "level");
        if (name.isBlank() || version.isBlank() || level.isBlank()) {
            throw new IllegalArgumentException("Server metadata strings must not be blank");
        }
        if (protocol < 0 || players < 0 || maxPlayers < 1 || players > maxPlayers) {
            throw new IllegalArgumentException("Invalid server protocol or player counts");
        }
        if (gameType < 0 || gameType > 2) {
            throw new IllegalArgumentException("gameType must be 0, 1, or 2");
        }
    }

    /** JSON response for the NetherNet capability endpoint. */
    public String toJson() {
        return "{"
                + "\"name\":" + quote(name) + ","
                + "\"protocol\":" + protocol + ","
                + "\"nexoraProtocol\":" + NexoraPrivateProtocolCodec.MAJOR_VERSION + ","
                + "\"version\":" + quote(version) + ","
                + "\"level\":" + quote(level) + ","
                + "\"players\":" + players + ","
                + "\"maxPlayers\":" + maxPlayers + ","
                + "\"gameType\":" + gameType
                + "}";
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
