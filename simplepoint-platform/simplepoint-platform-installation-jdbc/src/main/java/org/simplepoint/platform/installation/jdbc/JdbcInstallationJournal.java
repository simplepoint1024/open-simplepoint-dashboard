package org.simplepoint.platform.installation.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.platform.installation.InstallationException;
import org.simplepoint.platform.installation.InstallationException.Code;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.platform.installation.InstallationJournal;
import org.simplepoint.platform.installation.InstallationPlan;

/**
 * Short JDBC transactions serialize one target; domain actions execute outside these transactions.
 */
public final class JdbcInstallationJournal implements InstallationJournal {
  private final DataSource source;

  /** The supplied store belongs to the installer, not any business module. */
  public JdbcInstallationJournal(DataSource source) {
    this.source = Objects.requireNonNull(source);
  }

  @Override
  public Snapshot open(InstallationPlan plan) {
    return transaction(
        connection -> {
          var savepoint = connection.setSavepoint();
          try {
            update(
                connection,
                "insert into simpoint_installation_targets(target_id,generation) values (?,0)",
                plan.target());
          } catch (SQLException collision) {
            if (!"23505".equals(collision.getSQLState())) {
              throw collision;
            }
            connection.rollback(savepoint);
          } finally {
            connection.releaseSavepoint(savepoint);
          }
          var target = target(connection, plan);
          var operation = operation(connection, plan);
          if (operation != null) {
            verify(plan, target, operation);
            return snapshot(connection, plan, operation);
          }
          if (((Number) target[0]).longValue() != plan.expectedGeneration()) {
            throw failure(Code.GENERATION_CONFLICT);
          }
          if (target[1] != null
              && !Boolean.TRUE.equals(
                  rows(
                          connection,
                          "select complete from simpoint_installation_operations where"
                              + " operation_id=?",
                          target[1])
                      .getFirst()[0])) {
            throw failure(Code.OPERATION_IN_PROGRESS);
          }
          long generation = plan.expectedGeneration() + 1;
          update(
              connection,
              "insert into simpoint_installation_operations"
                  + " (operation_id,target_id,generation,plan_fingerprint,release_fingerprint,complete)"
                  + " values (?,?,?,?,?,false)",
              plan.operationId().toString(),
              plan.target(),
              generation,
              plan.fingerprint(),
              plan.releaseFingerprint());
          for (int index = 0; index < plan.steps().size(); index++) {
            var step = plan.steps().get(index);
            update(
                connection,
                "insert into simpoint_installation_steps"
                    + " (operation_id,step_index,step_id,adapter_kind,adapter_version,input_fingerprint,retry_mode,state)"
                    + " values (?,?,?,?,?,?,?,'PENDING')",
                plan.operationId().toString(),
                index,
                step.id(),
                step.kind(),
                step.version(),
                step.inputFingerprint(),
                step.retry().name());
          }
          update(
              connection,
              "update simpoint_installation_targets set generation=?,current_operation=? where"
                  + " target_id=?",
              generation,
              plan.operationId().toString(),
              plan.target());
          return snapshot(connection, plan, operation(connection, plan));
        });
  }

  @Override
  public Snapshot read(InstallationPlan plan) {
    return transaction(
        connection -> {
          var operation = requireCurrent(connection, plan);
          return snapshot(connection, plan, operation);
        });
  }

  @Override
  public Attempt claim(InstallationPlan plan, int index) {
    return transaction(
        connection -> {
          var operation = requireCurrent(connection, plan);
          var snapshot = snapshot(connection, plan, operation);
          var progress = unfinished(snapshot, index);
          if (progress.state() != State.PENDING
              && !(progress.state() == State.FAILED
                  && plan.steps().get(index).retry()
                      == InstallationPlan.Retry.DURABLE_IDEMPOTENT)) {
            throw failure(Code.RECOVERY_REQUIRED);
          }
          var token = UUID.randomUUID();
          update(
              connection,
              "insert into simpoint_installation_attempts(attempt_id,operation_id,step_index,state)"
                  + " values (?,?,?,'RUNNING')",
              token.toString(),
              plan.operationId().toString(),
              index);
          update(
              connection,
              "update simpoint_installation_steps set"
                  + " state='RUNNING',attempt_id=?,receipt_fingerprint=null where operation_id=?"
                  + " and step_index=?",
              token.toString(),
              plan.operationId().toString(),
              index);
          return new Attempt(index, token);
        });
  }

