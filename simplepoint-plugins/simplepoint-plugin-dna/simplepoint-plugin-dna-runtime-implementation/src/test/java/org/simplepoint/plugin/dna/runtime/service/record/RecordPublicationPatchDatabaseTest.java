package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchCompiler.Direction.APPLY;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchCompiler.Direction.RESTORE;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.data.calcite.core.query.CalciteSchemaConfigurer;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;
import org.simplepoint.plugin.dna.runtime.service.schema.JdbcPhysicalSchemaBackend;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.MysqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;
import org.simplepoint.plugin.dna.runtime.service.schema.PostgresqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordPublicationSchema;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordPublicationStageSchema;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceProvisioner;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Actual parent/child SQL and durable stage/checkpoint commit together; no production worker is
 * implied.
 */
class RecordPublicationPatchDatabaseTest {
  private static final int TIMEOUT = 30_000;
  private static final RecordPublicationStore JOURNAL = new RecordPublicationStore();
  private static final RecordPublicationStages STAGES = new RecordPublicationStages();
  private static final DefaultCalciteQueryEngine ENGINE = new DefaultCalciteQueryEngine();

  @Test
  void postgresqlAppliesAndRestoresExactBusinessAggregates() throws Exception {
    verify("POSTGRES", true, RecordPublicationPatchDatabaseTest::run);
  }

  @Test
  void mysqlAppliesAndRestoresExactBusinessAggregates() throws Exception {
    verify("MYSQL", false, RecordPublicationPatchDatabaseTest::run);
  }

  static void verify(String database, boolean postgres, Scenario scenario) throws Exception {
    verify(database, postgres, false, scenario);
  }

  static void verify(String database, boolean postgres, boolean owned, Scenario scenario)
      throws Exception {
    String prefix = "DNA_RUNTIME_TEST_" + database;
    String url = System.getenv(prefix + "_URL");
    assumeTrue(url != null && !url.isBlank(), "Real target database is required");
    var source =
        new DriverManagerDataSource(
            url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
    String namespace = "patch_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + namespace);
    }
    try {
      scenario.run(fixture(source, namespace, postgres, owned));
    } finally {
      try (var connection = source.getConnection();
          var statement = connection.createStatement()) {
        statement.execute(
            "DROP "
                + (postgres ? "SCHEMA " : "DATABASE ")
                + namespace
                + (postgres ? " CASCADE" : ""));
      }
    }
  }

