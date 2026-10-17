package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/** Shared real-database assertions; each query must retain aggregate pushdown in the enclosing fixture. */
final class RecordValueConditionDatabaseAssertions {
  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));
  private static final String UUID_VALUE = "28b77ee0-d897-4569-ab74-c473df701d23";
  private static final BigDecimal AMOUNT = new BigDecimal("9999999999999999999.123456780000000000");
  private static final List<String> LABELS = Arrays.asList(null, "", "a", "A", "á", "a ", "aa", "日本", "日本語",
      "😀", "é", "é", "\\x41", "x' OR 1=1 --", "\u3000");

  private RecordValueConditionDatabaseAssertions() {
  }

  static void verify(final Connection connection, final String table, final RecordInspectionServiceImpl service) throws Exception {
    seed(connection, table);
    decimalBoundaries(connection, table, service);
    for (String label : LABELS) {
      if (label != null) {
        count(service, leaf(RecordValueConditionOperator.EQUALS, "title", LogicalValueKind.STRING, label), 1);
      }
    }
    count(service, leaf(RecordValueConditionOperator.NOT_EQUALS, "title", LogicalValueKind.STRING, "a"), LABELS.size() - 1);
    count(service, leaf(RecordValueConditionOperator.IN, "title", LogicalValueKind.STRING, "a", "A"), 2);
    count(service, leaf(RecordValueConditionOperator.NOT_IN, "title", LogicalValueKind.STRING, "a", "A"), LABELS.size() - 2);
    var empty = new RecordValueCondition(RecordValueConditionOperator.IS_EMPTY, "title", List.of(), List.of());
    count(service, empty, 3);
    count(service, group(RecordValueConditionOperator.NOT, empty), LABELS.size() - 3);
    count(service, group(RecordValueConditionOperator.AND, empty,
        group(RecordValueConditionOperator.NOT, leaf(RecordValueConditionOperator.EQUALS, "title", LogicalValueKind.STRING, "a"))), 3);
    count(service, group(RecordValueConditionOperator.OR, empty,
        leaf(RecordValueConditionOperator.EQUALS, "title", LogicalValueKind.STRING, "a")), 4);
    count(service, leaf(RecordValueConditionOperator.EQUALS, "amount", LogicalValueKind.INTEGER, "9223372036854775701"), 1);
    count(service, leaf(RecordValueConditionOperator.GREATER_THAN, "amount", LogicalValueKind.INTEGER, "9223372036854775701"),
        LABELS.size() - 2);
    count(service, leaf(RecordValueConditionOperator.EQUALS, "exact_amount", LogicalValueKind.DECIMAL,
        "9999999999999999999.123456780000000001"), 1);
    count(service, leaf(RecordValueConditionOperator.GREATER_THAN, "exact_amount", LogicalValueKind.DECIMAL,
        "9999999999999999999.123456780000000001"), LABELS.size() - 2);
    count(service, leaf(RecordValueConditionOperator.EQUALS, "approved", LogicalValueKind.BOOLEAN, "true"), (LABELS.size() - 1) / 2);
    count(service, leaf(RecordValueConditionOperator.EQUALS, "reference_id", LogicalValueKind.UUID, UUID_VALUE), LABELS.size() - 1);
    count(service, leaf(RecordValueConditionOperator.EQUALS, "due_date", LogicalValueKind.DATE, "2026-10-14"), LABELS.size() - 1);
    temporal(service, "due_time", LogicalValueKind.TIME, "12:34:56.123456", "12:34:56.123456001");
    temporal(service, "occurred_at", LogicalValueKind.DATETIME, "2026-10-14T12:34:56.123456Z", "2026-10-14T12:34:56.123456001Z");
    count(service, leaf(RecordValueConditionOperator.EQUALS, "occurred_at", LogicalValueKind.DATETIME,
        "2026-10-14T20:34:56.123456+08:00"), LABELS.size() - 1);
    count(service, new RecordValueCondition(RecordValueConditionOperator.TRUE, null, List.of(), List.of()), LABELS.size());
    count(service, new RecordValueCondition(RecordValueConditionOperator.FALSE, null, List.of(), List.of()), 0);
    for (var invalid : List.of(
        leaf(RecordValueConditionOperator.EQUALS, "caption", LogicalValueKind.STRING, "a"),
        leaf(RecordValueConditionOperator.EQUALS, "tenant_id", LogicalValueKind.STRING, "tenant-a"),
        leaf(RecordValueConditionOperator.EQUALS, "amount", LogicalValueKind.STRING, "1"),
        leaf(RecordValueConditionOperator.GREATER_THAN, "title", LogicalValueKind.STRING, "a"),
        leaf(RecordValueConditionOperator.GREATER_THAN, "payload", LogicalValueKind.JSON, "{}"))) {
      assertThatThrownBy(() -> service.inspectCompleteness(query(invalid))).isInstanceOfSatisfying(DnaRuntimeException.class,
          failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_QUERY_INVALID));
    }
  }

  private static void decimalBoundaries(final Connection connection, final String table,
      final RecordInspectionServiceImpl service) throws Exception {
    for (String value : Arrays.asList(null, "0", "0.000000000000000001", "-0.000000000000000001",
        "99999999999999999999.999999999999999999", "-99999999999999999999.999999999999999999")) {
      try (var statement = connection.prepareStatement("INSERT INTO " + table
          + " (tenant_id, record_state, exact_amount) VALUES ('tenant-a', 'DECIMAL_BOUNDARY', ?)")) {
        statement.setBigDecimal(1, value == null ? null : new BigDecimal(value));
        statement.executeUpdate();
      }
    }
    decimalCount(service, RecordValueConditionOperator.EQUALS, "0.0000000000000000001", 0);
    decimalCount(service, RecordValueConditionOperator.NOT_EQUALS, "0.0000000000000000001", 6);
    decimalCount(service, RecordValueConditionOperator.LESS_THAN, "0.0000000000000000001", 3);
    decimalCount(service, RecordValueConditionOperator.GREATER_THAN_OR_EQUAL, "0.0000000000000000001", 2);
    decimalCount(service, RecordValueConditionOperator.LESS_THAN_OR_EQUAL, "-0.0000000000000000001", 2);
    decimalCount(service, RecordValueConditionOperator.GREATER_THAN, "-0.0000000000000000001", 3);
    decimalCount(service, RecordValueConditionOperator.EQUALS, "100000000000000000000", 0);
    decimalCount(service, RecordValueConditionOperator.LESS_THAN, "100000000000000000000", 5);
    decimalCount(service, RecordValueConditionOperator.GREATER_THAN, "-100000000000000000000", 5);
    decimalCount(service, RecordValueConditionOperator.GREATER_THAN, "1E+1000", 0);
    decimalCount(service, RecordValueConditionOperator.LESS_THAN, "1E+1000", 5);
  }

  private static void decimalCount(final RecordInspectionServiceImpl service, final RecordValueConditionOperator operator,
      final String value, final long expected) {
    var condition = leaf(operator, "exact_amount", LogicalValueKind.DECIMAL, value);
    var query = new RecordCompletenessQuery("model-1", "app-1", "form-1", SCHEMA, List.of("notes"),
        new RecordFilter(RecordFilterOperator.EQUALS, "record_state", List.of("DECIMAL_BOUNDARY"), List.of()), condition);
    assertThat(service.inspectCompleteness(query).recordCount()).as("decimal boundary %s %s", operator, value).isEqualTo(expected);
  }

  private static void temporal(final RecordInspectionServiceImpl service, final String field,
      final LogicalValueKind type, final String aligned, final String between) {
    count(service, leaf(RecordValueConditionOperator.EQUALS, field, type, aligned), LABELS.size() - 1);
    count(service, leaf(RecordValueConditionOperator.LESS_THAN, field, type, aligned), 0);
    count(service, leaf(RecordValueConditionOperator.GREATER_THAN, field, type, aligned), 0);
    count(service, leaf(RecordValueConditionOperator.EQUALS, field, type, between), 0);
    count(service, leaf(RecordValueConditionOperator.LESS_THAN, field, type, between), LABELS.size() - 1);
    count(service, leaf(RecordValueConditionOperator.GREATER_THAN_OR_EQUAL, field, type, between), 0);
    count(service, leaf(RecordValueConditionOperator.NOT_EQUALS, field, type, between), LABELS.size());
  }

  private static void count(final RecordInspectionServiceImpl service, final RecordValueCondition condition, final long count) {
    var result = service.inspectCompleteness(query(condition));
    assertThat(result.recordCount()).as("condition %s", condition).isEqualTo(count);
    assertThat(result.emptyCounts()).containsEntry("notes", count);
  }

  private static RecordCompletenessQuery query(final RecordValueCondition condition) {
    return new RecordCompletenessQuery("model-1", "app-1", "form-1", SCHEMA, List.of("notes"),
        new RecordFilter(RecordFilterOperator.EQUALS, "record_state", List.of("CONDITION"), List.of()), condition);
  }

  private static RecordValueCondition leaf(final RecordValueConditionOperator operator, final String field,
      final LogicalValueKind kind, final String... values) {
    return new RecordValueCondition(operator, field, Arrays.stream(values).map(value -> new LogicalValue(kind, value)).toList(), List.of());
  }

  private static RecordValueCondition group(final RecordValueConditionOperator operator, final RecordValueCondition... children) {
    return new RecordValueCondition(operator, null, List.of(), List.of(children));
  }

  private static void seed(final Connection connection, final String table) throws Exception {
    String product = connection.getMetaData().getDatabaseProductName();
    String timestamp = product.equals("MySQL") ? "DATETIME(6)"
        : product.equals("PostgreSQL") ? "TIMESTAMP(6) WITH TIME ZONE" : "TIMESTAMP(6)";
    String uuid = product.equals("PostgreSQL") ? "UUID" : "CHAR(36)";
    String sql = "INSERT INTO " + table + " (tenant_id, record_state, caption, amount, approved, exact_amount,"
        + " due_date, due_time, occurred_at, reference_id)"
        + " VALUES ('tenant-a', 'CONDITION', ?, ?, ?, ?, CAST(? AS DATE), CAST(? AS TIME(6)), CAST(? AS " + timestamp + "), CAST(? AS " + uuid + "))";
    for (int index = 0; index < LABELS.size(); index++) {
      try (var statement = connection.prepareStatement(sql)) {
        statement.setString(1, LABELS.get(index));
        statement.setObject(2, index == 0 ? null : 9223372036854775700L + index);
        statement.setObject(3, index == 0 ? null : index % 2 == 0);
        statement.setBigDecimal(4, index == 0 ? null : AMOUNT.add(BigDecimal.valueOf(index, 18)));
        statement.setString(5, index == 0 ? null : "2026-10-14");
        statement.setString(6, index == 0 ? null : "12:34:56.123456");
        statement.setString(7, index == 0 ? null : "2026-10-14 12:34:56.123456" + (product.equals("PostgreSQL") ? "+00" : ""));
        statement.setString(8, index == 0 ? null : UUID_VALUE.toUpperCase(java.util.Locale.ROOT));
        statement.executeUpdate();
      }
    }
    try (var statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO " + table + " (tenant_id, record_state, caption) VALUES ('tenant-b', 'CONDITION', 'a')");
      statement.executeUpdate("INSERT INTO " + table + " (tenant_id, record_state, caption, deleted_at)"
          + " VALUES ('tenant-a', 'CONDITION', 'a', CURRENT_TIMESTAMP)");
    }
  }
}