  @Override
  public void complete(InstallationPlan plan, Attempt attempt, String receiptFingerprint) {
    InstallationFingerprint.require(receiptFingerprint);
    transaction(
        connection -> {
          var operation = requireCurrent(connection, plan);
          var snapshot = snapshot(connection, plan, operation);
          var progress = matching(snapshot, attempt);
          if (progress.state() == State.COMPLETE) {
            if (!receiptFingerprint.equals(progress.receiptFingerprint())) {
              throw failure(Code.STEP_CONFLICT);
            }
            return null;
          }
          unfinished(snapshot, attempt.index());
          if (progress.state() != State.RUNNING && progress.state() != State.FAILED) {
            throw failure(Code.STEP_CONFLICT);
          }
          update(
              connection,
              "update simpoint_installation_attempts set state='COMPLETE',receipt_fingerprint=?,"
                  + " completed_at=current_timestamp where attempt_id=?",
              receiptFingerprint,
              attempt.token().toString());
          update(
              connection,
              "update simpoint_installation_steps set state='COMPLETE',receipt_fingerprint=?"
                  + " where operation_id=? and step_index=?",
              receiptFingerprint,
              plan.operationId().toString(),
              attempt.index());
          return null;
        });
  }

  @Override
  public void failed(InstallationPlan plan, Attempt attempt) {
    transaction(
        connection -> {
          var operation = requireCurrent(connection, plan);
          var snapshot = snapshot(connection, plan, operation);
          var progress = matching(snapshot, attempt);
          unfinished(snapshot, attempt.index());
          if (progress.state() != State.RUNNING) {
            throw failure(Code.STEP_CONFLICT);
          }
          update(
              connection,
              "update simpoint_installation_attempts set state='FAILED',error_code='ACTION_FAILED',"
                  + " completed_at=current_timestamp where attempt_id=?",
              attempt.token().toString());
          update(
              connection,
              "update simpoint_installation_steps set state='FAILED' where operation_id=? and"
                  + " step_index=?",
              plan.operationId().toString(),
              attempt.index());
          return null;
        });
  }

  @Override
  public Snapshot finish(InstallationPlan plan) {
    return transaction(
        connection -> {
          var operation = requireCurrent(connection, plan);
          var snapshot = snapshot(connection, plan, operation);
          if (snapshot.steps().stream().anyMatch(step -> step.state() != State.COMPLETE)) {
            throw failure(Code.STEP_CONFLICT);
          }
          if (!snapshot.complete()) {
            update(
                connection,
                "update simpoint_installation_operations set"
                    + " complete=true,completed_at=current_timestamp where operation_id=?",
                plan.operationId().toString());
          }
          return new Snapshot(snapshot.generation(), true, snapshot.steps());
        });
  }

  @Override
  public void authorizeRetry(InstallationPlan plan, Attempt attempt, String evidence) {
    InstallationFingerprint.require(evidence);
    transaction(
        connection -> {
          var operation = requireCurrent(connection, plan);
          var snapshot = snapshot(connection, plan, operation);
          var progress = matching(snapshot, attempt);
          unfinished(snapshot, attempt.index());
          if (progress.state() != State.RUNNING
              || plan.steps().get(attempt.index()).retry()
                  != InstallationPlan.Retry.DURABLE_IDEMPOTENT) {
            throw failure(Code.RECOVERY_REQUIRED);
          }
          update(
              connection,
              "update simpoint_installation_attempts set"
                  + " state='RETRY_AUTHORIZED',retirement_evidence=?,"
                  + " completed_at=current_timestamp where attempt_id=?",
              evidence,
              attempt.token().toString());
          update(
              connection,
              "update simpoint_installation_steps set state='PENDING',attempt_id=null where"
                  + " operation_id=? and step_index=?",
              plan.operationId().toString(),
              attempt.index());
          return null;
        });
  }

  private static Progress matching(Snapshot snapshot, Attempt attempt) {
    if (attempt.index() < 0 || attempt.index() >= snapshot.steps().size()) {
      throw failure(Code.STEP_CONFLICT);
    }
    var progress = snapshot.steps().get(attempt.index());
    if (attempt.token() == null || !attempt.token().equals(progress.attempt())) {
      throw failure(Code.STEP_CONFLICT);
    }
    return progress;
  }

