package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.MysqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.PostgresqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeOwnershipTestFixture;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs the real aggregate SQL through Calcite, including historical field mapping and Unicode. */
class RecordCompletenessDatabaseTest {

  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));
  private static final List<String> FIELDS =
      List.of("title", "notes", "amount", "approved", "payload");

  @AfterEach
  void clearContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }

  @Test
  void inspectsThroughCalciteOnH2() throws Exception {
    verifyDatabase(
        new DriverManagerDataSource(
            "jdbc:h2:mem:completeness-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""),
        null,
        "PUBLIC",
        mock(RuntimeSchemaDialect.class, org.mockito.Mockito.CALLS_REAL_METHODS),
        "JSON");
  }

  @Test
  void inspectsThroughCalciteOnPostgresql() throws Exception {
    String prefix = "DNA_RUNTIME_TEST_POSTGRES";
    Assumptions.assumeTrue(
        System.getenv(prefix + "_URL") != null, "PostgreSQL integration URL is not configured");
    verifyDatabase(
        source(prefix), null, "public", new PostgresqlRuntimeSchemaDialect(codec()), "JSONB");
  }

  @Test
  void inspectsThroughCalciteOnMysql() throws Exception {
    String prefix = "DNA_RUNTIME_TEST_MYSQL";
    Assumptions.assumeTrue(
        System.getenv(prefix + "_URL") != null, "MySQL integration URL is not configured");
    verifyDatabase(
        source(prefix), "dna_runtime", null, new MysqlRuntimeSchemaDialect(codec()), "JSON");
  }

  private static void verifyDatabase(
      final DriverManagerDataSource source,
      final String catalog,
      final String schema,
      final RuntimeSchemaDialect dialect,
      final String jsonType)
      throws Exception {
    if (source.getUrl().startsWith("jdbc:h2:")) {
      verifyScoped(source, catalog, schema, dialect, jsonType, true);
    } else {
      try (var namespace =
          RuntimeOwnershipTestFixture.namespace(
              source, dialect instanceof PostgresqlRuntimeSchemaDialect)) {
        verifyScoped(
            namespace.source(), namespace.catalog(), namespace.schema(), dialect, jsonType, false);
      }
    }
  }

  private static void verifyScoped(
      DataSource source,
      String catalog,
      String schema,
      RuntimeSchemaDialect dialect,
      String jsonType,
      boolean h2)
      throws Exception {
    String table = "spf_check_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    var unowned =
        new PhysicalSchemaTarget(
            "model-1", new RuntimeMetadataTarget("source-1", catalog, schema, table));
    var target =
        h2 ? unowned : RuntimeOwnershipTestFixture.reserveNewModel(source, unowned, "tenant-a");
    try (Connection connection = source.getConnection()) {
      try (var statement = connection.createStatement()) {
        statement.execute(
            "CREATE TABLE "
                + table
                + " (tenant_id VARCHAR(64), deleted_at TIMESTAMP, "
                + "caption VARCHAR(512), notes TEXT, amount BIGINT, approved BOOLEAN, payload "
                + jsonType
                + ", record_state VARCHAR(32), exact_amount DECIMAL(38,18), due_date DATE, due_time"
                + " TIME(6), occurred_at "
                + ("JSONB".equals(jsonType) ? "TIMESTAMP(6) WITH TIME ZONE" : "TIMESTAMP(6)")
                + ", reference_id "
                + ("JSONB".equals(jsonType) ? "UUID" : "CHAR(36)")
                + ")");
      }
      try {
        RecordInspectionServiceImpl service = service(source, catalog, schema, dialect, target, h2);
        authorize(true);
        var query = query("app-1", "form-1", FIELDS);
        assertThat(service.inspectCompleteness(query).recordCount()).isZero();
        assertThat(service.inspectCompleteness(query).emptyCounts().values()).containsOnly(0L);

        List<String> blanks = new ArrayList<>();
        blanks.add(null);
        blanks.add("");
        // Independent enumeration of the language's whitespace set, including alternating runs.
        int[] spaces = {
          9, 10, 11, 12, 13, 32, 160, 5760, 8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 8200,
          8201, 8202, 8232, 8233, 8239, 8287, 12288, 65279
        };
        for (int space : spaces) {
          blanks.add(Character.toString(space));
        }
        blanks.add(" \t\u3000\u00A0\uFEFF\n \u3000");
        for (String blank : blanks) {
          insert(connection, table, "tenant-a", false, "DRAFT", blank);
        }
        List<String> nonblank = List.of("\u0085", "\u200B", "x", "0", "日 本");
        for (String text : nonblank) {
          insert(connection, table, "tenant-a", false, "DRAFT", text);
        }
        insert(connection, table, "tenant-b", false, "DRAFT", null);
        insert(connection, table, "tenant-a", true, "DRAFT", null);
        insert(connection, table, "tenant-a", false, "SUBMITTED", null);

        var result = service.inspectCompleteness(query);
        assertThat(result.recordCount()).isEqualTo(blanks.size() + nonblank.size());
        assertThat(result.emptyCounts())
            .containsExactlyInAnyOrderEntriesOf(
                Map.of(
                    "title",
                    (long) blanks.size(),
                    "notes",
                    (long) blanks.size(),
                    "amount",
                    1L,
                    "approved",
                    1L,
                    "payload",
                    1L));
        // No caller row/field policy can accidentally hide incompatible records from management
        // checks.
        assertThatThrownBy(() -> service.inspectCompleteness(query("wrong-app", "form-1", FIELDS)))
            .isInstanceOfSatisfying(
                DnaRuntimeException.class,
                failure ->
                    assertThat(failure.errorCode())
                        .isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
        assertThatThrownBy(() -> service.inspectCompleteness(query("app-1", "wrong-form", FIELDS)))
            .isInstanceOfSatisfying(
                DnaRuntimeException.class,
                failure ->
                    assertThat(failure.errorCode())
                        .isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
        for (String invalid : List.of("unknown", "caption", "tenant_id")) {
          assertThatThrownBy(
                  () -> service.inspectCompleteness(query("app-1", "form-1", List.of(invalid))))
              .isInstanceOf(DnaRuntimeException.class);
        }
        RecordValueConditionDatabaseAssertions.verify(connection, table, service);
        authorize(false);
        assertThatThrownBy(() -> service.inspectCompleteness(query))
            .isInstanceOfSatisfying(
                DnaRuntimeException.class,
                failure ->
                    assertThat(failure.errorCode())
                        .isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
      } finally {
        try (var statement = connection.createStatement()) {
          statement.execute("DROP TABLE " + table);
        }
      }
    }
  }

  private static void insert(
      final Connection connection,
      final String table,
      final String tenant,
      final boolean deleted,
      final String state,
      final String title)
      throws Exception {
    try (var statement =
        connection.prepareStatement(
            "INSERT INTO "
                + table
                + " (tenant_id, deleted_at, caption, notes, amount, approved, payload,"
                + " record_state) VALUES (?, ?, ?, ?, ?, ?, "
                + (title == null ? "NULL" : "'{}'")
                + ", ?)")) {
      statement.setString(1, tenant);
      statement.setTimestamp(
          2, deleted ? Timestamp.from(Instant.parse("2026-10-14T00:00:00Z")) : null);
      statement.setString(3, title);
      statement.setString(4, title);
      statement.setObject(5, title == null ? null : 0L);
      statement.setObject(6, title == null ? null : false);
      statement.setString(7, state);
      statement.executeUpdate();
    }
  }

  private static RecordInspectionServiceImpl service(
      final DataSource source,
      final String catalog,
      final String schema,
      final RuntimeSchemaDialect dialect,
      final PhysicalSchemaTarget target,
      final boolean h2)
      throws Exception {
    var requested = schema("title");
    var model =
        new RuntimeRecordModel(
            "model-1", "app-1", "form-1", 2, schema("caption"), 1, requested, target);
    var resolver = mock(RuntimeRecordModelResolver.class);
    when(resolver.resolve(any(), any(), any())).thenReturn(model);
    var provider = mock(ManagedRuntimeDataSourceProvider.class);
    org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect core = null;
    org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect.SupportContext context = null;
    org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity identity = null;
    if (h2) {
      var locks = H2RecordWriteFenceFixture.prepare(source, "tenant-a", model.physicalModelId());
      org.mockito.Mockito.doReturn(locks.recordWriteFenceSql(null, null, model.target()))
          .when(dialect)
          .recordWriteFenceSql(any(), any(), any());
    } else {
      core =
          dialect instanceof PostgresqlRuntimeSchemaDialect
              ? new org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect()
              : new org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect();
      try (var connection = source.getConnection()) {
        var metadata = connection.getMetaData();
        context =
            new org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect.SupportContext(
                core.code(),
                metadata.getDriverName(),
                metadata.getDatabaseProductName(),
                metadata.getDatabaseProductVersion(),
                catalog,
                schema,
                metadata.getIdentifierQuoteString(),
                Map.of());
        identity =
            new org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity(
                metadata.getDatabaseProductName(),
                metadata.getDatabaseProductVersion(),
                metadata.getDatabaseMajorVersion(),
                metadata.getDatabaseMinorVersion());
      }
    }
    when(provider.open(any()))
        .thenReturn(
            new ManagedRuntimeDataSource(
                new SimpleDataSource(source),
                false,
                context,
                core,
                dialect,
                identity,
                catalog,
                schema));
    if (core != null) {
      var ddl =
          new org.simplepoint.plugin.dna.runtime.service.schema.JdbcPhysicalSchemaBackend(
              provider, codec());
      new org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceProvisioner(ddl)
          .prepare(model.target());
      new RuntimeCalciteRecordBackend(provider, new DefaultCalciteQueryEngine())
          .initializeWriteFence(
              new RuntimeRequestContext.RuntimeActor("tenant-a", "manager"), model.target());
    }
    var engine = spy(new DefaultCalciteQueryEngine());
    doAnswer(
            invocation -> {
              CalciteQueryResult result = (CalciteQueryResult) invocation.callRealMethod();
              if (((org.simplepoint.data.calcite.core.query.CalciteQueryRequest)
                      invocation.getArgument(0))
                  .sql()
                  .matches("(?s).*simpoint_dna_record_(publications|calculations)_v1.*")) {
                return result;
              }
              assertThat(result.analysis().planText()).doesNotContain("EnumerableAggregate");
              if (result.analysis().pushedSqls().isEmpty()) {
                assertThat(result.analysis().planText())
                    .contains("Values")
                    .doesNotContain("TableScan");
                assertThat(((Number) result.rows().getFirst().getFirst()).longValue()).isZero();
              } else {
                assertThat(result.analysis().pushedSqls())
                    .anySatisfy(
                        sql ->
                            assertThat(sql)
                                .containsIgnoringCase("COUNT(")
                                .containsIgnoringCase("SUM("));
              }
              return result;
            })
        .when(engine)
        .execute(any(), any());
    return new RecordInspectionServiceImpl(
        new RuntimeRequestContext(),
        resolver,
        new RecordSqlCompiler(new StandardLogicalValueConverter()),
        new RuntimeCalciteRecordBackend(provider, engine),
        new RecordAdmissionController(new DnaRuntimeExecutionProperties()),
        mock(RecordAuditSupport.class),
        mock(RecordExecutionObserver.class));
  }

  private static PhysicalSchemaDefinition schema(final String titleCode) {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(
            new LogicalFieldDefinition(
                "field-title",
                titleCode,
                "Title",
                LogicalFieldType.STRING,
                true,
                512,
                null,
                null,
                null),
            field("notes", LogicalFieldType.TEXT),
            field("amount", LogicalFieldType.INT64),
            field("approved", LogicalFieldType.BOOLEAN),
            field("payload", LogicalFieldType.JSON),
            new LogicalFieldDefinition(
                "field-exact",
                "exact_amount",
                "Exact",
                LogicalFieldType.DECIMAL,
                true,
                null,
                "title".equals(titleCode) ? 20 : 38,
                "title".equals(titleCode) ? 2 : 18,
                null),
            field("due_date", LogicalFieldType.DATE),
            field("due_time", LogicalFieldType.TIME),
            field("occurred_at", LogicalFieldType.DATETIME),
            field("reference_id", LogicalFieldType.UUID),
            new LogicalFieldDefinition(
                "field-state",
                "record_state",
                "State",
                LogicalFieldType.STRING,
                true,
                32,
                null,
                null,
                null)),
        List.of());
  }

  private static LogicalFieldDefinition field(final String code, final LogicalFieldType type) {
    return new LogicalFieldDefinition(
        "field-" + code, code, code, type, true, null, null, null, null);
  }

  private static RecordCompletenessQuery query(
      final String app, final String form, final List<String> fields) {
    return new RecordCompletenessQuery(
        "model-1",
        app,
        form,
        SCHEMA,
        fields,
        new RecordFilter(RecordFilterOperator.EQUALS, "record_state", List.of("DRAFT"), List.of()));
  }

  private static void authorize(final boolean administrator) {
    var authorization = new AuthorizationContext();
    authorization.setUserId("publisher");
    authorization.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    authorization.setIsAdministrator(administrator);
    authorization.setDataScopeType("SELF");
    authorization.setResources(Set.of("app.forms.orders"));
    authorization.setResourceFieldPermissions(
        Map.of("app.forms.orders", Map.of("dna.runtime.record.model-1#caption", "HIDDEN")));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
    ResourcePolicyContext.set("app.forms.orders");
  }

  private static DriverManagerDataSource source(final String prefix) {
    return new DriverManagerDataSource(
        System.getenv(prefix + "_URL"),
        System.getenv(prefix + "_USER"),
        System.getenv(prefix + "_PASSWORD"));
  }

  private static PhysicalSchemaSnapshotCodec codec() {
    return new PhysicalSchemaSnapshotCodec(new ObjectMapper().findAndRegisterModules());
  }
}
