package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.sql.DataSource;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteSchemaConfigurer;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;

/** Shared real PostgreSQL/MySQL assertions, invoked inside the write-fence database fixture. */
final class RecordCalculationDatabaseAssertions {
  private static final int TIMEOUT = 10_000;
  private static final RecordCalculationStore STORE = new RecordCalculationStore();
  private static final DefaultCalciteQueryEngine ENGINE = new DefaultCalciteQueryEngine();
  private static final RecordWriteFenceKey KEY = new RecordWriteFenceKey("tenant-a", "calculation-model");

  private RecordCalculationDatabaseAssertions() {
  }

  static void verify(DataSource source, RecordWriteFence fence, Function<Connection, CalciteSchemaConfigurer> catalog)
      throws SQLException {
    try (var connection = transaction(source)) {
      fence.initialize(connection, KEY, "publisher", TIMEOUT);
      connection.commit();
    }
    var first = policy(Map.of("total", "ROUND([price]*[quantity],2)"));
    try (var connection = transaction(source); var reader = transaction(source)) {
      assertThat(read(catalog.apply(connection), KEY, 0)).isNull();
      fence.publish(connection, KEY, 0, 1, "1".repeat(64), "publisher", TIMEOUT,
          locked -> insert(catalog.apply(locked), KEY, 1, first));
      assertThat(read(catalog.apply(connection), KEY, 1).policy()).isEqualTo(first);
      assertThat(read(catalog.apply(reader), KEY, 1)).isNull();
      connection.rollback();
      assertThat(read(catalog.apply(reader), KEY, 1)).isNull();
      reader.rollback();
    }
    try (var connection = transaction(source)) {
      fence.publish(connection, KEY, 0, 1, "1".repeat(64), "publisher", TIMEOUT,
          locked -> insert(catalog.apply(locked), KEY, 1, first));
      connection.commit();
    }
    try (var connection = transaction(source)) {
      var repeated = new AtomicBoolean();
      fence.publish(connection, KEY, 0, 1, "1".repeat(64), "publisher", TIMEOUT,
          locked -> repeated.set(true));
      assertThat(repeated).isFalse();
      assertThat(read(catalog.apply(connection), KEY, 1).policy()).isEqualTo(first);
      assertThat(read(catalog.apply(connection), new RecordWriteFenceKey("tenant-b", KEY.modelId()), 1)).isNull();
      assertThat(read(catalog.apply(connection), new RecordWriteFenceKey(KEY.tenantId(), "other-model"), 1)).isNull();
      connection.commit();
    }
    try (var connection = transaction(source)) {
      assertThatThrownBy(() -> insert(catalog.apply(connection), KEY, 1, policy(Map.of("total", "999"))))
          .isInstanceOf(RuntimeException.class);
      connection.rollback();
    }
    try (var connection = transaction(source)) {
      // A plain write-policy change retains the last installed formula; cancellation does too.
      fence.publish(connection, KEY, 1, 2, "2".repeat(64), "publisher", TIMEOUT, locked -> { });
      fence.cancel(connection, KEY, 2, 3, "3".repeat(64), "4".repeat(64), "publisher", new RecordExecutionDeadline(TIMEOUT));
      assertThat(read(catalog.apply(connection), KEY, 3).policy()).isEqualTo(first);
      connection.commit();
    }
    var cleared = policy(Map.of());
    try (var connection = transaction(source)) {
      fence.publish(connection, KEY, 3, 4, "5".repeat(64), "publisher", TIMEOUT,
          locked -> insert(catalog.apply(locked), KEY, 4, cleared));
      assertThat(read(catalog.apply(connection), KEY, 3).policy()).isEqualTo(first);
      assertThat(read(catalog.apply(connection), KEY, 4).policy()).isEqualTo(cleared);
      connection.commit();
    }
    try (var connection = transaction(source)) {
      fence.admitWrite(connection, KEY, 4, TIMEOUT);
      assertThat(read(catalog.apply(connection), KEY, 4).policy().definition().formulas()).isEmpty();
      connection.commit();
    }
  }

  private static RecordCalculationPolicy policy(Map<String, String> formulas) {
    return new RecordCalculationPolicy(1, "app", "form", new RecordSchemaReference(1, "a".repeat(64)),
        new RecordCalculationDefinition("1", formulas, Map.of()));
  }

  private static void insert(CalciteSchemaConfigurer catalog, RecordWriteFenceKey key, long version, RecordCalculationPolicy policy) {
    var plan = STORE.insert(key, version, "publisher", Instant.parse("2026-10-17T10:00:00Z"), policy);
    assertThat(ENGINE.executeUpdate(new CalciteUpdateRequest(plan.sql(), null, TIMEOUT, plan.parameters()), catalog).affectedRows())
        .isEqualTo(1);
  }

  private static RecordCalculationStore.Installed read(CalciteSchemaConfigurer catalog, RecordWriteFenceKey key, long version) {
    var plan = STORE.lookup(key, version);
    return STORE.read(key, version, ENGINE.execute(new CalciteQueryRequest(plan.sql(), null, plan.maxRows(),
        TIMEOUT, plan.parameters()), catalog));
  }

  private static Connection transaction(DataSource source) throws SQLException {
    var connection = source.getConnection();
    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    connection.setAutoCommit(false);
    return connection;
  }
}
