package ru.nexora.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, validated configuration for one NEXORA server process. */
public record NexoraServerConfig(
        String bindHost,
        int port,
        String motd,
        int maxPlayers,
        String httpBindHost,
        String dataDirectory,
        long autosaveSeconds,
        int udpPortMin,
        int udpPortMax,
        List<String> stunUrls,
        int maximumResidentChunks
) {
    public NexoraServerConfig {
        bindHost = requireText(bindHost, "bindHost");
        motd = requireText(motd, "motd");
        httpBindHost = requireText(httpBindHost, "httpBindHost");
        dataDirectory = requireText(dataDirectory, "dataDirectory");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be between 1 and 65535");
        if (maxPlayers < 1) throw new IllegalArgumentException("maxPlayers must be positive");
        if (maximumResidentChunks < 1) throw new IllegalArgumentException("maximumResidentChunks must be positive");
        if (autosaveSeconds < 1) throw new IllegalArgumentException("autosaveSeconds must be positive");
        if (udpPortMin < 1 || udpPortMax > 65535 || udpPortMin > udpPortMax) {
            throw new IllegalArgumentException("Invalid WebRTC UDP port range");
        }
        stunUrls = List.copyOf(Objects.requireNonNull(stunUrls, "stunUrls")).stream()
                .map(value -> requireText(value, "STUN URL"))
                .toList();
    }

    /** Backwards-compatible constructor for callers that predate residency limits. */
    public NexoraServerConfig(String bindHost, int port, String motd, int maxPlayers,
            String httpBindHost, String dataDirectory, long autosaveSeconds,
            int udpPortMin, int udpPortMax, List<String> stunUrls) {
        this(bindHost, port, motd, maxPlayers, httpBindHost, dataDirectory, autosaveSeconds,
                udpPortMin, udpPortMax, stunUrls, 8192);
    }

    public static NexoraServerConfig from(String[] args, Map<String, String> environment) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(environment, "environment");
        String bindHost = argument(args, 0, "0.0.0.0");
        return new NexoraServerConfig(
                bindHost,
                integerArgument(args, 1, serverPortDefault(environment), "port"),
                argument(args, 2, "NEXORA Core - Development"),
                integerArgument(args, 3, 20, "maxPlayers"),
                environment.getOrDefault("NEXORA_HTTP_BIND", bindHost),
                environment.getOrDefault("NEXORA_DATA_DIR", "data"),
                longEnvironment(environment, "NEXORA_AUTOSAVE_SECONDS", 300L),
                integerEnvironment(environment, "NEXORA_UDP_PORT_MIN", 30000),
                integerEnvironment(environment, "NEXORA_UDP_PORT_MAX", 30199),
                parseStunUrls(environment.get("NEXORA_STUN_URLS")),
                integerEnvironment(environment, "NEXORA_MAX_RESIDENT_CHUNKS", 8192)
        );
    }

    /**
     * Pterodactyl-style hosts expose the primary allocation as SERVER_PORT.
     * Explicit command-line arguments still take precedence; NEXORA_PORT can
     * override the host-provided value for custom deployments.
     */
    private static int serverPortDefault(Map<String, String> environment) {
        for (String name : List.of("NEXORA_PORT", "SERVER_PORT", "PORT")) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) {
                return integerEnvironment(environment, name, 19132);
            }
        }
        return 19132;
    }

    private static List<String> parseStunUrls(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(";"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private static String argument(String[] args, int index, String fallback) {
        return args.length > index ? args[index] : fallback;
    }

    private static int integerArgument(String[] args, int index, int fallback, String name) {
        if (args.length <= index) return fallback;
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid command-line " + name + ": " + args[index], exception);
        }
    }

    private static int integerEnvironment(Map<String, String> environment, String name, int fallback) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value, exception);
        }
    }

    private static long longEnvironment(Map<String, String> environment, String name, long fallback) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value, exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
