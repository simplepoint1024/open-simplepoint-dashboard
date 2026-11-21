package org.simplepoint.platform.composition.verification;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** One isolated namespace in an explicitly configured disposable database. */
final class FormRelationTestDatabase implements AutoCloseable {
  final String namespace = "form_relation_" + UUID.randomUUID().toString().replace("-", "");
  final boolean postgres;
  final String catalog;
  final DataSource source;
  private final DataSource admin;

  FormRelationTestDatabase(boolean postgres) throws SQLException {
    this.postgres = postgres;
    String prefix = "DNA_RUNTIME_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
    String url = System.getenv(prefix + "_URL");
    if (url == null
        || !url.matches(
            "jdbc:(postgresql|mysql)://127\\.0\\.0\\.1:[0-9]{1,5}/dna_runtime(?:\\?.*)?")) {
      throw new IllegalArgumentException(
          "Relation tests require disposable localhost dna_runtime databases");
    }
    admin =
        new DriverManagerDataSource(
            url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
    try (var connection = admin.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + namespace);
      catalog = postgres ? connection.getCatalog() : namespace;
    }
    source =
        new AbstractDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            return scope(admin.getConnection());
          }

          @Override
          public Connection getConnection(String user, String password) throws SQLException {
            return scope(admin.getConnection(user, password));
          }

          private Connection scope(Connection connection) throws SQLException {
            try {
              if (postgres) {
                connection.setSchema(namespace);
              } else {
                connection.setCatalog(namespace);
              }
              return connection;
            } catch (SQLException failure) {
              try {
                connection.close();
              } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
              }
              throw failure;
            }
          }
        };
  }

  String schema() {
    return postgres ? namespace : null;
  }

  @Override
  public void close() throws SQLException {
    try (var connection = admin.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "DROP "
              + (postgres ? "SCHEMA " : "DATABASE ")
              + namespace
              + (postgres ? " CASCADE" : ""));
    }
  }
}
