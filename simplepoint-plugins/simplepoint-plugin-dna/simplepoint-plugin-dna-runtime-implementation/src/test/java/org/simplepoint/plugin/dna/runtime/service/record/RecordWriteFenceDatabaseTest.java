package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteSchemaConfigurer;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.JdbcPhysicalSchemaBackend;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.MysqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.PostgresqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceProvisioner;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real target transaction tests; no JVM lock or control-database transaction provides serialization. */
class RecordWriteFenceDatabaseTest {

  private static final RecordWriteFenceKey KEY = new RecordWriteFenceKey("tenant-a", "model-a");
  private static final int TIMEOUT = 10_000;

  @Test
  void postgresqlFencesWritersAndRecoversPublication() throws Exception {
    verify("POSTGRES", true);
  }

  @Test
  void mysqlFencesWritersAndRecoversPublication() throws Exception {
    verify("MYSQL", false);
  }

  private void verify(final String database, final boolean postgres) throws Exception {
    String prefix = "DNA_RUNTIME_TEST_" + database;
    String url = System.getenv(prefix + "_URL");
    assumeTrue(url != null && !url.isBlank(), "Real target database is required");
    var source = new DriverManagerDataSource(url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
    String namespace = "fence_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + namespace);
    }
    try {
      Fixture fixture = fixture(source, namespace, postgres);
      verifyTransactionAndOwnership(fixture);
      verifyInitializationRace(fixture);
      verifyInFlightWrites(fixture);
      verifyRecovery(fixture);
      verifyLockTimeout(fixture);
      verifyCancellationWins(fixture);
      verifyPublicationWins(fixture);
      verifyUncommittedCancellation(fixture);
    } finally {
      try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
        statement.execute("DROP " + (postgres ? "SCHEMA " : "DATABASE ") + namespace + (postgres ? " CASCADE" : ""));
      }
    }
  }

  private static Fixture fixture(final DataSource source, final String namespace, final boolean postgres) throws Exception {
    var codec = new PhysicalSchemaSnapshotCodec(new ObjectMapper().findAndRegisterModules());
    JdbcDatabaseDialect core = postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
    RuntimeSchemaDialect dialect = postgres ? new PostgresqlRuntimeSchemaDialect(codec) : new MysqlRuntimeSchemaDialect(codec);
    JdbcDatabaseDialect.SupportContext context;
    DatabaseIdentity database;
    String catalog;
    try (Connection connection = source.getConnection()) {
      var metadata = connection.getMetaData();
      catalog = postgres ? connection.getCatalog() : namespace;
      context = new JdbcDatabaseDialect.SupportContext(core.code(), metadata.getDriverName(), metadata.getDatabaseProductName(),
          metadata.getDatabaseProductVersion(), catalog, postgres ? namespace : null, metadata.getIdentifierQuoteString(), Map.of());
      database = new DatabaseIdentity(metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion(),
          metadata.getDatabaseMajorVersion(), metadata.getDatabaseMinorVersion());
    }
    var target = new PhysicalSchemaTarget(KEY.modelId(), new RuntimeMetadataTarget("source", catalog,
        postgres ? namespace : null, "records"));
    var provider = mock(ManagedRuntimeDataSourceProvider.class);
    when(provider.open(any())).thenReturn(new ManagedRuntimeDataSource(new SimpleDataSource(source), false,
        context, core, dialect, database, catalog, postgres ? namespace : null));
    var provisioner = new RecordWriteFenceProvisioner(new JdbcPhysicalSchemaBackend(provider, codec));
    provisioner.prepare(target);
    provisioner.prepare(target);
    String records = core.qualifyName(postgres ? null : namespace, postgres ? namespace : null, "records", context);
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE " + records + " (id INTEGER PRIMARY KEY)");
    }
    return new Fixture(source, new RecordWriteFence(dialect.recordWriteFenceSql(core, context, target)),
        catalog, postgres ? namespace : null);
  }

  private static void verifyTransactionAndOwnership(final Fixture fixture) throws Exception {
    try (Connection connection = fixture.source().getConnection()) {
      assertThatThrownBy(() -> fixture.fence().admitWrite(connection, KEY, 0, TIMEOUT))
          .isInstanceOf(IllegalArgumentException.class);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      assertThatThrownBy(() -> fixture.fence().admitWrite(connection, KEY, 0, TIMEOUT))
          .isInstanceOf(IllegalArgumentException.class);
      connection.rollback();
    }
    try (Connection connection = transaction(fixture)) {
      assertThatThrownBy(() -> fixture.fence().admitWrite(connection, KEY, 0, TIMEOUT))
          .isInstanceOfSatisfying(DnaRuntimeException.class, failure ->
              assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.PERSISTENCE_FAILED));
      connection.rollback();
    }
  }

  private static void verifyInitializationRace(final Fixture fixture) throws Exception {
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var first = executor.submit(() -> initialize(fixture, start));
      var second = executor.submit(() -> initialize(fixture, start));
      start.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS).revision()).isZero();
      assertThat(second.get(10, TimeUnit.SECONDS).revision()).isZero();
    }
    try (Connection connection = transaction(fixture)) {
      assertThatThrownBy(() -> fixture.fence().admitWrite(connection,
          new RecordWriteFenceKey("tenant-b", KEY.modelId()), 0, TIMEOUT)).isInstanceOf(DnaRuntimeException.class);
      assertThatThrownBy(() -> fixture.fence().admitWrite(connection,
          new RecordWriteFenceKey(KEY.tenantId(), "model-b"), 0, TIMEOUT)).isInstanceOf(DnaRuntimeException.class);
      connection.rollback();
      fixture.fence().initialize(connection, new RecordWriteFenceKey(KEY.tenantId(), "model-b"), "publisher", TIMEOUT);
      connection.commit();
    }
  }

  private static RecordWriteFence.Snapshot initialize(final Fixture fixture, final CountDownLatch start) throws Exception {
    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    try (Connection connection = transaction(fixture)) {
      var result = fixture.fence().initialize(connection, KEY, "publisher", TIMEOUT);
      connection.commit();
      return result;
    }
  }

  private static void verifyInFlightWrites(final Fixture fixture) throws Exception {
    var publisherStarted = new CountDownLatch(1);
    var inspected = new CountDownLatch(1);
    var commitPublication = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2);
         Connection writer = transaction(fixture)) {
      fixture.fence().admitWrite(writer, KEY, 0, TIMEOUT);
      // Shared locks let unrelated records of the same model be written concurrently.
      try (Connection another = transaction(fixture)) {
        fixture.fence().admitWrite(another, KEY, 0, 1000);
        another.commit();
      }
      var publication = executor.submit(() -> {
        try (Connection connection = transaction(fixture)) {
          // Establish an earlier read before waiting: the protected check must not reuse that snapshot.
          assertThat(recordCount(fixture, connection)).isZero();
          publisherStarted.countDown();
          final var result = fixture.fence().publish(connection, KEY, 0, 1, "a".repeat(64), "publisher", TIMEOUT, locked -> {
            assertThat(locked).isNotSameAs(connection);
            assertThatThrownBy(locked::commit).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> locked.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ))
                .isInstanceOf(SQLException.class);
            assertThat(recordCount(fixture, locked)).isEqualTo(1L);
          });
          inspected.countDown();
          assertThat(commitPublication.await(5, TimeUnit.SECONDS)).isTrue();
          connection.commit();
          return result;
        }
      });
      try {
        assertThat(publisherStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> publication.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        // Even if the initiating RPC has timed out, the target transaction still owns its lock.
        var written = new DefaultCalciteQueryEngine().executeUpdate(new CalciteUpdateRequest(
            "INSERT INTO \"runtime\".\"records\" (\"id\") VALUES (?)", null, TIMEOUT, List.of(1)), catalog(fixture, writer));
        assertThat(written.affectedRows()).isEqualTo(1);
        assertThat(writer.getAutoCommit()).isFalse();
        assertThatThrownBy(() -> publication.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        writer.commit();
        assertThat(inspected.await(5, TimeUnit.SECONDS)).isTrue();
        try (Connection unrelated = transaction(fixture)) {
          fixture.fence().admitWrite(unrelated, new RecordWriteFenceKey(KEY.tenantId(), "model-b"), 0, 1000);
          unrelated.commit();
        }
        var staleStarted = new CountDownLatch(1);
        var stale = executor.submit(() -> {
          try (Connection connection = transaction(fixture)) {
            staleStarted.countDown();
            assertConflict(() -> fixture.fence().admitWrite(connection, KEY, 0, TIMEOUT));
            connection.rollback();
          }
          return true;
        });
        assertThat(staleStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> stale.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        commitPublication.countDown();
        assertThat(publication.get(5, TimeUnit.SECONDS).revision()).isEqualTo(1);
        assertThat(stale.get(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        writer.rollback();
        commitPublication.countDown();
      }
    }
  }

  private static void verifyRecovery(final Fixture fixture) throws Exception {
    try (Connection connection = transaction(fixture)) {
      assertThatThrownBy(() -> fixture.fence().publish(connection, KEY, 1, 2, "b".repeat(64), "publisher", TIMEOUT,
          locked -> {
            throw new SQLException("Impact check unavailable");
          })).isInstanceOf(SQLException.class);
      connection.rollback();
      fixture.fence().admitWrite(connection, KEY, 1, TIMEOUT);
      connection.commit();
    }
    // Closing a connection after advancing but before commit leaves the previous policy intact.
    try (Connection abandoned = transaction(fixture)) {
      fixture.fence().publish(abandoned, KEY, 1, 2, "b".repeat(64), "publisher", TIMEOUT, locked -> {});
    }
    try (Connection connection = transaction(fixture)) {
      fixture.fence().admitWrite(connection, KEY, 1, TIMEOUT);
      connection.commit();
      fixture.fence().publish(connection, KEY, 1, 2, "b".repeat(64), "publisher", TIMEOUT, locked -> {});
      connection.commit();
    }
    // Recover a committed transition whose acknowledgement was lost. A fresh transaction sees
    // its fingerprint and does not repeat the now-mutable impact check.
    try (Connection connection = transaction(fixture)) {
      var replay = fixture.fence().publish(connection, KEY, 1, 2, "b".repeat(64), "publisher", TIMEOUT,
          locked -> {
            throw new AssertionError("Committed inspection was repeated");
          });
      assertThat(replay.revision()).isEqualTo(2);
      assertThat(fixture.fence().initialize(connection, KEY, "publisher", TIMEOUT).revision()).isEqualTo(2);
      assertConflict(() -> fixture.fence().publish(connection, KEY, 0, 2, "b".repeat(64), "publisher", TIMEOUT, locked -> {}));
      assertConflict(() -> fixture.fence().publish(connection, KEY, 1, 2, "c".repeat(64), "publisher", TIMEOUT, locked -> {}));
      assertConflict(() -> fixture.fence().publish(connection, KEY, 0, 1, "a".repeat(64), "publisher", TIMEOUT, locked -> {}));
      fixture.fence().admitWrite(connection, KEY, 2, TIMEOUT);
      connection.commit();
    }
  }

  private static void verifyLockTimeout(final Fixture fixture) throws Exception {
    try (Connection publisher = transaction(fixture); Connection writer = transaction(fixture)) {
      fixture.fence().publish(publisher, KEY, 2, 3, "c".repeat(64), "publisher", TIMEOUT, locked -> {});
      assertThatThrownBy(() -> fixture.fence().admitWrite(writer, KEY, 2, 1000)).isInstanceOf(SQLException.class);
      writer.rollback();
      publisher.rollback();
      fixture.fence().admitWrite(writer, KEY, 2, TIMEOUT);
      writer.commit();
    }
  }

  private static void verifyCancellationWins(final Fixture fixture) throws Exception {
    var started = new CountDownLatch(1);
    try (Connection cancellation = transaction(fixture); ExecutorService executor = Executors.newSingleThreadExecutor()) {
      var cancelled = fixture.fence().cancel(cancellation, KEY, 2, 3, "d".repeat(64), "e".repeat(64),
          "publisher", new RecordExecutionDeadline(TIMEOUT));
      assertThat(cancelled.cancelled()).isTrue();
      assertThat(cancelled.snapshot().revision()).isEqualTo(3);
      var late = executor.submit(() -> {
        try (Connection connection = transaction(fixture)) {
          started.countDown();
          assertConflict(() -> fixture.fence().publish(connection, KEY, 2, 3, "d".repeat(64), "publisher", TIMEOUT,
              locked -> {
                throw new AssertionError("Cancelled publication reached inspection");
              }));
          connection.rollback();
        }
        return true;
      });
      try {
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> late.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        cancellation.commit();
        assertThat(late.get(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        cancellation.rollback();
      }
    }
    try (Connection connection = transaction(fixture)) {
      var replay = fixture.fence().cancel(connection, KEY, 2, 3, "d".repeat(64), "e".repeat(64),
          "publisher", new RecordExecutionDeadline(TIMEOUT));
      assertThat(replay.cancelled()).isTrue();
      assertConflict(() -> fixture.fence().admitWrite(connection, KEY, 2, TIMEOUT));
      fixture.fence().admitWrite(connection, KEY, 3, TIMEOUT);
      assertConflict(() -> fixture.fence().cancel(connection, KEY, 2, 3, "d".repeat(64), "f".repeat(64),
          "publisher", new RecordExecutionDeadline(TIMEOUT)));
      connection.commit();
    }
  }

  private static void verifyPublicationWins(final Fixture fixture) throws Exception {
    var started = new CountDownLatch(1);
    try (Connection publication = transaction(fixture); ExecutorService executor = Executors.newSingleThreadExecutor()) {
      fixture.fence().publish(publication, KEY, 3, 4, "a".repeat(64), "publisher", TIMEOUT, locked -> {});
      var cancellation = executor.submit(() -> {
        try (Connection connection = transaction(fixture)) {
          started.countDown();
          var result = fixture.fence().cancel(connection, KEY, 3, 4, "a".repeat(64), "b".repeat(64),
              "publisher", new RecordExecutionDeadline(TIMEOUT));
          connection.commit();
          return result;
        }
      });
      try {
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> cancellation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        publication.commit();
        var resolved = cancellation.get(5, TimeUnit.SECONDS);
        assertThat(resolved.cancelled()).isFalse();
        assertThat(resolved.snapshot().fingerprint()).isEqualTo("a".repeat(64));
        assertThat(resolved.snapshot().revision()).isEqualTo(4);
      } finally {
        publication.rollback();
      }
    }
  }

  private static void verifyUncommittedCancellation(final Fixture fixture) throws Exception {
    try (Connection abandoned = transaction(fixture)) {
      fixture.fence().cancel(abandoned, KEY, 4, 5, "c".repeat(64), "d".repeat(64),
          "publisher", new RecordExecutionDeadline(TIMEOUT));
      abandoned.rollback();
    }
    try (Connection connection = transaction(fixture)) {
      fixture.fence().admitWrite(connection, KEY, 4, TIMEOUT);
      var published = fixture.fence().publish(connection, KEY, 4, 5, "c".repeat(64), "publisher", TIMEOUT, locked -> {});
      assertThat(published.fingerprint()).isEqualTo("c".repeat(64));
      connection.commit();
    }
  }

  private static Connection transaction(final Fixture fixture) throws SQLException {
    Connection connection = fixture.source().getConnection();
    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    connection.setAutoCommit(false);
    return connection;
  }

  private static void assertConflict(final SqlAction action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(DnaRuntimeException.class, failure ->
        assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT));
  }

  @FunctionalInterface
  private interface SqlAction {
    void run() throws SQLException;
  }

  private static CalciteSchemaConfigurer catalog(final Fixture fixture, final Connection connection) {
    return root -> root.add("runtime", JdbcSchema.create(root, "runtime", new TransactionConnectionDataSource(connection),
        fixture.catalog(), fixture.schema()));
  }

  private static long recordCount(final Fixture fixture, final Connection connection) {
    var rows = new DefaultCalciteQueryEngine().execute(new CalciteQueryRequest(
        "SELECT COUNT(*) FROM \"runtime\".\"records\"", null, 1, TIMEOUT, List.of()), catalog(fixture, connection));
    return ((Number) rows.rows().getFirst().getFirst()).longValue();
  }

  private record Fixture(DataSource source, RecordWriteFence fence, String catalog, String schema) {
  }
}
