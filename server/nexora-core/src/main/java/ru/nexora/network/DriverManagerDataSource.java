package ru.nexora.network;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/** Minimal standard-JDBC DataSource configured entirely by deployment environment. */
public final class DriverManagerDataSource implements DataSource {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public DriverManagerDataSource(String jdbcUrl, String username, String password) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            throw new IllegalArgumentException("A JDBC URL is required");
        }
        this.jdbcUrl = jdbcUrl;
        this.username = username == null || username.isBlank() ? null : username;
        this.password = password == null || password.isBlank() ? null : password;
        if ((this.username == null) != (this.password == null)) {
            throw new IllegalArgumentException("JDBC username and password must be configured together");
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return username == null ? DriverManager.getConnection(jdbcUrl)
                : DriverManager.getConnection(jdbcUrl, username, password);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return DriverManager.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { DriverManager.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { DriverManager.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return DriverManager.getLoginTimeout(); }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return Logger.getLogger("ru.nexora.network.jdbc");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        Objects.requireNonNull(iface, "iface");
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }
}
