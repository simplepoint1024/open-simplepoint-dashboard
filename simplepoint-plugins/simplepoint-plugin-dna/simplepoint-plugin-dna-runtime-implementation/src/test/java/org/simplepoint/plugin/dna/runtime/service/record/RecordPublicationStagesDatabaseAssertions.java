package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationStageFixtures.KEY;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationStageFixtures.PARENT;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationStageFixtures.SECOND;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;
import javax.sql.DataSource;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.data.calcite.core.query.CalciteSchemaConfigurer;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordPublicationStageSchema;

/** Storage/transaction evidence only: marker rows do not claim a production backfill worker exists. */
final class RecordPublicationStagesDatabaseAssertions {
  private static final int TIMEOUT = 20_000;
  private static final RecordPublicationStages STAGES = new RecordPublicationStages();
  private static final RecordPublicationStore JOURNAL = new RecordPublicationStore();
  private static final DefaultCalciteQueryEngine ENGINE = new DefaultCalciteQueryEngine();

  private RecordPublicationStagesDatabaseAssertions() {
  }

  static void verify(DataSource source, RecordWriteFence fence, Function<Connection, CalciteSchemaConfigurer> catalog)
      throws SQLException {
    var intent = RecordPublicationStageFixtures.intent();
    var initial = new RecordPublicationStore.Entry(intent, RecordPublicationCheckpoint.start(2));
    var aggregate = RecordPublicationStageFixtures.aggregate(PARENT, 65_536);
    var prepared = STAGES.prepare(intent, aggregate);
    var second = STAGES.prepare(intent, RecordPublicationStageFixtures.aggregate(SECOND, 1));
    assertThat(prepared.header().payloadBytes()).isGreaterThan(2 * 1024 * 1024);
    try (var connection = transaction(source)) {
      fence.initialize(connection, KEY, intent.actorId(), TIMEOUT);
      connection.commit();
    }
    var first = new RecordPublicationStore.Entry(intent, initial.checkpoint().batch(1, PARENT));
    for (boolean commit : new boolean[]{false, true}) {
      try (var connection = locked(source, fence); var observer = transaction(source)) {
        var sql = catalog.apply(connection);
        assertThat(update(sql, JOURNAL.insert(initial))).isEqualTo(1);
        stage(sql, prepared);
        assertThat(update(sql, JOURNAL.advance(initial, first.checkpoint(), intent.clock()))).isEqualTo(1);
        assertThat(read(catalog.apply(observer), intent, PARENT)).isNull();
        assertThat(STAGES.payload(intent, read(sql, intent, PARENT), query -> query(sql, query))).isEqualTo(aggregate);
        if (commit) {
          connection.commit();
        } else {
          connection.rollback();
        }
        if (!commit) {
          assertThat(read(catalog.apply(observer), intent, PARENT)).isNull();
          assertThat(journal(catalog.apply(observer), intent)).isNull();
          assertThat(countChunks(catalog.apply(observer), intent)).isZero();
        }
        observer.rollback();
      }
    }
    var allStaged = new RecordPublicationStore.Entry(intent, first.checkpoint().batch(1, SECOND));
    var applying = new RecordPublicationStore.Entry(intent, allStaged.checkpoint().transition(RecordPublicationCheckpoint.Phase.APPLYING));
    try (var connection = locked(source, fence)) {
      var sql = catalog.apply(connection);
      assertThat(STAGES.payload(intent, read(sql, intent, PARENT), query -> query(sql, query))).isEqualTo(aggregate);
      stage(sql, second);
      assertThat(update(sql, JOURNAL.advance(first, allStaged.checkpoint(), intent.clock()))).isEqualTo(1);
      assertThat(update(sql, JOURNAL.advance(allStaged, applying.checkpoint(), intent.clock()))).isEqualTo(1);
      assertThat(page(sql, intent, null)).containsExactly(prepared.header());
      assertThat(page(sql, intent, PARENT)).containsExactly(second.header());
      assertThat(page(sql, intent, SECOND)).isEmpty();
      connection.commit();
    }
    try (var connection = locked(source, fence)) {
      assertThatThrownBy(() -> stage(catalog.apply(connection), prepared)).isInstanceOf(RuntimeException.class);
      connection.rollback();
    }
    for (String damage : List.of("DELETE FROM \"runtime\".\"" + RecordPublicationStageSchema.CHUNKS
        + "\" WHERE \"tenant_id\" = ? AND \"chunk_index\" = 0", "UPDATE \"runtime\".\"" + RecordPublicationStageSchema.CHUNKS
        + "\" SET \"chunk_text\" = 'corrupt' WHERE \"tenant_id\" = ? AND \"chunk_index\" = 0")) {
      try (var connection = locked(source, fence)) {
        var sql = catalog.apply(connection);
        assertThat(ENGINE.executeUpdate(new CalciteUpdateRequest(damage, null, TIMEOUT, List.of(KEY.tenantId())), sql).affectedRows()).isEqualTo(2);
        assertThatThrownBy(() -> STAGES.payload(intent, prepared.header(), query -> query(sql, query))).isInstanceOf(DnaRuntimeException.class);
        connection.rollback();
      }
    }
    var applied = new RecordPublicationStore.Entry(intent, applying.checkpoint().batch(1, PARENT));
    for (boolean commit : new boolean[]{false, true}) {
      try (var connection = locked(source, fence)) {
        var sql = catalog.apply(connection);
        assertThat(read(sql, intent, PARENT)).isEqualTo(prepared.header());
        assertThat(journal(sql, intent)).isEqualTo(applying);
        assertThat(markerExists(sql)).isFalse();
        assertThat(update(sql, STAGES.advance(intent, prepared.header(), RecordPublicationStages.Phase.APPLIED, intent.clock()))).isEqualTo(1);
        marker(sql, true);
        assertThat(update(sql, JOURNAL.advance(applying, applied.checkpoint(), intent.clock()))).isEqualTo(1);
        if (commit) {
          connection.commit();
        } else {
          connection.rollback();
        }
      }
    }
    try (var connection = locked(source, fence)) {
      var sql = catalog.apply(connection);
      assertThat(update(sql, STAGES.advance(intent, prepared.header(), RecordPublicationStages.Phase.APPLIED, intent.clock()))).isZero();
      var foreign = new RecordPublicationIntent(1, intent.key(), intent.command(), "e".repeat(64), intent.previousFingerprint(),
          intent.actorId(), intent.evaluatedAt(), intent.bindings(), null);
      assertThat(update(sql, STAGES.advance(foreign, prepared.header().withPhase(RecordPublicationStages.Phase.APPLIED),
          RecordPublicationStages.Phase.RESTORED, intent.clock()))).isZero();
      var other = RecordPublicationFixtures.intent(new RecordWriteFenceKey("tenant-b", KEY.modelId()));
      assertThat(read(sql, other, PARENT)).isNull();
      assertThat(countChunks(sql, other)).isZero();
      connection.rollback();
    }
    var restoring = new RecordPublicationStore.Entry(intent, applied.checkpoint().transition(RecordPublicationCheckpoint.Phase.RESTORING));
    var restored = new RecordPublicationStore.Entry(intent, restoring.checkpoint().batch(1, PARENT));
    for (boolean commit : new boolean[]{false, true}) {
      try (var connection = locked(source, fence)) {
        var sql = catalog.apply(connection);
        assertThat(journal(sql, intent)).isEqualTo(applied);
        assertThat(markerExists(sql)).isTrue();
        var header = read(sql, intent, PARENT);
        assertThat(header.phase()).isEqualTo(RecordPublicationStages.Phase.APPLIED);
        assertThat(STAGES.payload(intent, header, query -> query(sql, query))).isEqualTo(aggregate);
        assertThat(update(sql, JOURNAL.advance(applied, restoring.checkpoint(), intent.clock()))).isEqualTo(1);
        marker(sql, false);
        assertThat(update(sql, STAGES.advance(intent, header, RecordPublicationStages.Phase.RESTORED, intent.clock()))).isEqualTo(1);
        assertThat(update(sql, JOURNAL.advance(restoring, restored.checkpoint(), intent.clock()))).isEqualTo(1);
        if (commit) {
          connection.commit();
        } else {
          connection.rollback();
        }
      }
    }
    try (var connection = locked(source, fence)) {
      var sql = catalog.apply(connection);
      assertThat(read(sql, intent, PARENT).phase()).isEqualTo(RecordPublicationStages.Phase.RESTORED);
      assertThat(read(sql, intent, SECOND).phase()).isEqualTo(RecordPublicationStages.Phase.STAGED);
      assertThat(markerExists(sql)).isFalse();
      assertThat(journal(sql, intent)).isEqualTo(restored);
      assertThat(restored.checkpoint().progress()).isNotNull();
      assertThat(STAGES.payload(intent, read(sql, intent, PARENT), query -> query(sql, query))).isEqualTo(aggregate);
      connection.rollback();
    }
  }

