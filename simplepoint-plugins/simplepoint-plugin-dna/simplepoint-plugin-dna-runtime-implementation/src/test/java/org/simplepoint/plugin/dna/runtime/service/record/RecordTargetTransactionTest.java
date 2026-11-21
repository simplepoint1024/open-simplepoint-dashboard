package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;

class RecordTargetTransactionTest {
  @Test
  void closesConfirmedRollbackBeforeOpeningFreshAttemptAndReturnsOnlyCommittedResult()
      throws Exception {
    var fixture = new Fixture(connection -> {});
    var calls = new AtomicInteger();
    Integer result =
        RecordTargetTransaction.executeRetrying(
            fixture.handle,
            new RecordExecutionDeadline(2000),
            connection -> {
              if (calls.incrementAndGet() < 3) {
                throw deadlock();
              }
              return calls.get();
            });
    assertThat(result).isEqualTo(3);
    assertThat(fixture.connections).hasSize(3);
    for (int index = 0; index < 2; index++) {
      verify(fixture.connections.get(index)).rollback();
      verify(fixture.connections.get(index), never()).commit();
    }
    verify(fixture.connections.getLast()).commit();
    verify(fixture.connections.getLast(), never()).rollback();
  }

  @Test
  void exhaustsThreeAttemptsAndPreservesOriginalFailure() throws Exception {
    var fixture = new Fixture(connection -> {});
    var failure = deadlock();
    assertThatThrownBy(
            () ->
                RecordTargetTransaction.executeRetrying(
                    fixture.handle,
                    new RecordExecutionDeadline(2000),
                    connection -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(fixture.connections).hasSize(3);
  }

  @Test
  void plainExecutionNeverAssumesCallbackCanReplay() throws Exception {
    var fixture = new Fixture(connection -> {});
    assertThatThrownBy(
            () ->
                RecordTargetTransaction.execute(
                    fixture.handle,
                    connection -> {
                      throw deadlock();
                    }))
        .isInstanceOf(SQLException.class);
    assertThat(fixture.connections).hasSize(1);
  }

  @Test
  void neverRetriesAfterCommitWasAttemptedEvenWithContentionCode() throws Exception {
    var failure = deadlock();
    var fixture =
        new Fixture(
            connection -> {
              try {
                doThrow(failure).when(connection).commit();
              } catch (SQLException unexpected) {
                throw new AssertionError(unexpected);
              }
            });
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                RecordTargetTransaction.executeRetrying(
                    fixture.handle,
                    new RecordExecutionDeadline(2000),
                    connection -> calls.incrementAndGet()))
        .isSameAs(failure);
    assertThat(calls).hasValue(1);
    assertThat(fixture.connections).hasSize(1);
  }

  @Test
  void failedRollbackAbortsWithoutRestoringAutocommitOrReplaying() throws Exception {
    var rollbackFailure = new SQLException("Rollback uncertain", "08006");
    var fixture =
        new Fixture(
            connection -> {
              try {
                doThrow(rollbackFailure).when(connection).rollback();
              } catch (SQLException unexpected) {
                throw new AssertionError(unexpected);
              }
            });
    var failure = deadlock();
    assertThatThrownBy(
            () ->
                RecordTargetTransaction.executeRetrying(
                    fixture.handle,
                    new RecordExecutionDeadline(2000),
                    connection -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(fixture.connections).hasSize(1);
    verify(fixture.connections.getFirst()).abort(any());
    verify(fixture.connections.getFirst(), never()).setAutoCommit(true);
    assertThat(failure.getSuppressed()).containsExactly(rollbackFailure);
  }

  @Test
  void closeFailurePreventsAnotherAttempt() throws Exception {
    var fixture =
        new Fixture(
            connection -> {
              try {
                doThrow(new SQLException("Close failed", "08006")).when(connection).close();
              } catch (SQLException unexpected) {
                throw new AssertionError(unexpected);
              }
            });
    assertThatThrownBy(
            () ->
                RecordTargetTransaction.executeRetrying(
                    fixture.handle,
                    new RecordExecutionDeadline(2000),
                    connection -> {
                      throw deadlock();
                    }))
        .hasMessage("Close failed");
    assertThat(fixture.connections).hasSize(1);
  }

  @Test
  void originalBudgetAndInterruptionPreventAnotherAttempt() throws Exception {
    var fixture = new Fixture(connection -> {});
    var deadline = new RecordExecutionDeadline(50);
    assertThatThrownBy(
            () ->
                RecordTargetTransaction.executeRetrying(
                    fixture.handle,
                    deadline,
                    connection -> {
                      try {
                        Thread.sleep(60);
                      } catch (InterruptedException unexpected) {
                        throw new AssertionError(unexpected);
                      }
                      throw deadlock();
                    }))
        .isInstanceOf(DnaRuntimeException.class)
        .hasMessageContaining("budget");
    assertThat(fixture.connections).hasSize(1);
    try {
      assertThatThrownBy(
              () ->
                  RecordTargetTransaction.executeRetrying(
                      fixture.handle,
                      new RecordExecutionDeadline(2000),
                      connection -> {
                        Thread.currentThread().interrupt();
                        throw deadlock();
                      }))
          .isInstanceOf(DnaRuntimeException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(fixture.connections).hasSize(2);
    } finally {
      Thread.interrupted();
    }
  }

  private static SQLException deadlock() {
    return new SQLException("Opaque", "40P01");
  }

  private static final class Fixture {
    final ArrayList<Connection> connections = new ArrayList<>();
    final ManagedRuntimeDataSource handle;

    Fixture(Consumer<Connection> configure) throws Exception {
      var source = mock(SimpleDataSource.class);
      var core = mock(JdbcDatabaseDialect.class);
      when(core.code()).thenReturn("postgresql");
      var dialect = mock(RuntimeSchemaDialect.class);
      when(dialect.dialectId()).thenReturn("postgresql");
      handle = new ManagedRuntimeDataSource(source, false, null, core, dialect, null, null, null);
      when(source.getConnection())
          .thenAnswer(
              call -> {
                if (!connections.isEmpty()) {
                  verify(connections.getLast()).close();
                }
                var connection = mock(Connection.class);
                when(connection.getAutoCommit()).thenReturn(true);
                when(connection.getTransactionIsolation())
                    .thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
                configure.accept(connection);
                connections.add(connection);
                return connection;
              });
    }
  }
}
