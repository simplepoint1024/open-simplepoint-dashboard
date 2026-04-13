package org.simplepoint.plugin.dna.jdbc.driver;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * A minimal {@link DataSource} implementation backed by {@link DnaJdbcDriver}.
 *
 * <p>Suitable for use with connection-pool libraries (HikariCP, DBCP) or
 * frameworks that require a DataSource instead of a raw JDBC URL.
 *
 * <pre>{@code
 * DnaJdbcDataSource ds = new DnaJdbcDataSource();
 * ds.setUrl("jdbc:dna://host:port/catalog");
 * ds.setUser("user@example.com");
 * ds.setPassword("secret");
 * try (Connection c = ds.getConnection()) { ... }
 * }</pre>
 */
public final class DnaJdbcDataSource implements DataSource {

  private String url;
  private String user;
  private String password;
  private PrintWriter logWriter;
  private int loginTimeout;

  public DnaJdbcDataSource() {
  }

  public DnaJdbcDataSource(final String url) {
    this.url = url;
  }

  // ── getters / setters ───────────────────────────────────────────────

  public String getUrl() {
    return url;
  }

  public void setUrl(final String url) {
    this.url = url;
  }

  public String getUser() {
    return user;
  }

  public void setUser(final String user) {
    this.user = user;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(final String password) {
    this.password = password;
  }

  // ── DataSource ──────────────────────────────────────────────────────

  @Override
  public Connection getConnection() throws SQLException {
    return getConnection(user, password);
  }

  @Override
  public Connection getConnection(final String username, final String pwd) throws SQLException {
    if (url == null || url.isBlank()) {
      throw new SQLException("DNA JDBC URL has not been set on DnaJdbcDataSource");
    }
    Properties props = new Properties();
    if (username != null) {
      props.setProperty("user", username);
    }
    if (pwd != null) {
      props.setProperty("password", pwd);
    }
    return new DnaJdbcDriver().connect(url, props);
  }

  @Override
  public PrintWriter getLogWriter() {
    return logWriter;
  }

  @Override
  public void setLogWriter(final PrintWriter out) {
    this.logWriter = out;
  }

  @Override
  public void setLoginTimeout(final int seconds) {
    this.loginTimeout = seconds;
  }

  @Override
  public int getLoginTimeout() {
    return loginTimeout;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException("DNA JDBC driver does not provide a JUL Logger");
  }

  @Override
  public <T> T unwrap(final Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(final Class<?> iface) {
    return iface.isInstance(this);
  }
}