  private static Database fixture(
      DataSource source, String namespace, boolean postgres, boolean owned) throws Exception {
    var codec = new PhysicalSchemaSnapshotCodec(new ObjectMapper().findAndRegisterModules());
    JdbcDatabaseDialect core =
        postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
    final RuntimeSchemaDialect dialect =
        postgres ? new PostgresqlRuntimeSchemaDialect(codec) : new MysqlRuntimeSchemaDialect(codec);
    JdbcDatabaseDialect.SupportContext context;
    DatabaseIdentity identity;
    String catalog;
    try (var connection = source.getConnection()) {
      var metadata = connection.getMetaData();
      catalog = postgres ? connection.getCatalog() : namespace;
      context =
          new JdbcDatabaseDialect.SupportContext(
              core.code(),
              metadata.getDriverName(),
              metadata.getDatabaseProductName(),
              metadata.getDatabaseProductVersion(),
              catalog,
              postgres ? namespace : null,
              metadata.getIdentifierQuoteString(),
              Map.of());
      identity =
          new DatabaseIdentity(
              metadata.getDatabaseProductName(),
              metadata.getDatabaseProductVersion(),
              metadata.getDatabaseMajorVersion(),
              metadata.getDatabaseMinorVersion());
    }
    var fixture = new RecordPublicationPatchFixture(catalog, postgres ? namespace : null);
    String namespaceId = null;
    if (owned) {
      namespaceId = UUID.randomUUID().toString();
      try (var connection = source.getConnection()) {
        org.simplepoint.plugin.dna.runtime.service.schema.RuntimeOwnershipTestFixture.installFresh(
            connection, namespace, UUID.fromString(namespaceId));
        try (var session =
            org.simplepoint.data.ownership.PhysicalOwnershipSession.open(connection, namespace)) {
          for (var model : List.of(fixture.parent, fixture.child)) {
            session.claimNew(
                model.target().metadataTarget().tableName(),
                true,
                new org.simplepoint.data.ownership.PhysicalObjectOwner(
                    "dna-runtime", fixture.intent.key().tenantId(), model.physicalModelId()));
          }
        }
      }
    }
    final var infrastructureTarget =
        org.simplepoint.plugin.dna.runtime.service.schema.RuntimeOwnershipTestFixture
            .reserveNewModel(source, fixture.parent.target(), fixture.intent.key().tenantId());
    org.simplepoint.plugin.dna.runtime.service.schema.RuntimeOwnershipTestFixture.reserveNewModel(
        source, fixture.child.target(), fixture.intent.key().tenantId());
    var provider = mock(ManagedRuntimeDataSourceProvider.class);
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
                postgres ? namespace : null));
    var ddl = new JdbcPhysicalSchemaBackend(provider, codec);
    for (var model : List.of(fixture.parent, fixture.child)) {
      var plan =
          ddl.planInitial(
              model.target(), "Publication patch business fixture", model.activeSchema());
      for (var step : plan.steps()) {
        ddl.execute(model.target(), plan.dialectId(), step);
      }
    }
    new RecordWriteFenceProvisioner(ddl).prepare(infrastructureTarget);
    new org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner(ddl)
        .prepare(infrastructureTarget);
    return new Database(
        source,
        fixture,
        dialect,
        new RecordWriteFence(dialect.recordWriteFenceSql(core, context, fixture.parent.target())),
        catalog,
        postgres ? namespace : null,
        core,
        context,
        new RuntimeCalciteRecordBackend(provider, ENGINE),
        namespaceId);
  }

  private static void run(Database db) throws Exception {
    var fixture = db.fixture();
    fixture.verifyCalculatedCandidates();
    var intent = fixture.intent;
    var aggregate = fixture.aggregate;
    var prepared = STAGES.prepare(intent, aggregate);
    var initial = new RecordPublicationStore.Entry(intent, RecordPublicationCheckpoint.start(1));
    var staged =
        new RecordPublicationStore.Entry(
            intent, initial.checkpoint().batch(1, aggregate.parentRecordId()));
    var applying =
        new RecordPublicationStore.Entry(
            intent, staged.checkpoint().transition(RecordPublicationCheckpoint.Phase.APPLYING));
    try (var connection = transaction(db)) {
      for (var plan : fixture.seed()) {
        assertThat(update(db, connection, plan)).isEqualTo(1);
      }
      db.fence().initialize(connection, intent.key(), intent.actorId(), TIMEOUT);
      assertThat(update(db, connection, JOURNAL.insert(initial))).isEqualTo(1);
      for (var plan : STAGES.inserts(prepared)) {
        assertThat(update(db, connection, plan)).isEqualTo(1);
      }
      assertThat(
              update(db, connection, JOURNAL.advance(initial, staged.checkpoint(), intent.clock())))
          .isEqualTo(1);
      assertThat(
              update(
                  db, connection, JOURNAL.advance(staged, applying.checkpoint(), intent.clock())))
          .isEqualTo(1);
      connection.commit();
    }
    Map<String, List<Map<String, String>>> original;
    try (var connection = locked(db)) {
      original = snapshot(db, connection);
      var reloaded =
          STAGES.payload(intent, header(db, connection), plan -> query(db, connection, plan));
      assertThat(reloaded).isEqualTo(aggregate);
      assertThat(original.get("parent").getFirst().get("bytes_value")).isEqualTo("fbff");
      var foreign =
          new RecordPublicationIntent(
              1,
              new RecordWriteFenceKey("tenant-b", intent.key().modelId()),
              intent.command(),
              intent.fingerprint(),
              intent.previousFingerprint(),
              intent.actorId(),
              intent.evaluatedAt(),
              intent.bindings(),
              null);
      for (var plan : fixture.compiler.compile(foreign, reloaded, fixture.models, APPLY)) {
        assertThat(update(db, connection, plan)).isZero();
      }
      connection.rollback();
    }
    var applyPlans = fixture.compiler.compile(intent, aggregate, fixture.models, APPLY);
    // Failure after the parent statement must not leave a partial aggregate or advance its
    // manifest.
    try (var connection = locked(db)) {
      assertThat(update(db, connection, applyPlans.getFirst())).isEqualTo(1);
      connection.rollback();
    }
    try (var connection = locked(db)) {
      assertThat(snapshot(db, connection)).isEqualTo(original);
      assertThat(header(db, connection).phase()).isEqualTo(RecordPublicationStages.Phase.STAGED);
      assertThat(journal(db, connection)).isEqualTo(applying);
      // Wrong optimistic version and wrong previous audit actor/time affect zero rows.
      var row = aggregate.rows().getFirst();
      for (var stale :
          List.of(
              new RecordPublicationAggregate.Row(
                  row.modelId(),
                  row.recordId(),
                  null,
                  1,
                  row.previousUpdatedBy(),
                  row.previousUpdatedAt(),
                  row.changes()),
              new RecordPublicationAggregate.Row(
                  row.modelId(),
                  row.recordId(),
                  null,
                  0,
                  "foreign-updater",
                  row.previousUpdatedAt(),
                  row.changes()),
              new RecordPublicationAggregate.Row(
                  row.modelId(),
                  row.recordId(),
                  null,
                  0,
                  row.previousUpdatedBy(),
                  intent.evaluatedAt(),
                  row.changes()))) {
        var staleAggregate = new RecordPublicationAggregate(1, row.recordId(), List.of(stale));
        assertThat(
                update(
                    db,
                    connection,
                    fixture
                        .compiler
                        .compile(intent, staleAggregate, fixture.models, APPLY)
                        .getFirst()))
            .isZero();
      }
      String unrelatedParent = "00000000-0000-4000-8000-000000000099";
      var foreignAggregate =
          new RecordPublicationAggregate(
              1,
              unrelatedParent,
              List.of(
                  fixture.row(
                      fixture.parent.physicalModelId(),
                      unrelatedParent,
                      null,
                      aggregate.rows().getFirst().changes()),
                  fixture.row(
                      fixture.child.physicalModelId(),
                      RecordPublicationPatchFixture.CHILD,
                      unrelatedParent,
                      aggregate.rows().getLast().changes())));
      assertThat(
              update(
                  db,
                  connection,
                  fixture
                      .compiler
                      .compile(intent, foreignAggregate, fixture.models, APPLY)
                      .getLast()))
          .isZero();
      assertThat(snapshot(db, connection)).isEqualTo(original);
      connection.rollback();
    }
    var applied =
        new RecordPublicationStore.Entry(
            intent, applying.checkpoint().batch(1, aggregate.parentRecordId()));
    for (boolean commit : new boolean[] {false, true}) {
      try (var connection = locked(db)) {
        for (var plan : applyPlans) {
          assertThat(update(db, connection, plan)).isEqualTo(1);
        }
        assertThat(
                update(
                    db,
                    connection,
                    STAGES.advance(
                        intent,
                        prepared.header(),
                        RecordPublicationStages.Phase.APPLIED,
                        intent.clock())))
            .isEqualTo(1);
        assertThat(
                update(
                    db,
                    connection,
                    JOURNAL.advance(applying, applied.checkpoint(), intent.clock())))
            .isEqualTo(1);
        verifyCandidate(snapshot(db, connection), original);
        if (commit) {
          connection.commit();
        } else {
          connection.rollback();
        }
      }
    }
    Map<String, List<Map<String, String>>> candidate;
    var restoring =
        new RecordPublicationStore.Entry(
            intent, applied.checkpoint().transition(RecordPublicationCheckpoint.Phase.RESTORING));
    try (var connection = locked(db)) {
      candidate = snapshot(db, connection);
      verifyCandidate(candidate, original);
      for (var plan : applyPlans) {
        assertThat(update(db, connection, plan)).isZero();
      }
      assertThat(
              update(
                  db, connection, JOURNAL.advance(applied, restoring.checkpoint(), intent.clock())))
          .isEqualTo(1);
      connection.commit();
    }
    var restored =
        new RecordPublicationStore.Entry(
            intent, restoring.checkpoint().batch(1, aggregate.parentRecordId()));
    for (boolean commit : new boolean[] {false, true}) {
      // New physical connection and payload decoding emulate a restarted recovery attempt.
      try (var connection = locked(db)) {
        assertThat(snapshot(db, connection)).isEqualTo(candidate);
        var header = header(db, connection);
        assertThat(header.phase()).isEqualTo(RecordPublicationStages.Phase.APPLIED);
        var recovered = STAGES.payload(intent, header, plan -> query(db, connection, plan));
        for (var plan : fixture.compiler.compile(intent, recovered, fixture.models, RESTORE)) {
          assertThat(update(db, connection, plan)).isEqualTo(1);
        }
        assertThat(
                update(
                    db,
                    connection,
                    STAGES.advance(
                        intent, header, RecordPublicationStages.Phase.RESTORED, intent.clock())))
            .isEqualTo(1);
        assertThat(
                update(
                    db,
                    connection,
                    JOURNAL.advance(restoring, restored.checkpoint(), intent.clock())))
            .isEqualTo(1);
        assertThat(snapshot(db, connection)).isEqualTo(original);
        if (commit) {
          connection.commit();
        } else {
          connection.rollback();
        }
      }
    }
    try (var connection = locked(db)) {
      assertThat(snapshot(db, connection)).isEqualTo(original);
      assertThat(journal(db, connection)).isEqualTo(restored);
      assertThat(header(db, connection).phase()).isEqualTo(RecordPublicationStages.Phase.RESTORED);
      for (var plan : fixture.compiler.compile(intent, aggregate, fixture.models, RESTORE)) {
        assertThat(update(db, connection, plan)).isZero();
      }
      assertThat(db.fence().admitRead(connection, intent.key(), TIMEOUT).revision()).isZero();
      connection.rollback();
    }
  }

  static void verifyCandidate(
      Map<String, List<Map<String, String>>> actual,
      Map<String, List<Map<String, String>>> original) {
    var parent = actual.get("parent").getFirst();
    var child = actual.get("child").getFirst();
    assertThat(parent)
        .containsEntry("total_amount", "9999999999999999999999999999999999.9999")
        .containsEntry("units", "-9223372036854775808")
        .containsEntry("summary", "候选值・日本語😀")
        .containsEntry("record_version", "1")
        .containsEntry("updated_by", "publisher")
        .containsEntry("business_date", "2026-10-18")
        .containsEntry("start_time", "00:00:00.123456")
        .containsEntry("bytes_value", "ff")
        .containsEntry("reference_id", RecordPublicationPatchFixture.CHILD);
    assertThat(parent.get("calculated_at")).contains("2026-10-18 02:03:04.123456");
    assertThat(parent.get("updated_at")).contains("2026-10-18 02:03:04.123456");
    assertThat(LogicalJsonValues.canonicalizeStored(parent.get("payload")))
        .isEqualTo("{\"a\":1.25,\"文\":\"日\"}");
    assertThat(child)
        .containsEntry("line_amount", "20.02")
        .containsEntry("record_version", "1")
        .containsEntry("updated_by", "publisher");
    for (String key : List.of("parent", "child")) {
      for (String field :
          List.of("created_at", "created_by", "created_org_dept_id", "tenant_id", "deleted_at")) {
        assertThat(actual.get(key).getFirst().get(field))
            .isEqualTo(original.get(key).getFirst().get(field));
      }
    }
    assertThat(parent.get("source_value"))
        .isEqualTo(original.get("parent").getFirst().get("source_value"));
    for (String field : List.of("owner_record_id", "row_order", "memo")) {
      assertThat(child.get(field)).isEqualTo(original.get("child").getFirst().get(field));
    }
  }

  static Map<String, List<Map<String, String>>> snapshot(Database db, Connection connection)
      throws SQLException {
    var result = new LinkedHashMap<String, List<Map<String, String>>>();
    for (var entry :
        Map.of("parent", db.fixture().parent, "child", db.fixture().child).entrySet()) {
      var model = entry.getValue();
      String table =
          db.core()
              .qualifyName(
                  db.schema() == null ? db.catalog() : null,
                  db.schema(),
                  model.target().metadataTarget().tableName(),
                  db.context());
      var rows = new ArrayList<Map<String, String>>();
      try (var statement = connection.createStatement();
          var values = statement.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
        while (values.next()) {
          var row = new LinkedHashMap<String, String>();
          for (int column = 1; column <= values.getMetaData().getColumnCount(); column++) {
            String name = values.getMetaData().getColumnName(column);
            row.put(
                name,
                name.equals("bytes_value")
                    ? HexFormat.of().formatHex(values.getBytes(column))
                    : values.getString(column));
          }
          rows.add(Collections.unmodifiableMap(row));
        }
      }
      result.put(entry.getKey(), List.copyOf(rows));
    }
    return Map.copyOf(result);
  }

  static long update(Database db, Connection connection, RecordSqlStatement plan) {
    return ENGINE
        .executeUpdate(
            new CalciteUpdateRequest(
                plan.sql(),
                null,
                TIMEOUT,
                RecordSqlParameters.bind(plan.parameters(), db.dialect())),
            catalog(db, connection))
        .affectedRows();
  }

  static CalciteQueryResult query(Database db, Connection connection, RecordSelectPlan plan) {
    return ENGINE.execute(
        new CalciteQueryRequest(
            plan.sql(),
            null,
            plan.maxRows(),
            TIMEOUT,
            RecordSqlParameters.bind(plan.parameters(), db.dialect())),
        catalog(db, connection));
  }

  private static RecordPublicationStages.Header header(Database db, Connection connection) {
    return STAGES.read(
        db.fixture().intent,
        RecordPublicationPatchFixture.PARENT,
        query(
            db,
            connection,
            STAGES.lookup(db.fixture().intent, RecordPublicationPatchFixture.PARENT)));
  }

  private static RecordPublicationStore.Entry journal(Database db, Connection connection) {
    return JOURNAL.read(
        db.fixture().intent.key(),
        1,
        query(db, connection, JOURNAL.lookup(db.fixture().intent.key(), 1)));
  }

  private static CalciteSchemaConfigurer catalog(Database db, Connection connection) {
    return new RuntimeRecordCatalog(
        new TransactionConnectionDataSource(connection),
        db.dialect(),
        db.catalog(),
        db.schema(),
        Set.of(
            "patch_parents",
            "patch_children",
            RecordPublicationSchema.TABLE,
            RecordPublicationStageSchema.TABLE,
            RecordPublicationStageSchema.CHUNKS));
  }

  static Connection transaction(Database db) throws SQLException {
    var connection = db.source().getConnection();
    connection.setAutoCommit(false);
    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    db.dialect().prepareRecordTransaction(connection);
    return connection;
  }

  static Connection locked(Database db) throws SQLException {
    var connection = transaction(db);
    try {
      db.fence()
          .lockPublication(
              connection, db.fixture().intent.key(), 0, new RecordExecutionDeadline(TIMEOUT));
      return connection;
    } catch (SQLException | RuntimeException failure) {
      connection.close();
      throw failure;
    }
  }

  @FunctionalInterface
  interface Scenario {
    void run(Database database) throws Exception;
  }

  record Database(
      DataSource source,
      RecordPublicationPatchFixture fixture,
      RuntimeSchemaDialect dialect,
      RecordWriteFence fence,
      String catalog,
      String schema,
      JdbcDatabaseDialect core,
      JdbcDatabaseDialect.SupportContext context,
      RuntimeCalciteRecordBackend backend,
      String namespaceId) {}
}