  private static void stage(CalciteSchemaConfigurer catalog, RecordPublicationStages.Prepared prepared) {
    for (var statement : STAGES.inserts(prepared)) {
      assertThat(update(catalog, statement)).isEqualTo(1);
    }
  }

  private static RecordPublicationStages.Header read(CalciteSchemaConfigurer catalog, RecordPublicationIntent intent, String parent) {
    return STAGES.read(intent, parent, query(catalog, STAGES.lookup(intent, parent)));
  }

  private static List<RecordPublicationStages.Header> page(CalciteSchemaConfigurer catalog, RecordPublicationIntent intent, String cursor) {
    return STAGES.readPage(intent, RecordPublicationStages.Phase.STAGED, cursor, 1,
        query(catalog, STAGES.page(intent, RecordPublicationStages.Phase.STAGED, cursor, 1)));
  }

  private static RecordPublicationStore.Entry journal(CalciteSchemaConfigurer catalog, RecordPublicationIntent intent) {
    return JOURNAL.read(intent.key(), intent.command().targetVersion(), query(catalog, JOURNAL.lookup(intent.key(), intent.command().targetVersion())));
  }

  private static long countChunks(CalciteSchemaConfigurer catalog, RecordPublicationIntent intent) {
    var plan = new RecordSelectPlan("SELECT COUNT(*) FROM \"runtime\".\"" + RecordPublicationStageSchema.CHUNKS
        + "\" WHERE \"tenant_id\" = ?", List.of(intent.key().tenantId()), List.of(), 1);
    return ((Number) query(catalog, plan).rows().getFirst().getFirst()).longValue();
  }

