package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationCheckpoint.Phase.APPLYING;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationCheckpoint.Phase.CANCELLED;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationCheckpoint.Phase.RESTORING;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationFixtures.AT;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationFixtures.KEY;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;
import javax.sql.DataSource;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteSchemaConfigurer;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Real physical transactions: journal/caller writes roll back together and recover on new connections. */
final class RecordPublicationDatabaseAssertions {
  private static final int TIMEOUT = 10_000;
  private static final RecordPublicationStore STORE = new RecordPublicationStore();
  private static final DefaultCalciteQueryEngine ENGINE = new DefaultCalciteQueryEngine();

  private RecordPublicationDatabaseAssertions() {
  }

  static void verify(DataSource source, RecordWriteFence fence, Function<Connection, CalciteSchemaConfigurer> catalog)
      throws SQLException {
    var initial = RecordPublicationFixtures.entry(2);
    try (var connection = transaction(source)) {
      fence.initialize(connection, KEY, "publisher", TIMEOUT);
      connection.commit();
    }
    try (var connection = locked(source, fence); var observer = transaction(source)) {
      assertThat(update(catalog.apply(connection), STORE.insert(initial))).isEqualTo(1);
      marker(catalog.apply(connection), 9001, true);
      assertThat(read(catalog.apply(connection))).isEqualTo(initial);
      assertThat(read(catalog.apply(observer))).isNull();
      connection.rollback();
      assertThat(read(catalog.apply(observer))).isNull();
      assertThat(hasMarker(catalog.apply(observer), 9001)).isFalse();
      observer.rollback();
    }
    try (var connection = locked(source, fence)) {
      assertThat(update(catalog.apply(connection), STORE.insert(initial))).isEqualTo(1);
      connection.commit();
    }
    try (var connection = locked(source, fence)) {
      assertThatThrownBy(() -> update(catalog.apply(connection), STORE.insert(initial))).isInstanceOf(RuntimeException.class);
      connection.rollback();
    }
    var first = new RecordPublicationStore.Entry(initial.intent(), initial.checkpoint().batch(1, "first"));
    try (var connection = locked(source, fence)) {
      assertThat(update(catalog.apply(connection), STORE.advance(initial, first.checkpoint(), AT))).isEqualTo(1);
      marker(catalog.apply(connection), 9001, true);
      connection.rollback();
    }
    try (var connection = locked(source, fence)) {
      assertThat(read(catalog.apply(connection))).isEqualTo(initial);
      assertThat(hasMarker(catalog.apply(connection), 9001)).isFalse();
      assertThat(update(catalog.apply(connection), STORE.advance(initial, first.checkpoint(), AT))).isEqualTo(1);
      marker(catalog.apply(connection), 9001, true);
      connection.commit();
    }
    try (var connection = locked(source, fence)) {
      var restarted = new RecordPublicationStore();
      var query = restarted.lookup(KEY, 1);
      assertThat(restarted.read(KEY, 1, ENGINE.execute(new CalciteQueryRequest(query.sql(), null, 2, TIMEOUT,
          query.parameters()), catalog.apply(connection)))).isEqualTo(first);
      assertThat(update(catalog.apply(connection), STORE.advance(initial, first.checkpoint(), AT))).isZero();
      var original = initial.intent();
      var foreign = new RecordPublicationIntent(1, KEY, original.command(), "f".repeat(64), original.previousFingerprint(),
          original.actorId(), original.evaluatedAt(), original.bindings(), original.previousCalculation());
      assertThat(update(catalog.apply(connection), STORE.advance(new RecordPublicationStore.Entry(foreign, first.checkpoint()),
          first.checkpoint().batch(1, "last"), AT))).isZero();
      var other = new RecordWriteFenceKey("tenant-b", KEY.modelId());
      query = STORE.lookup(other, 1);
      assertThat(STORE.read(other, 1, ENGINE.execute(new CalciteQueryRequest(query.sql(), null, 2, TIMEOUT,
          query.parameters()), catalog.apply(connection)))).isNull();
      connection.commit();
    }
    var staged = advance(source, fence, catalog, first, first.checkpoint().batch(1, "last"));
    var applying = advance(source, fence, catalog, staged, staged.checkpoint().transition(APPLYING));
    var applied = new RecordPublicationStore.Entry(applying.intent(), applying.checkpoint().batch(1, "first"));
    try (var connection = locked(source, fence)) {
      assertThat(update(catalog.apply(connection), STORE.advance(applying, applied.checkpoint(), AT))).isEqualTo(1);
      marker(catalog.apply(connection), 9002, true);
      connection.commit();
    }
    var failed = advance(source, fence, catalog, applied, applied.checkpoint().failed(DnaRuntimeErrorCode.RECORD_TRANSACTION_FAILED));
    var restoring = new RecordPublicationStore.Entry(failed.intent(), failed.checkpoint().transition(RESTORING));
    var restored = new RecordPublicationStore.Entry(restoring.intent(), restoring.checkpoint().batch(1, "first"));
    for (boolean commit : new boolean[]{false, true}) {
      try (var connection = locked(source, fence)) {
        assertThat(read(catalog.apply(connection))).isEqualTo(failed);
        assertThat(hasMarker(catalog.apply(connection), 9002)).isTrue();
        assertThat(update(catalog.apply(connection), STORE.advance(failed, restoring.checkpoint(), AT))).isEqualTo(1);
        marker(catalog.apply(connection), 9002, false);
        assertThat(update(catalog.apply(connection), STORE.advance(restoring, restored.checkpoint(), AT))).isEqualTo(1);
        assertThat(read(catalog.apply(connection)).checkpoint().progress()).isNotNull();
        if (commit) {
          connection.commit();
        } else {
          connection.rollback();
        }
      }
    }
    String cancellation = new RecordRequestFingerprint(new ObjectMapper()).cancellationOf(initial.intent().command());
    for (boolean commit : new boolean[]{false, true}) {
      try (var connection = locked(source, fence)) {
        assertThat(read(catalog.apply(connection))).isEqualTo(restored);
        assertThat(hasMarker(catalog.apply(connection), 9002)).isFalse();
        assertThat(update(catalog.apply(connection), STORE.advance(restored, restored.checkpoint().transition(CANCELLED), AT))).isEqualTo(1);
        assertThat(fence.cancel(connection, KEY, 0, 1, initial.intent().fingerprint(), cancellation, "publisher",
            new RecordExecutionDeadline(TIMEOUT)).cancelled()).isTrue();
        if (commit) {
          connection.commit();
        } else {
          connection.rollback();
        }
      }
    }
    try (var connection = transaction(source)) {
      assertThat(fence.admitRead(connection, KEY, TIMEOUT).revision()).isEqualTo(1);
      assertThat(read(catalog.apply(connection)).checkpoint().phase()).isEqualTo(CANCELLED);
      var audit = ENGINE.execute(new CalciteQueryRequest("SELECT CAST(\"updated_at\" AS VARCHAR) FROM \"runtime\"."
          + "\"simpoint_dna_record_publications_v1\" WHERE \"tenant_id\" = ? AND \"model_id\" = ?",
          null, 1, TIMEOUT, List.of(KEY.tenantId(), KEY.modelId())), catalog.apply(connection));
      assertThat(audit.rows().getFirst().getFirst().toString()).contains("2026-10-17 10:00:00.123456");
      var active = STORE.active(KEY);
      assertThat(STORE.readActive(KEY, ENGINE.execute(new CalciteQueryRequest(active.sql(), null, active.maxRows(), TIMEOUT,
          active.parameters()), catalog.apply(connection)))).isNull();
      assertThatThrownBy(() -> fence.lockPublication(connection, KEY, 0, new RecordExecutionDeadline(TIMEOUT)))
          .isInstanceOfSatisfying(DnaRuntimeException.class,
              error -> assertThat(error.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT));
      connection.rollback();
    }
  }

