package org.simplepoint.platform.installation.ownership;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Disposable native namespace; no production database or in-memory schema substitute. */
final class InstallationDatabaseFixture implements AutoCloseable {
  final String name = "install_own_" + UUID.randomUUID().toString().replace("-", "");
  final boolean postgres;
  final DriverManagerDataSource base;
  final DataSource source;

  InstallationDatabaseFixture(boolean postgres) throws SQLException {
    this.postgres = postgres;
    String prefix = "PHYSICAL_OWNERSHIP_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
    String url = System.getenv(prefix + "_URL");
    Assumptions.assumeTrue(url != null, "Disposable native database required");
    base =
        new DriverManagerDataSource(
            url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
    try (var connection = base.getConnection()) {
      executeOn(connection, "CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + name);
    }
    source =
        new AbstractDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            var connection = base.getConnection();
            try {
              if (postgres) {
                connection.setSchema(name);
              } else {
                connection.setCatalog(name);
              }
              return connection;
            } catch (SQLException failure) {
              connection.close();
              throw failure;
            }
          }

          @Override
          public Connection getConnection(String user, String password) throws SQLException {
            throw new SQLException("Credential override not part of fixture");
          }
        };
  }

  void execute(String sql) throws SQLException {
    try (var connection = source.getConnection()) {
      executeOn(connection, sql);
    }
  }

  long scalar(String sql) throws SQLException {
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      try (var rows = statement.executeQuery(sql)) {
        if (!rows.next()) {
          throw new SQLException("Missing fixture result");
        }
        return rows.getLong(1);
      }
    }
  }

  private static void executeOn(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      statement.execute(sql);
    }
  }

  @Override
  public void close() throws SQLException {
    try (var connection = base.getConnection()) {
      executeOn(
          connection,
          "DROP " + (postgres ? "SCHEMA " : "DATABASE ") + name + (postgres ? " CASCADE" : ""));
    }
  }
}
