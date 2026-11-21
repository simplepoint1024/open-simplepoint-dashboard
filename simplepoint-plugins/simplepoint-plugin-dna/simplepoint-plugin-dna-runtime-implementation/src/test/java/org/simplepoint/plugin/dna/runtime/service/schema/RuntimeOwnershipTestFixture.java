package org.simplepoint.plugin.dna.runtime.service.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.data.ownership.PhysicalObjectOwner;
import org.simplepoint.data.ownership.PhysicalOwnershipException;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Explicit ownership installation for disposable test namespaces; never adopts existing tables. */
public final class RuntimeOwnershipTestFixture {
  private RuntimeOwnershipTestFixture() {}

  /** Creates an isolated namespace; all sessions, model tables and shared tables stay inside it. */
  public static Namespace namespace(DataSource base, boolean postgres) throws SQLException {
    String name = "sp_owned_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = base.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + name);
      return new Namespace(
          base,
          scoped(base, name, postgres),
          name,
          postgres,
          postgres ? connection.getCatalog() : name);
    }
  }

  /** Owns only a disposable test namespace, not the caller's underlying datasource. */
  public record Namespace(
      DataSource base, DataSource source, String name, boolean postgres, String catalog)
      implements AutoCloseable {
    /** JDBC schema coordinate for the scoped connections. */
    public String schema() {
      return postgres ? name : null;
    }

    @Override
    public void close() throws SQLException {
      try (var connection = base.getConnection();
          var statement = connection.createStatement()) {
        statement.execute(
            "DROP " + (postgres ? "SCHEMA " : "DATABASE ") + name + (postgres ? " CASCADE" : ""));
      }
    }
  }

  /** Must be called before creating the test model's table in its disposable namespace. */
  public static PhysicalSchemaTarget reserveNewModel(
      DataSource source, PhysicalSchemaTarget target, String tenant) throws SQLException {
    try (var connection = source.getConnection()) {
      boolean postgres = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
      String namespace =
          postgres ? target.metadataTarget().schemaName() : target.metadataTarget().catalogName();
      try (var session = openOrInstall(connection, namespace)) {
        session.claimNew(
            target.metadataTarget().tableName(),
            true,
            new PhysicalObjectOwner("dna-runtime", tenant, target.physicalModelId()));
        return target.withOwnership(session.namespaceId().toString(), tenant);
      }
    }
  }

  private static PhysicalOwnershipSession openOrInstall(Connection connection, String namespace)
      throws SQLException {
    try {
      return PhysicalOwnershipSession.open(connection, namespace);
    } catch (PhysicalOwnershipException failure) {
      if (failure.code() != PhysicalOwnershipException.Code.DIRECTORY_NOT_INSTALLED) {
        throw failure;
      }
      installFresh(connection, namespace, UUID.randomUUID());
      return PhysicalOwnershipSession.open(connection, namespace);
    }
  }

  /** Explicitly installs and activates a freshly created disposable namespace. */
  public static void installFresh(Connection connection, String namespace, UUID identity)
      throws SQLException {
    PhysicalOwnershipInstaller.install(connection, namespace, identity);
    PhysicalOwnershipInstaller.activateFresh(connection, namespace, identity);
  }

  /** Supplies fresh sessions scoped to an already-created, isolated fixture namespace. */
  public static DataSource scoped(DataSource delegate, String namespace, boolean postgres) {
    return new AbstractDataSource() {
      @Override
      public Connection getConnection() throws SQLException {
        return scope(delegate.getConnection());
      }

      @Override
      public Connection getConnection(String user, String password) throws SQLException {
        return scope(delegate.getConnection(user, password));
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
}