  private static CalciteQueryResult query(CalciteSchemaConfigurer catalog, RecordSelectPlan plan) {
    return ENGINE.execute(new CalciteQueryRequest(plan.sql(), null, plan.maxRows(), TIMEOUT, plan.parameters()), catalog);
  }

  private static long update(CalciteSchemaConfigurer catalog, RecordSqlStatement plan) {
    return ENGINE.executeUpdate(new CalciteUpdateRequest(plan.sql(), null, TIMEOUT, plan.parameters()), catalog).affectedRows();
  }

  private static void marker(CalciteSchemaConfigurer catalog, boolean insert) {
    assertThat(update(catalog, new RecordSqlStatement(insert ? "INSERT INTO \"runtime\".\"records\" (\"id\") VALUES (?)"
        : "DELETE FROM \"runtime\".\"records\" WHERE \"id\" = ?", List.of(9100), RecordMutationType.UPDATE,
        "9100", 1, RecordPublicationFixtures.AT))).isEqualTo(1);
  }

  private static boolean markerExists(CalciteSchemaConfigurer catalog) {
    return !query(catalog, new RecordSelectPlan("SELECT \"id\" FROM \"runtime\".\"records\" WHERE \"id\" = ?",
        List.of(9100), List.of(), 1)).rows().isEmpty();
  }

  private static Connection transaction(DataSource source) throws SQLException {
    var connection = source.getConnection();
    connection.setAutoCommit(false);
    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    return connection;
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
}
