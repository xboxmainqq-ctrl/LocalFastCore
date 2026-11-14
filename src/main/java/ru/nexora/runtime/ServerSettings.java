package ru.nexora.runtime;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable, platform-independent configuration for a standalone NEXORA server.
 *
 * <p>These settings describe server intent only. They do not open sockets,
 * create directories, or start game services.</p>
 */
public record ServerSettings(
        String bindAddress,
        int port,
        int maxPlayers,
        int tickRate,
        int viewDistance,
        boolean onlineMode,
        Path dataDirectory
) {
    public static final int DEFAULT_PORT = 19132;
    public static final int DEFAULT_MAX_PLAYERS = 20;
    public static final int DEFAULT_TICK_RATE = 20;
    public static final int DEFAULT_VIEW_DISTANCE = 10;

    public ServerSettings {
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(dataDirectory, "dataDirectory");

        bindAddress = bindAddress.trim();
        if (bindAddress.isEmpty()) {
            throw new IllegalArgumentException("Bind address must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        if (maxPlayers < 1 || maxPlayers > 10000) {
            throw new IllegalArgumentException("Max players must be between 1 and 10000");
        }
        if (tickRate < 1 || tickRate > 120) {
            throw new IllegalArgumentException("Tick rate must be between 1 and 120");
        }
        if (viewDistance < 2 || viewDistance > 32) {
            throw new IllegalArgumentException("View distance must be between 2 and 32");
        }

        dataDirectory = dataDirectory.toAbsolutePath().normalize();
    }

    public static ServerSettings defaults(Path dataDirectory) {
        return new ServerSettings(
                "0.0.0.0",
                DEFAULT_PORT,
                DEFAULT_MAX_PLAYERS,
                DEFAULT_TICK_RATE,
                DEFAULT_VIEW_DISTANCE,
                true,
                dataDirectory
        );
    }
}
