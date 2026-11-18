package ru.nexora.persistence;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Selects local checkpoint files or a deployment-shared JDBC checkpoint store. */
public final class PlayerStateStoreFactory {
    public record ConfiguredStore(PlayerStateStore store, boolean shared, String description) {
        public ConfiguredStore {
            Objects.requireNonNull(store, "store");
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Store description must not be blank");
            }
        }
    }

    private PlayerStateStoreFactory() {
    }

    public static ConfiguredStore open(Path localDirectory, Map<String, String> environment)
            throws IOException {
        Objects.requireNonNull(localDirectory, "localDirectory");
        Objects.requireNonNull(environment, "environment");
        String jdbcUrl = value(environment, "NEXORA_PLAYER_STATE_JDBC_URL");
        if (jdbcUrl == null) {
            return new ConfiguredStore(new FilePlayerStateStore(localDirectory), false,
                    "local checksummed files at " + localDirectory.toAbsolutePath() + " (single-host only)");
        }
        String username = value(environment, "NEXORA_PLAYER_STATE_JDBC_USER");
        String password = value(environment, "NEXORA_PLAYER_STATE_JDBC_PASSWORD");
        DataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        JdbcPlayerStateStore store = new JdbcPlayerStateStore(dataSource);
        if (booleanValue(environment, "NEXORA_PLAYER_STATE_JDBC_INITIALIZE_SCHEMA", false)) {
            store.initializeSchema();
        }
        // Validate credentials, connectivity and pre-provisioned schema before accepting players.
        store.loadLatest("nexora-schema-check");
        return new ConfiguredStore(store, true, "shared JDBC player checkpoint store (connection details hidden)");
    }

    private static String value(Map<String, String> environment, String name) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean booleanValue(Map<String, String> environment, String name, boolean fallback) {
        String value = value(environment, name);
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(name + " must be true or false");
    }
}
