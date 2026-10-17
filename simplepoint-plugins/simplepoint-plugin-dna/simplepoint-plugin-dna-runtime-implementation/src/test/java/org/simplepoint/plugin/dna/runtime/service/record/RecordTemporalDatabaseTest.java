package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.service.schema.JdbcPhysicalSchemaBackend;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.MysqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.PostgresqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceProvisioner;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exercises the record wire contract through generated Calcite writes and production query projection. */
class RecordTemporalDatabaseTest {

  private static final RuntimeActor ACTOR = new RuntimeActor("tenant-a", "user-a");
  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));
  private static final List<String> FIELDS = List.of("due_date", "due_time", "occurred_at");

  @Test
  void preservesTemporalValuesOnH2() throws Exception {
    verify(new DriverManagerDataSource("jdbc:h2:mem:temporal-" + UUID.randomUUID()
        + ";DB_CLOSE_DELAY=-1", "sa", ""), null, "PUBLIC", null);
  }

  @Test
  void preservesTemporalValuesOnPostgresql() throws Exception {
    verify(database("POSTGRES"), null, "public", new PostgresqlJdbcDatabaseDialect());
  }

  @Test
  void preservesTemporalValuesOnMysql() throws Exception {
    verify(database("MYSQL"), "dna_runtime", null, new MysqlJdbcDatabaseDialect());
  }

  private static DriverManagerDataSource database(final String database) {
    String prefix = "DNA_RUNTIME_TEST_" + database;
    String url = System.getenv(prefix + "_URL");
    Assumptions.assumeTrue(url != null, database + " integration URL is not configured");
    return new DriverManagerDataSource(url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
  }

  private static void verify(final DriverManagerDataSource source, final String catalog, final String schema,
      final JdbcDatabaseDialect core) throws Exception {
    String table = "spf_temporal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String timestamp = core instanceof PostgresqlJdbcDatabaseDialect ? "TIMESTAMP(6) WITH TIME ZONE"
        : core instanceof MysqlJdbcDatabaseDialect ? "DATETIME(6)" : "TIMESTAMP(6)";
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE " + table + " (id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64), "
          + "record_version BIGINT, created_by VARCHAR(64), created_org_dept_id VARCHAR(64), created_at " + timestamp
          + ", updated_by VARCHAR(64), updated_at " + timestamp + ", deleted_at " + timestamp
          + ", due_date DATE, due_time TIME(6), occurred_at " + timestamp
          + (core == null ? "" : ", payload " + (core instanceof PostgresqlJdbcDatabaseDialect ? "JSONB" : "JSON")) + ")");
    }
    try {
      run(source, catalog, schema, core, table);
    } finally {
      try (var connection = source.getConnection(); var statement = connection.createStatement()) {
        statement.execute("DROP TABLE " + table);
      }
    }
  }

  private static void run(final DriverManagerDataSource source, final String catalog, final String schema,
      final JdbcDatabaseDialect core, final String table) throws Exception {
    var dataSource = new SimpleDataSource(source);
    var codec = new PhysicalSchemaSnapshotCodec(new ObjectMapper().findAndRegisterModules());
    RuntimeSchemaDialect dialect = core == null ? H2RecordWriteFenceFixture.prepare(dataSource, "tenant-a", "model-time")
        : core instanceof PostgresqlJdbcDatabaseDialect ? new PostgresqlRuntimeSchemaDialect(codec) : new MysqlRuntimeSchemaDialect(codec);
    var target = new PhysicalSchemaTarget("model-time", new RuntimeMetadataTarget("source-time", catalog, schema, table));
    var definition = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(field("due_date", LogicalFieldType.DATE),
        field("due_time", LogicalFieldType.TIME), field("occurred_at", LogicalFieldType.DATETIME),
        field("payload", LogicalFieldType.JSON)), List.of());
    final var model = new RuntimeRecordModel("model-time", "app-time", "form-time", 1, definition, target);
    var provider = mock(ManagedRuntimeDataSourceProvider.class);
    try (var connection = source.getConnection()) {
      var metadata = connection.getMetaData();
      var context = core == null ? null : new JdbcDatabaseDialect.SupportContext(core.code(), metadata.getDriverName(),
          metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion(), catalog, schema, metadata.getIdentifierQuoteString(), Map.of());
      var identity = new DatabaseIdentity(metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion(),
          metadata.getDatabaseMajorVersion(), metadata.getDatabaseMinorVersion());
      when(provider.open(any())).thenReturn(new ManagedRuntimeDataSource(dataSource, false, context, core, dialect, identity, catalog, schema));
    }
    final var compiler = new RecordSqlCompiler(new StandardLogicalValueConverter());
    var backend = new RuntimeCalciteRecordBackend(provider, new DefaultCalciteQueryEngine());
    if (core != null) {
      new RecordWriteFencePreparation(new RecordWriteFenceProvisioner(new JdbcPhysicalSchemaBackend(provider, codec)), backend)
          .prepare(ACTOR, target);
    }
    Set<String> allowed = Set.of("due_date", "due_time", "occurred_at", "payload", "id", "created_at");
    var access = new RecordAccessContext(ACTOR, "app.temporal", allowed, allowed, Set.of(),
        new DataScopeCondition("ALL", "created_org_dept_id", "created_by", "user-a", Set.of()), "dept-a");
    var requests = mock(RuntimeRequestContext.class);
    var resolver = mock(RuntimeRecordModelResolver.class);
    var permissions = mock(RuntimeRecordAccessPolicy.class);
    when(requests.requireActor()).thenReturn(ACTOR);
    when(resolver.resolve(ACTOR, "model-time", SCHEMA)).thenReturn(model);
    when(permissions.authorize(ACTOR, model)).thenReturn(access);
    var queries = new RecordQueryServiceImpl(requests, resolver, permissions, compiler, backend, mock(RecordAuditSupport.class),
        new RecordAdmissionController(new DnaRuntimeExecutionProperties()), mock(RecordExecutionObserver.class));
    Map<String, Object> input = Map.of("due_date", "2024-02-29", "due_time", "23:59:59.123456",
        "occurred_at", "2026-10-14T09:02:03.123456+08:00");
    var created = compiler.mutation(model, access, new RecordCreateMutation("create", input), Instant.parse("2026-10-14T00:00:00.654321Z"));
    backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(created), 15_000);
    var lookup = new RecordLookupQuery("model-time", SCHEMA, created.recordId(), FIELDS);
    var values = queries.get(lookup).values();
    assertThat(values).containsExactlyInAnyOrderEntriesOf(Map.of("due_date", "2024-02-29", "due_time", "23:59:59.123456",
        "occurred_at", "2026-10-14T01:02:03.123456Z"));
    // The returned values must be valid write inputs without caller-specific JDBC parsing or precision loss.
    var update = compiler.mutation(model, access, new RecordUpdateMutation("update", created.recordId(), 0, values), Instant.now());
    backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(update), 15_000);
    assertThat(queries.get(lookup).values()).isEqualTo(values);
    var page = queries.page(new RecordPageQuery("model-time", SCHEMA, 0, 10, FIELDS, null, List.of()));
    assertThat(page.records()).hasSize(1);
    assertThat(page.records().getFirst().values()).isEqualTo(values);
    verifyTemporalOrdering(model, access, compiler, backend, queries, created.recordId());
    assertThat(LocalDate.parse(values.get("due_date").toString())).isEqualTo(LocalDate.of(2024, 2, 29));
    assertThat(LocalTime.parse(values.get("due_time").toString()).getNano()).isEqualTo(123456000);
    assertThat(Instant.parse(values.get("occurred_at").toString()).getNano()).isEqualTo(123456000);
    for (var entry : values.entrySet()) {
      var filter = new RecordFilter(RecordFilterOperator.EQUALS, entry.getKey(), List.of(entry.getValue()), List.of());
      assertThat(queries.page(new RecordPageQuery("model-time", SCHEMA, 0, 10, FIELDS, filter, List.of())).total()).isEqualTo(1);
    }
    var condition = new RecordValueCondition(RecordValueConditionOperator.EQUALS, "occurred_at",
        List.of(new LogicalValue(LogicalValueKind.DATETIME, input.get("occurred_at").toString())), List.of());
    var inspection = new RecordCompletenessQuery("model-time", "app-time", "form-time", SCHEMA, FIELDS, null, condition);
    var inspected = backend.aggregateQuery(model, compiler.completeness(model, access, inspection), 15_000);
    assertThat(((Number) inspected.rows().getFirst().getFirst()).longValue()).isEqualTo(1);
    var physicalPlan = backend.query(model, compiler.lookup(model, access, lookup), 15_000).analysis();
    assertThat(physicalPlan.planText()).contains("JdbcProject").doesNotContain("EnumerableCalc", "EnumerableProject");
    assertThat(String.join(" ", physicalPlan.pushedSqls())).containsIgnoringCase("CAST(");
    for (var entry : Map.of("due_time", "12:34:56.123456789", "occurred_at", "2026-10-14T00:00:00.123456789Z").entrySet()) {
      assertThatThrownBy(() -> compiler.mutation(model, access,
          new RecordUpdateMutation("invalid", created.recordId(), 1, Map.of(entry.getKey(), entry.getValue())), Instant.now()))
          .isInstanceOf(DnaRuntimeException.class);
    }
    Map<String, Object> midnight = Map.of("due_date", "1960-01-01", "due_time", "00:00:00.000001",
        "occurred_at", "1960-01-01T00:00:00.999999Z");
    backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(compiler.mutation(model, access,
        new RecordUpdateMutation("midnight", created.recordId(), 1, midnight), Instant.now())), 15_000);
    assertThat(queries.get(lookup).values()).isEqualTo(midnight);
    var masked = new RecordAccessContext(ACTOR, "app.temporal", Set.copyOf(FIELDS), Set.of(), Set.copyOf(FIELDS),
        access.rowScope(), "dept-a");
    when(permissions.authorize(ACTOR, model)).thenReturn(masked);
    assertThat(queries.get(lookup).values().values()).containsOnly("***");
    when(permissions.authorize(ACTOR, model)).thenReturn(access);
    Map<String, Object> empty = new LinkedHashMap<>();
    FIELDS.forEach(field -> empty.put(field, null));
    backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(compiler.mutation(model, access,
        new RecordUpdateMutation("clear", created.recordId(), 2, empty), Instant.now())), 15_000);
    assertThat(queries.get(lookup).values()).isEqualTo(empty);
    if (core != null) {
      RecordJsonDatabaseAssertions.verify(model, access, compiler, backend, queries, SCHEMA, core instanceof PostgresqlJdbcDatabaseDialect);
    }

  }

  private static void verifyTemporalOrdering(RuntimeRecordModel model, RecordAccessContext access,
      RecordSqlCompiler compiler, RuntimeCalciteRecordBackend backend, RecordQueryServiceImpl queries,
      String firstId) {
    // Opposite order for business/audit timestamps, separated by one microsecond.
    var second = compiler.mutation(model, access, new RecordCreateMutation("sort-second",
        Map.of("occurred_at", "2026-10-14T01:02:03.123457Z")),
        Instant.parse("2026-10-14T00:00:00.654320Z"));
    backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(second), 15_000);
    for (String field : List.of("occurred_at", "created_at")) {
      for (var direction : RecordSortDirection.values()) {
        boolean secondFirst = field.equals("created_at") == (direction == RecordSortDirection.ASC);
        List<String> expected = secondFirst ? List.of(second.recordId(), firstId) : List.of(firstId, second.recordId());
        for (var projected : List.of(List.of("occurred_at", "created_at"), List.of("due_date"))) {
          for (int page = 0; page < 2; page++) {
            var result = queries.page(new RecordPageQuery("model-time", SCHEMA, page, 1, projected,
                null, List.of(new RecordSort(field, direction))));
            assertThat(result.total()).isEqualTo(2);
            assertThat(result.records()).singleElement().satisfies(record -> {
              assertThat(record.id()).isEqualTo(expected.get(result.page()));
              if (projected.contains("created_at")) {
                assertThat(record.values().get("created_at")).isEqualTo(record.id().equals(firstId)
                    ? "2026-10-14T00:00:00.654321Z" : "2026-10-14T00:00:00.654320Z");
              }
            });
          }
        }
      }
    }
    backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(compiler.mutation(model, access,
        new RecordDeleteMutation("sort-cleanup", second.recordId(), 0), Instant.now())), 15_000);
  }

  private static LogicalFieldDefinition field(final String code, final LogicalFieldType type) {
    return new LogicalFieldDefinition("field-" + code, code, code, type, true, null, null, null, null);
  }
}
