package ru.nexora.runtime;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/** Validated, human-editable settings for a standalone NEXORA server. */
public record ServerConfiguration(
        String worldId,
        String generatorId,
        long seed,
        WorldHeight worldHeight,
        int tickRate
) {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public ServerConfiguration {
        worldId = validateId(worldId, "world id");
        generatorId = validateId(generatorId, "generator id");
        Objects.requireNonNull(worldHeight, "worldHeight");
        if (worldHeight.height() < 8 || worldHeight.height() > 4096) {
            throw new IllegalArgumentException("World height must be between 8 and 4096 blocks");
        }
        if (tickRate < 1 || tickRate > 120) {
            throw new IllegalArgumentException("Tick rate must be between 1 and 120");
        }
    }

    public static ServerConfiguration defaults() {
        return new ServerConfiguration("world", "overworld", 0L,
                new WorldHeight(-64, 320), 20);
    }

    /** Loads server.properties, creating a documented default file on first launch. */
    public static ServerConfiguration loadOrCreate(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (Files.notExists(file)) {
            ServerConfiguration defaults = defaults();
            defaults.save(file);
            return defaults;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        ServerConfiguration defaults = defaults();
        try {
            return new ServerConfiguration(
                    properties.getProperty("world.id", defaults.worldId()),
                    properties.getProperty("world.generator", defaults.generatorId()),
                    Long.parseLong(properties.getProperty("world.seed", Long.toString(defaults.seed())).trim()),
                    new WorldHeight(
                            Integer.parseInt(properties.getProperty("world.min-y",
                                    Integer.toString(defaults.worldHeight().minY())).trim()),
                            Integer.parseInt(properties.getProperty("world.max-y-exclusive",
                                    Integer.toString(defaults.worldHeight().maxYExclusive())).trim())),
                    Integer.parseInt(properties.getProperty("server.tick-rate",
                            Integer.toString(defaults.tickRate())).trim()));
        } catch (NumberFormatException invalidNumber) {
            throw new IOException("Invalid numeric value in " + file, invalidNumber);
        } catch (IllegalArgumentException invalidSetting) {
            throw new IOException("Invalid NEXORA server setting in " + file + ": "
                    + invalidSetting.getMessage(), invalidSetting);
        }
    }

    public void save(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        Properties properties = new Properties();
        properties.setProperty("world.id", worldId);
        properties.setProperty("world.generator", generatorId);
        properties.setProperty("world.seed", Long.toString(seed));
        properties.setProperty("world.min-y", Integer.toString(worldHeight.minY()));
        properties.setProperty("world.max-y-exclusive", Integer.toString(worldHeight.maxYExclusive()));
        properties.setProperty("server.tick-rate", Integer.toString(tickRate));
        properties.setProperty("server.network.enabled", "false");
        properties.setProperty("bedrock.network.enabled", "false");
        properties.setProperty("bedrock.bind-address", "0.0.0.0");
        properties.setProperty("bedrock.port", "19132");
        properties.setProperty("bedrock.ice-port", "0");
        properties.setProperty("bedrock.identity-file", "nethernet-identity.pem");
        try (Writer writer = Files.newBufferedWriter(file)) {
            properties.store(writer, "NEXORA Dedicated Server configuration");
        }
    }

    private static String validateId(String value, String label) {
        Objects.requireNonNull(value, label);
        String normalized = value.trim();
        if (!ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
        return normalized;
    }
}