  private static RecordPublicationStore.Entry advance(DataSource source, RecordWriteFence fence,
      Function<Connection, CalciteSchemaConfigurer> catalog, RecordPublicationStore.Entry old, RecordPublicationCheckpoint next)
      throws SQLException {
    try (var connection = locked(source, fence)) {
      assertThat(update(catalog.apply(connection), STORE.advance(old, next, AT))).isEqualTo(1);
      connection.commit();
    }
    return new RecordPublicationStore.Entry(old.intent(), next);
  }

  private static RecordPublicationStore.Entry read(CalciteSchemaConfigurer catalog) {
    var plan = STORE.lookup(KEY, 1);
    return STORE.read(KEY, 1, ENGINE.execute(new CalciteQueryRequest(plan.sql(), null, plan.maxRows(), TIMEOUT, plan.parameters()), catalog));
  }

  private static long update(CalciteSchemaConfigurer catalog, RecordSqlStatement plan) {
    return ENGINE.executeUpdate(new CalciteUpdateRequest(plan.sql(), null, TIMEOUT, plan.parameters()), catalog).affectedRows();
  }

  private static void marker(CalciteSchemaConfigurer catalog, int value, boolean insert) {
    assertThat(ENGINE.executeUpdate(new CalciteUpdateRequest(insert ? "INSERT INTO \"runtime\".\"records\" (\"id\") VALUES (?)"
        : "DELETE FROM \"runtime\".\"records\" WHERE \"id\" = ?", null, TIMEOUT, List.of(value)), catalog).affectedRows()).isEqualTo(1);
  }

  private static boolean hasMarker(CalciteSchemaConfigurer catalog, int value) {
    return !ENGINE.execute(new CalciteQueryRequest("SELECT \"id\" FROM \"runtime\".\"records\" WHERE \"id\" = ?",
        null, 1, TIMEOUT, List.of(value)), catalog).rows().isEmpty();
  }

  private static Connection locked(DataSource source, RecordWriteFence fence) throws SQLException {
    var connection = transaction(source);
    try {
      fence.lockPublication(connection, KEY, 0, new RecordExecutionDeadline(TIMEOUT));
      return connection;
    } catch (SQLException | RuntimeException failure) {
      connection.close();
      throw failure;
    }
  }

  private static Connection transaction(DataSource source) throws SQLException {
    var connection = source.getConnection();
    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    connection.setAutoCommit(false);
    return connection;
  }
}
