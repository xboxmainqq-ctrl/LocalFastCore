package ru.nexora.network;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared-database transfer ledger adapter.
 *
 * Each mutation is a single database statement guarded by the record version,
 * so competing nodes cannot both advance the same transfer version. The
 * DataSource must point at the same transactional database on every node.
 * Schema creation is explicit and should be performed by deployment tooling.
 */
public final class JdbcPlayerTransferLedger implements PlayerTransferLedger {
    public static final String TABLE_NAME = "nexora_player_transfers";

    private static final String COLUMNS = "transfer_id, player_xuid, source_node, target_node, "
            + "player_revision, transfer_state, fencing_token, lease_expires_at, record_version, updated_at";

    private final DataSource dataSource;

    public JdbcPlayerTransferLedger(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Creates the portable table schema. Run once during controlled deployment,
     * not on every server tick or player operation.
     */
    public void initializeSchema() throws IOException {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
                + "transfer_id VARCHAR(36) PRIMARY KEY, "
                + "player_xuid VARCHAR(64) NOT NULL, "
                + "source_node VARCHAR(64) NOT NULL, "
                + "target_node VARCHAR(64) NOT NULL, "
                + "player_revision BIGINT NOT NULL, "
                + "transfer_state VARCHAR(16) NOT NULL, "
                + "fencing_token BIGINT NOT NULL, "
                + "lease_expires_at BIGINT NOT NULL, "
                + "record_version BIGINT NOT NULL, "
                + "updated_at BIGINT NOT NULL)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
            commitIfNeeded(connection);
        } catch (SQLException exception) {
            throw new IOException("Unable to initialize NEXORA transfer ledger schema", exception);
        }
    }

    @Override
    public Optional<PlayerTransferRecord> get(UUID transferId) throws IOException {
        Objects.requireNonNull(transferId, "transferId");
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE_NAME + " WHERE transfer_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, transferId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRecord(result)) : Optional.empty();
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new IOException("Unable to read NEXORA transfer ledger record", exception);
        }
    }

    @Override
    public boolean create(PlayerTransferRecord prepared) throws IOException {
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.state() != PlayerTransferRecord.State.PREPARED
                || prepared.version() != 0 || prepared.fencingToken() != 0) {
            throw new IllegalArgumentException("Only a version-zero PREPARED record can be created");
        }
        String sql = "INSERT INTO " + TABLE_NAME + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRecord(statement, prepared);
            statement.executeUpdate();
            commitIfNeeded(connection);
            return true;
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            return false;
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) return false;
            throw new IOException("Unable to create NEXORA transfer ledger record", exception);
        }
    }

    @Override
    public boolean compareAndSet(UUID transferId, long expectedVersion,
                                 PlayerTransferRecord replacement) throws IOException {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(replacement, "replacement");
        if (!transferId.equals(replacement.transferId())) {
            throw new IllegalArgumentException("Transfer ID cannot change during compare-and-set");
        }
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE
                || replacement.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("Replacement version must be expectedVersion + 1");
        }
        Optional<PlayerTransferRecord> stored = get(transferId);
        if (stored.isEmpty()) return false;
        PlayerTransferRecord current = stored.orElseThrow();
        if (current.version() != expectedVersion || !replacement.isLegalSuccessorOf(current)) return false;
        String sql = "UPDATE " + TABLE_NAME + " SET transfer_state = ?, fencing_token = ?, "
                + "lease_expires_at = ?, record_version = ?, updated_at = ? "
                + "WHERE transfer_id = ? AND record_version = ? AND player_xuid = ? "
                + "AND source_node = ? AND target_node = ? AND player_revision = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, replacement.state().name());
            statement.setLong(2, replacement.fencingToken());
            statement.setLong(3, replacement.leaseExpiresAtEpochMillis());
            statement.setLong(4, replacement.version());
            statement.setLong(5, replacement.updatedAtEpochMillis());
            statement.setString(6, transferId.toString());
            statement.setLong(7, expectedVersion);
            statement.setString(8, replacement.playerXuid());
            statement.setString(9, replacement.sourceNode());
            statement.setString(10, replacement.targetNode());
            statement.setLong(11, replacement.playerRevision());
            int updated = statement.executeUpdate();
            commitIfNeeded(connection);
            return updated == 1;
        } catch (SQLException exception) {
            throw new IOException("Unable to advance NEXORA transfer ledger record", exception);
        }
    }

    @Override
    public List<PlayerTransferRecord> listRecoverableForTarget(String targetNode, int limit) throws IOException {
        if (targetNode == null || !targetNode.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Invalid target node");
        if (limit < 1 || limit > 10_000)
            throw new IllegalArgumentException("Recovery query limit must be 1..10000");
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE_NAME
                + " WHERE target_node = ? AND transfer_state IN (?, ?) "
                + "ORDER BY updated_at DESC, transfer_id ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, targetNode);
            statement.setString(2, PlayerTransferRecord.State.CLAIMED.name());
            statement.setString(3, PlayerTransferRecord.State.COMMITTED.name());
            statement.setMaxRows(limit);
            try (ResultSet result = statement.executeQuery()) {
                List<PlayerTransferRecord> records = new ArrayList<>();
                while (result.next()) records.add(readRecord(result));
                return List.copyOf(records);
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new IOException("Unable to list recoverable NEXORA transfer records", exception);
        }
    }

    private static void commitIfNeeded(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) connection.commit();
    }

    private static void bindRecord(PreparedStatement statement, PlayerTransferRecord record)
            throws SQLException {
        statement.setString(1, record.transferId().toString());
        statement.setString(2, record.playerXuid());
        statement.setString(3, record.sourceNode());
        statement.setString(4, record.targetNode());
        statement.setLong(5, record.playerRevision());
        statement.setString(6, record.state().name());
        statement.setLong(7, record.fencingToken());
        statement.setLong(8, record.leaseExpiresAtEpochMillis());
        statement.setLong(9, record.version());
        statement.setLong(10, record.updatedAtEpochMillis());
    }

    private static PlayerTransferRecord readRecord(ResultSet result) throws SQLException {
        return new PlayerTransferRecord(
                UUID.fromString(result.getString("transfer_id")),
                result.getString("player_xuid"),
                result.getString("source_node"),
                result.getString("target_node"),
                result.getLong("player_revision"),
                PlayerTransferRecord.State.valueOf(result.getString("transfer_state")),
                result.getLong("fencing_token"),
                result.getLong("lease_expires_at"),
                result.getLong("record_version"),
                result.getLong("updated_at"));
    }

    private static boolean isConstraintViolation(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("23")) return true;
        }
        return false;
    }
}
