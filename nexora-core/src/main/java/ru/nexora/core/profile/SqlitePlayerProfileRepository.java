package ru.nexora.core.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-backed profile repository.
 *
 * All public operations are synchronized because this implementation owns a
 * single JDBC connection. Call it from a worker thread, not the server's
 * primary tick thread, when profile data may be read or written frequently.
 */
public final class SqlitePlayerProfileRepository implements PlayerProfileRepository {
    private static final int SCHEMA_VERSION = 1;

    private final Connection connection;
    private boolean closed;

    public SqlitePlayerProfileRepository(Path databaseFile) throws IOException {
        if (databaseFile == null) {
            throw new IllegalArgumentException("databaseFile must not be null");
        }
        Path absolute = databaseFile.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Connection opened = null;
        try {
            Class.forName("org.sqlite.JDBC");
            opened = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            connection = opened;
            configure();
            migrate();
        } catch (ClassNotFoundException | SQLException | IOException failure) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw new IOException("Could not open or migrate NEXORA profile database", failure);
        }
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
        }
    }

    private void migrate() throws SQLException, IOException {
        int currentVersion;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            currentVersion = result.next() ? result.getInt(1) : 0;
        }
        if (currentVersion > SCHEMA_VERSION) {
            throw new IOException("Profile database schema " + currentVersion
                    + " is newer than this NEXORA build supports (" + SCHEMA_VERSION + ")");
        }
        if (currentVersion == 0) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS player_profiles (
                            player_uuid TEXT PRIMARY KEY NOT NULL,
                            last_known_name TEXT NOT NULL,
                            class_id TEXT,
                            level INTEGER NOT NULL CHECK (level >= 1),
                            experience INTEGER NOT NULL CHECK (experience >= 0)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS player_profile_attributes (
                            player_uuid TEXT NOT NULL,
                            attribute_key TEXT NOT NULL,
                            attribute_value INTEGER NOT NULL,
                            PRIMARY KEY (player_uuid, attribute_key),
                            FOREIGN KEY (player_uuid) REFERENCES player_profiles(player_uuid)
                                ON DELETE CASCADE
                        )
                        """);
                statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    @Override
    public synchronized Optional<PlayerProfile> find(UUID uniqueId) throws IOException {
        ensureOpen();
        if (uniqueId == null) {
            throw new IllegalArgumentException("uniqueId must not be null");
        }
        try {
            String name;
            String classId;
            int level;
            long experience;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT last_known_name, class_id, level, experience "
                            + "FROM player_profiles WHERE player_uuid = ?")) {
                statement.setString(1, uniqueId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    name = result.getString("last_known_name");
                    classId = result.getString("class_id");
                    level = result.getInt("level");
                    experience = result.getLong("experience");
                }
            }

            Map<String, Integer> attributes = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT attribute_key, attribute_value FROM player_profile_attributes "
                            + "WHERE player_uuid = ? ORDER BY attribute_key")) {
                statement.setString(1, uniqueId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        attributes.put(result.getString("attribute_key"),
                                result.getInt("attribute_value"));
                    }
                }
            }
            return Optional.of(new PlayerProfile(uniqueId, name, Optional.ofNullable(classId),
                    level, experience, attributes));
        } catch (SQLException exception) {
            throw new IOException("Could not load player profile " + uniqueId, exception);
        }
    }

    @Override
    public synchronized void save(PlayerProfile profile) throws IOException {
        ensureOpen();
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO player_profiles
                        (player_uuid, last_known_name, class_id, level, experience)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(player_uuid) DO UPDATE SET
                        last_known_name = excluded.last_known_name,
                        class_id = excluded.class_id,
                        level = excluded.level,
                        experience = excluded.experience
                    """)) {
                statement.setString(1, profile.uniqueId().toString());
                statement.setString(2, profile.lastKnownName());
                statement.setString(3, profile.classId().orElse(null));
                statement.setInt(4, profile.level());
                statement.setLong(5, profile.experience());
                statement.executeUpdate();
            }

            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM player_profile_attributes WHERE player_uuid = ?");
                 PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO player_profile_attributes
                        (player_uuid, attribute_key, attribute_value) VALUES (?, ?, ?)
                    """)) {
                delete.setString(1, profile.uniqueId().toString());
                delete.executeUpdate();
                for (Map.Entry<String, Integer> entry : profile.attributes().entrySet()) {
                    insert.setString(1, profile.uniqueId().toString());
                    insert.setString(2, entry.getKey());
                    insert.setInt(3, entry.getValue());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            connection.setAutoCommit(previousAutoCommit);
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
            try {
                connection.setAutoCommit(true);
            } catch (SQLException restoreException) {
                exception.addSuppressed(restoreException);
            }
            throw new IOException("Could not save player profile " + profile.uniqueId(), exception);
        }
    }

    @Override
    public synchronized boolean delete(UUID uniqueId) throws IOException {
        ensureOpen();
        if (uniqueId == null) {
            throw new IllegalArgumentException("uniqueId must not be null");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM player_profiles WHERE player_uuid = ?")) {
            statement.setString(1, uniqueId.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IOException("Could not delete player profile " + uniqueId, exception);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Player profile repository is closed");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } catch (SQLException exception) {
            throw new IOException("Could not close player profile database", exception);
        }
    }
}
