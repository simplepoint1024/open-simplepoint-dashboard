package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingTarget;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalIsolationMode;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Isolated real target namespace shared by registration and publication admission tests. */
final class PhysicalOwnershipDatabaseFixture implements AutoCloseable {
  final boolean postgres;
  final String namespace = "sp_ownership_" + UUID.randomUUID().toString().replace("-", "");
  final DriverManagerDataSource source;

  PhysicalOwnershipDatabaseFixture(boolean postgres) throws SQLException {
    this.postgres = postgres;
    String prefix = "DNA_RUNTIME_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
    String url = System.getenv(prefix + "_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable database is required");
    source =
        new DriverManagerDataSource(
            url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
    try (var connection = source.getConnection()) {
      execute(connection, "CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + quote(namespace));
    }
  }

  PhysicalBindingTarget placement(String id) {
    try (var connection = source.getConnection()) {
      return new PhysicalBindingTarget(
          id,
          postgres ? connection.getCatalog() : namespace,
          postgres ? namespace : null,
          PhysicalIsolationMode.SHARED_DATABASE);
    } catch (SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }

  PhysicalModelOwnershipRegistry registry() throws SQLException {
    return new PhysicalModelOwnershipRegistry(provider());
  }

  ManagedRuntimeDataSourceProvider provider() throws SQLException {
    var provider = mock(ManagedRuntimeDataSourceProvider.class);
    try (var connection = source.getConnection()) {
      var metadata = connection.getMetaData();
      JdbcDatabaseDialect core =
          postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
      var codec = new PhysicalSchemaSnapshotCodec(new ObjectMapper());
      RuntimeSchemaDialect dialect =
          postgres
              ? new PostgresqlRuntimeSchemaDialect(codec)
              : new MysqlRuntimeSchemaDialect(codec);
      var context =
          new JdbcDatabaseDialect.SupportContext(
              core.code(),
              metadata.getDriverName(),
              metadata.getDatabaseProductName(),
              metadata.getDatabaseProductVersion(),
              connection.getCatalog(),
              postgres ? namespace : null,
              metadata.getIdentifierQuoteString(),
              Map.of());
      var database =
          new DatabaseIdentity(
              metadata.getDatabaseProductName(),
              metadata.getDatabaseProductVersion(),
              metadata.getDatabaseMajorVersion(),
              metadata.getDatabaseMinorVersion());
      when(provider.open(any()))
          .thenReturn(
              new ManagedRuntimeDataSource(
                  new SimpleDataSource(source),
                  false,
                  context,
                  core,
                  dialect,
                  database,
                  postgres ? connection.getCatalog() : namespace,
                  postgres ? namespace : null));
    }
    return provider;
  }

  String quote(String value) {
    String quote = postgres ? "\"" : "`";
    return quote + value.replace(quote, quote + quote) + quote;
  }

  String table(String table) {
    return quote(namespace) + "." + quote(table);
  }

  void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      // Fixture installation/cleanup can wait for fsync on shared CI disks. Lock-contention
      // assertions use their own bounded admission timeout, independent of this DDL budget.
      statement.setQueryTimeout(120);
      statement.execute(sql);
    }
  }

  @Override
  public void close() throws SQLException {
    try (var connection = source.getConnection()) {
      execute(
          connection,
          "DROP "
              + (postgres ? "SCHEMA " : "DATABASE ")
              + quote(namespace)
              + (postgres ? " CASCADE" : ""));
    }
  }
}
