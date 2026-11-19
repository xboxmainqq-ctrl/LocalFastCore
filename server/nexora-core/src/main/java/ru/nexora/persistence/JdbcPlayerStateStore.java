package ru.nexora.persistence;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared SQL checkpoint store. Per-player revision CAS prevents an older node
 * from overwriting a newer snapshot. Payloads are Base64 encoded in a text
 * column to avoid vendor-specific binary column types.
 */
public final class JdbcPlayerStateStore implements PlayerStateStore {
    public static final String TABLE_NAME = "nexora_player_state_snapshots";
    private static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SAVE_ATTEMPTS = 32;

    private final DataSource dataSource;

    public JdbcPlayerStateStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Run as a deployment migration or explicitly opt in during startup. */
    public void initializeSchema() throws IOException {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
            String payloadType;
            if (product.contains("postgresql")) payloadType = "TEXT";
            else if (product.contains("mysql") || product.contains("mariadb")) payloadType = "LONGTEXT";
            else if (product.contains("h2")) payloadType = "CLOB";
            else throw new IOException("Unsupported JDBC database for NEXORA player checkpoints: " + product);
            String sql = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
                    + "player_xuid VARCHAR(64) PRIMARY KEY, "
                    + "schema_version INTEGER NOT NULL, "
                    + "revision BIGINT NOT NULL, "
                    + "captured_at BIGINT NOT NULL, "
                    + "payload_base64 " + payloadType + " NOT NULL, "
                    + "checksum VARCHAR(64) NOT NULL)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.executeUpdate();
                commitIfNeeded(connection);
            }
        } catch (SQLException exception) {
            throw new IOException("Unable to initialize NEXORA player checkpoint schema", exception);
        }
    }

    @Override
    public SaveResult save(PlayerStateSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        byte[] payload = snapshot.payload();
        if (!snapshot.hasValidChecksum()) throw new IllegalArgumentException("Invalid player snapshot checksum");
        if (payload.length > MAX_PAYLOAD_BYTES) throw new IOException("Player checkpoint exceeds storage limit");
        String encoded = Base64.getEncoder().encodeToString(payload);

        for (int attempt = 0; attempt < MAX_SAVE_ATTEMPTS; attempt++) {
            Optional<PlayerStateSnapshot> stored = loadLatest(snapshot.playerId());
            if (stored.isPresent()) {
                PlayerStateSnapshot current = stored.orElseThrow();
                if (snapshot.revision() < current.revision()) return SaveResult.STALE;
                if (snapshot.revision() == current.revision()) {
                    if (snapshot.schemaVersion() == current.schemaVersion()
                            && snapshot.checksum().equals(current.checksum())) return SaveResult.ALREADY_CURRENT;
                    throw new IllegalStateException("Conflicting player snapshot revision "
                            + snapshot.revision() + " for player " + snapshot.playerId());
                }
                String update = "UPDATE " + TABLE_NAME
                        + " SET schema_version = ?, revision = ?, captured_at = ?, payload_base64 = ?, checksum = ?"
                        + " WHERE player_xuid = ? AND revision = ?";
                try (Connection connection = dataSource.getConnection();
                     PreparedStatement statement = connection.prepareStatement(update)) {
                    statement.setInt(1, snapshot.schemaVersion());
                    statement.setLong(2, snapshot.revision());
                    statement.setLong(3, snapshot.capturedAtEpochMillis());
                    statement.setString(4, encoded);
                    statement.setString(5, snapshot.checksum());
                    statement.setString(6, snapshot.playerId());
                    statement.setLong(7, current.revision());
                    int changed = statement.executeUpdate();
                    commitIfNeeded(connection);
                    if (changed == 1) return SaveResult.SAVED;
                } catch (SQLException exception) {
                    throw new IOException("Unable to advance NEXORA player checkpoint revision", exception);
                }
                continue;
            }

            String insert = "INSERT INTO " + TABLE_NAME
                    + " (player_xuid, schema_version, revision, captured_at, payload_base64, checksum)"
                    + " VALUES (?, ?, ?, ?, ?, ?)";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, snapshot.playerId());
                statement.setInt(2, snapshot.schemaVersion());
                statement.setLong(3, snapshot.revision());
                statement.setLong(4, snapshot.capturedAtEpochMillis());
                statement.setString(5, encoded);
                statement.setString(6, snapshot.checksum());
                statement.executeUpdate();
                commitIfNeeded(connection);
                return SaveResult.SAVED;
            } catch (SQLException exception) {
                if (isConstraintViolation(exception)) continue;
                throw new IOException("Unable to create NEXORA player checkpoint", exception);
            }
        }
        throw new IOException("Player checkpoint CAS contention exceeded retry limit");
    }

    @Override
    public Optional<PlayerStateSnapshot> loadLatest(String playerId) throws IOException {
        if (playerId == null || !playerId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid player XUID");
        }
        String sql = "SELECT schema_version, revision, captured_at, payload_base64, checksum"
                + " FROM " + TABLE_NAME + " WHERE player_xuid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                int schema = result.getInt("schema_version");
                long revision = result.getLong("revision");
                long capturedAt = result.getLong("captured_at");
                String encoded = result.getString("payload_base64");
                String checksum = result.getString("checksum");
                if (encoded == null || encoded.length() > ((MAX_PAYLOAD_BYTES + 2) / 3) * 4) {
                    throw new IOException("Stored NEXORA player checkpoint exceeds storage limit");
                }
                byte[] payload;
                try {
                    payload = Base64.getDecoder().decode(encoded);
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Stored NEXORA player checkpoint has invalid Base64", invalid);
                }
                if (payload.length > MAX_PAYLOAD_BYTES) {
                    throw new IOException("Stored NEXORA player checkpoint exceeds storage limit");
                }
                PlayerStateSnapshot snapshot;
                try {
                    snapshot = new PlayerStateSnapshot(schema, playerId, revision, capturedAt, payload);
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Stored NEXORA player checkpoint metadata is invalid", invalid);
                }
                if (!snapshot.checksum().equals(checksum) || !snapshot.hasValidChecksum()) {
                    throw new IOException("Stored NEXORA player checkpoint checksum mismatch");
                }
                return Optional.of(snapshot);
            }
        } catch (SQLException exception) {
            throw new IOException("Unable to load NEXORA player checkpoint", exception);
        }
    }

    private static void commitIfNeeded(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) connection.commit();
    }

    private static boolean isConstraintViolation(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("23")) return true;
        }
        return false;
    }
}
