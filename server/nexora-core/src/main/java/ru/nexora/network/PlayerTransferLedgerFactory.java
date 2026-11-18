package ru.nexora.network;

import ru.nexora.persistence.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Selects a local development ledger or a deployment-shared JDBC ledger. */
public final class PlayerTransferLedgerFactory {
    public record ConfiguredLedger(DurableHandoffCoordinator coordinator,
                                   boolean shared,
                                   String description) {
        public ConfiguredLedger {
            Objects.requireNonNull(coordinator, "coordinator");
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Ledger description must not be blank");
            }
        }
    }

    private PlayerTransferLedgerFactory() {
    }

    public static ConfiguredLedger open(Path dataDirectory, Map<String, String> environment)
            throws IOException {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(environment, "environment");
        String jdbcUrl = value(environment, "NEXORA_TRANSFER_JDBC_URL");
        PlayerTransferLedger ledger;
        boolean shared;
        String description;
        if (jdbcUrl == null) {
            Path path = dataDirectory.resolve("cluster").resolve("transfers");
            ledger = new FilePlayerTransferLedger(path);
            shared = false;
            description = "local file ledger at " + path.toAbsolutePath() + " (single-host only)";
        } else {
            String username = value(environment, "NEXORA_TRANSFER_JDBC_USER");
            String password = value(environment, "NEXORA_TRANSFER_JDBC_PASSWORD");
            DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
            JdbcPlayerTransferLedger jdbc = new JdbcPlayerTransferLedger(dataSource);
            if (booleanValue(environment, "NEXORA_TRANSFER_JDBC_INITIALIZE_SCHEMA", false)) {
                jdbc.initializeSchema();
            }
            ledger = jdbc;
            shared = true;
            description = "shared JDBC ledger (connection details hidden)";
        }
        return new ConfiguredLedger(
                new DurableHandoffCoordinator(new PlayerTransferCoordinator(ledger)),
                shared,
                description);
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
