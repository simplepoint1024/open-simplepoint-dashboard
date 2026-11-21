package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Native driver contention and complete transaction rollback; isolated from application metadata.
 */
class RecordTargetContentionDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void nativeLockTimeoutRollsBackEarlierMutationBeforeRetry(boolean postgres) throws Exception {
    try (var f = new Fixture(postgres);
        var blocker = f.source.getConnection()) {
      blocker.setAutoCommit(false);
      f.change(blocker, 2);
      var attempts = new AtomicInteger();
      var diagnostics = new CopyOnWriteArrayList<String>();
      Integer completed =
          RecordTargetTransaction.executeRetrying(
              f.handle,
              new RecordExecutionDeadline(10_000),
              connection -> {
                final int number = attempts.incrementAndGet();
                try (var settings = connection.createStatement()) {
                  settings.execute(
                      postgres
                          ? "SET LOCAL lock_timeout='100ms'"
                          : "SET SESSION innodb_lock_wait_timeout=1");
                }
                f.change(connection, 1);
                try {
                  f.change(connection, 2);
                } catch (SQLException failure) {
                  diagnostics.add(failure.getSQLState() + ":" + failure.getErrorCode());
                  blocker.rollback();
                  throw failure;
                }
                return number;
              });
      assertThat(completed).isEqualTo(2);
      assertThat(diagnostics).containsExactly(postgres ? "55P03:0" : "40001:1205");
      f.assertValues(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void realDeadlockVictimRetriesWithoutRepeatingAnyCommittedEffect(boolean postgres)
      throws Exception {
    try (var f = new Fixture(postgres);
        var pool = Executors.newFixedThreadPool(2)) {
      var ready = new CountDownLatch(2);
      var attempts = List.of(new AtomicInteger(), new AtomicInteger());
      var diagnostics = new CopyOnWriteArrayList<String>();
      var one = pool.submit(() -> run(f, 1, 2, attempts.getFirst(), ready, diagnostics));
      var two = pool.submit(() -> run(f, 2, 1, attempts.getLast(), ready, diagnostics));
      assertThat(one.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(two.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(attempts.stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(3);
      assertThat(diagnostics).containsExactly(postgres ? "40P01:0" : "40001:1213");
      f.assertValues(2);
    }
  }

  private static boolean run(
      Fixture f,
      int first,
      int second,
      AtomicInteger attempts,
      CountDownLatch ready,
      List<String> diagnostics)
      throws SQLException {
    return RecordTargetTransaction.executeRetrying(
        f.handle,
        new RecordExecutionDeadline(15_000),
        connection -> {
          int attempt = attempts.incrementAndGet();
          f.change(connection, first);
          if (attempt == 1) {
            ready.countDown();
            try {
              assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            }
          }
          try {
            f.change(connection, second);
          } catch (SQLException failure) {
            diagnostics.add(failure.getSQLState() + ":" + failure.getErrorCode());
            throw failure;
          }
          return true;
        });
  }

  private static final class Fixture implements AutoCloseable {
    final SimpleDataSource source;
    final ManagedRuntimeDataSource handle;
    final String table = "contention_" + UUID.randomUUID().toString().replace("-", "");

    Fixture(boolean postgres) throws Exception {
      String prefix = "DNA_RUNTIME_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
      String url = System.getenv(prefix + "_URL");
      assumeTrue(url != null && !url.isBlank(), "Disposable database required");
      source =
          new SimpleDataSource(
              new DriverManagerDataSource(
                  url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD")));
      var dialect = mock(RuntimeSchemaDialect.class);
      when(dialect.dialectId()).thenReturn(postgres ? "postgresql" : "mysql");
      handle = new ManagedRuntimeDataSource(source, false, null, null, dialect, null, null, null);
      try (var connection = source.getConnection();
          var statement = connection.createStatement()) {
        statement.execute(
            "CREATE TABLE "
                + table
                + " (id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)"
                + (postgres ? "" : " ENGINE=InnoDB"));
        statement.execute("INSERT INTO " + table + " VALUES (1,0),(2,0)");
      }
    }

    void change(Connection connection, int id) throws SQLException {
      try (var statement =
          connection.prepareStatement("UPDATE " + table + " SET amount=amount+1 WHERE id=?")) {
        statement.setQueryTimeout(10);
        statement.setInt(1, id);
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
    }

    void assertValues(int expected) throws SQLException {
      try (var connection = source.getConnection();
          var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT amount FROM " + table + " ORDER BY id")) {
        for (int index = 0; index < 2; index++) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(expected);
        }
        assertThat(rows.next()).isFalse();
      }
    }

    @Override
    public void close() throws Exception {
      try (var connection = source.getConnection();
          var statement = connection.createStatement()) {
        statement.execute("DROP TABLE " + table);
      } finally {
        source.close();
      }
    }
  }
}