  private static Progress unfinished(Snapshot snapshot, int index) {
    if (snapshot.complete()
        || index < 0
        || index >= snapshot.steps().size()
        || snapshot.steps().get(index).state() == State.COMPLETE
        || snapshot.steps().subList(0, index).stream()
            .anyMatch(step -> step.state() != State.COMPLETE)) {
      throw failure(Code.STEP_CONFLICT);
    }
    return snapshot.steps().get(index);
  }

  private Object[] requireCurrent(Connection connection, InstallationPlan plan)
      throws SQLException {
    var target = target(connection, plan);
    var operation = operation(connection, plan);
    if (operation == null) {
      throw failure(Code.PLAN_CONFLICT);
    }
    verify(plan, target, operation);
    return operation;
  }

  private static void verify(InstallationPlan plan, Object[] target, Object[] operation) {
    if (!plan.target().equals(operation[0]) || !plan.fingerprint().equals(operation[2])) {
      throw failure(Code.PLAN_CONFLICT);
    }
    if (!plan.operationId().toString().equals(target[1])
        || ((Number) operation[1]).longValue() != ((Number) target[0]).longValue()) {
      throw failure(Code.GENERATION_CONFLICT);
    }
  }

  private Object[] target(Connection connection, InstallationPlan plan) throws SQLException {
    var rows =
        rows(
            connection,
            "select generation,current_operation from simpoint_installation_targets where"
                + " target_id=? for update",
            plan.target());
    if (rows.isEmpty()) {
      throw failure(Code.PLAN_CONFLICT);
    }
    return rows.getFirst();
  }

  private Object[] operation(Connection connection, InstallationPlan plan) throws SQLException {
    var rows =
        rows(
            connection,
            "select target_id,generation,plan_fingerprint,complete from"
                + " simpoint_installation_operations where operation_id=?",
            plan.operationId().toString());
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Snapshot snapshot(Connection connection, InstallationPlan plan, Object[] operation)
      throws SQLException {
    var steps = new ArrayList<Progress>();
    for (var row :
        rows(
            connection,
            "select step_index,state,attempt_id,receipt_fingerprint from"
                + " simpoint_installation_steps where operation_id=? order by step_index",
            plan.operationId().toString())) {
      if (((Number) row[0]).intValue() != steps.size()) {
        throw failure(Code.PLAN_CONFLICT);
      }
      steps.add(
          new Progress(
              State.valueOf((String) row[1]),
              row[2] == null ? null : UUID.fromString((String) row[2]),
              (String) row[3]));
    }
    if (steps.size() != plan.steps().size()) {
      throw failure(Code.PLAN_CONFLICT);
    }
    return new Snapshot(
        ((Number) operation[1]).longValue(), Boolean.TRUE.equals(operation[3]), steps);
  }

  private <T> T transaction(SqlWork<T> work) {
    try (var connection = source.getConnection()) {
      if (!connection.getAutoCommit()) {
        throw failure(Code.JOURNAL_UNAVAILABLE);
      }
      connection.setAutoCommit(false);
      try {
        T result = work.apply(connection);
        connection.commit();
        return result;
      } catch (RuntimeException | SQLException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollback) {
          failure.addSuppressed(rollback);
        }
        throw failure;
      }
    } catch (SQLException sql) {
      var failure =
          failure(
              "23505".equals(sql.getSQLState()) ? Code.PLAN_CONFLICT : Code.JOURNAL_UNAVAILABLE);
      failure.initCause(sql);
      throw failure;
    }
  }

  private static int update(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (var statement = statement(connection, sql, parameters)) {
      return statement.executeUpdate();
    }
  }

  private static List<Object[]> rows(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (var statement = statement(connection, sql, parameters);
        var results = statement.executeQuery()) {
      var rows = new ArrayList<Object[]>();
      int columns = results.getMetaData().getColumnCount();
      while (results.next()) {
        var row = new Object[columns];
        for (int i = 0; i < columns; i++) {
          row[i] = results.getObject(i + 1);
        }
        rows.add(row);
      }
      return rows;
    }
  }

  private static PreparedStatement statement(
      Connection connection, String sql, Object... parameters) throws SQLException {
    var statement = connection.prepareStatement(sql);
    try {
      statement.setQueryTimeout(10);
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      return statement;
    } catch (SQLException failure) {
      statement.close();
      throw failure;
    }
  }

  private static InstallationException failure(Code code) {
    return new InstallationException(code);
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T apply(Connection connection) throws SQLException;
  }
}
