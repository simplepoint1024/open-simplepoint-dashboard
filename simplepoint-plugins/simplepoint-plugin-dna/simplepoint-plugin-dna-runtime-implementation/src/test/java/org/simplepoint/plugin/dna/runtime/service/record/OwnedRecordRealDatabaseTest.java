package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.service.schema.JdbcPhysicalSchemaBackend;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.MysqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.PostgresqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceProvisioner;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceSql;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import org.simplepoint.plugin.dna.runtime.api.record.*;
import org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalIndexDefinition;
class OwnedRecordRealDatabaseTest {
  private static final RuntimeActor ACTOR = new RuntimeActor("tenant-a", "user-a");
  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));
  @Test void postgresAggregate() throws Exception {verify("POSTGRES", true);}
  @Test void mysqlAggregate() throws Exception {verify("MYSQL", false);}

  private void verify(String database, boolean postgres) throws Exception {
    String prefix = "DNA_RUNTIME_TEST_" + database, url = System.getenv(prefix + "_URL");
    assumeTrue(url != null && !url.isBlank(), "Real target database is required");
    var source = new DriverManagerDataSource(url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
    String namespace = "owned_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + namespace);
    }
    try {run(source, namespace, postgres);}
    finally {
      try (var connection = source.getConnection(); var statement = connection.createStatement()) {
        statement.execute("DROP " + (postgres ? "SCHEMA " : "DATABASE ") + namespace + (postgres ? " CASCADE" : ""));
      }
    }
  }

  private void run(DataSource source, String namespace, boolean postgres) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var codec = new PhysicalSchemaSnapshotCodec(mapper);
    JdbcDatabaseDialect core = postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
    RuntimeSchemaDialect dialect = postgres ? new PostgresqlRuntimeSchemaDialect(codec) : new MysqlRuntimeSchemaDialect(codec);
    JdbcDatabaseDialect.SupportContext context;
    DatabaseIdentity database;
    String catalog;
    try (Connection connection = source.getConnection()) {
      var metadata = connection.getMetaData();
      catalog = postgres ? connection.getCatalog() : namespace;
      context = new JdbcDatabaseDialect.SupportContext(core.code(), metadata.getDriverName(), metadata.getDatabaseProductName(),
          metadata.getDatabaseProductVersion(), catalog, postgres ? namespace : null, metadata.getIdentifierQuoteString(), Map.of());
      database = new DatabaseIdentity(metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion(),
          metadata.getDatabaseMajorVersion(), metadata.getDatabaseMinorVersion());
    }

    var trackWrites = new AtomicBoolean();
    Set<Connection> businessConnections = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    var transactionalReads = new AtomicInteger();
    var trackedSource = tracked(source, trackWrites, businessConnections, transactionalReads);
    var provider = mock(ManagedRuntimeDataSourceProvider.class);
    when(provider.open(any())).thenReturn(new ManagedRuntimeDataSource(new SimpleDataSource(trackedSource), false, context, core, dialect,
        database, catalog, postgres ? namespace : null));
    var title = new LogicalFieldDefinition("title", "title", "Title", LogicalFieldType.STRING, false, 128, null, null, null);
    var rate = new LogicalFieldDefinition("rate", "exchange_rate", "Exchange rate", LogicalFieldType.DECIMAL, false, null, 12, 4,
        new org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue(org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind.DECIMAL, "1.0000"));
    var price = new LogicalFieldDefinition("price", "unit_price", "Unit price", LogicalFieldType.DECIMAL, false, null, 24, 2,
        new org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue(org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind.DECIMAL, "10.01"));
    var quantity = new LogicalFieldDefinition("quantity", "quantity", "Quantity", LogicalFieldType.INT32, false, null, null, null,
        new org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue(org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind.INTEGER, "1"));
    var computedAt = new LogicalFieldDefinition("computed_at", "computed_at", "Computed at", LogicalFieldType.DATETIME, true, null, null, null, null);
    var schema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(title, rate, computedAt,
        new LogicalFieldDefinition("state", "lifecycle_state", "State", LogicalFieldType.STRING, false, 16, null, null,
            new org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue(org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind.STRING, "DRAFT")),
        new LogicalFieldDefinition("selection", "selection", "Selection", LogicalFieldType.JSON, true, null, null, null, null),
        new LogicalFieldDefinition("rating", "rating", "Rating", LogicalFieldType.INT32, true, null, null, null, null),
        new LogicalFieldDefinition("display_title", "display_title", "Display title", LogicalFieldType.STRING, true, 128, null, null, null),
        new LogicalFieldDefinition("amount", "amount", "Amount", LogicalFieldType.DECIMAL, true, null, 24, 2, null)), List.of());
    var childSchema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(title, price, quantity, new LogicalFieldDefinition("amount", "amount", "Amount", LogicalFieldType.DECIMAL, true, null, 24, 2, null),
        new LogicalFieldDefinition("parent", "parent_record_id", "Parent", LogicalFieldType.UUID, false, null, null, null, null),
        new LogicalFieldDefinition("order", "row_order", "Order", LogicalFieldType.INT32, false, null, null, null, null)),
        List.of(new LogicalIndexDefinition("parent_title", List.of("parent_record_id", "title"), true)), new OwnedModelRelation("model-1", "parent"));
    var target = new PhysicalSchemaTarget("model-1", new RuntimeMetadataTarget("source", catalog, postgres ? namespace : null, "parents"));
    var childTarget = new PhysicalSchemaTarget("child-1", new RuntimeMetadataTarget("source", catalog, postgres ? namespace : null, "details"));
    var ddl = new JdbcPhysicalSchemaBackend(provider, codec);
    new RecordJournalProvisioner(ddl).prepare(target);
    for (var entry : Map.of(target, schema, childTarget, childSchema).entrySet()) {
      var plan = ddl.planInitial(entry.getKey(), "Aggregate", entry.getValue());
      for (var step : plan.steps()) ddl.execute(entry.getKey(), plan.dialectId(), step);
    }
    var parent = new RuntimeRecordModel("model-1", "app", "form", 1, schema, target);
    var child = new RuntimeRecordModel("child-1", "app", "detail", 1, childSchema, childTarget);
    var backend = new RuntimeCalciteRecordBackend(provider, new DefaultCalciteQueryEngine());
    new RecordWriteFencePreparation(new RecordWriteFenceProvisioner(ddl), backend).prepare(ACTOR, target);
    var requestContext = mock(RuntimeRequestContext.class);
    var models = mock(RuntimeRecordModelResolver.class);
    var policies = mock(RuntimeRecordAccessPolicy.class);
    var access = new RecordAccessContext(ACTOR, "app.form", Set.of("id", "record_version", "title", "amount", "exchange_rate", "unit_price", "quantity", "computed_at", "display_title", "parent_record_id", "row_order", "selection", "rating", "lifecycle_state"),
        Set.of("title", "amount", "exchange_rate", "unit_price", "quantity", "lifecycle_state"), Set.of(), new DataScopeCondition("ALL", "created_org_dept_id", "created_by", ACTOR.actorId(), Set.of()), null);
    when(requestContext.requireActor()).thenReturn(ACTOR);
    when(models.resolve(ACTOR, "model-1", SCHEMA)).thenReturn(parent);
    when(models.resolve(ACTOR, "child-1", SCHEMA)).thenReturn(child);
    when(policies.authorize(ACTOR, parent)).thenReturn(access);
    when(policies.owned(ACTOR, child, access)).thenReturn(access);
    var compiler = new RecordSqlCompiler(new StandardLogicalValueConverter());
    var audit = mock(RecordAuditSupport.class);
    var admission = new RecordAdmissionController(new DnaRuntimeExecutionProperties());
    var observer = mock(RecordExecutionObserver.class);
    var queries = new RecordQueryServiceImpl(requestContext, models, policies, compiler, backend, audit, admission, observer, RecordCalculationTestSupport.access(models, policies, compiler));
    var service = new OwnedRecordService(requestContext, models, policies, compiler, backend,
        new RecordRequestFingerprint(mapper), admission, audit, observer, new RecordCalculationWriteSupport(models,
        new org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec(new ObjectMapper().findAndRegisterModules()), compiler), RecordCalculationTestSupport.access(models, policies, compiler));
    var bad = command("rollback", new RecordCreateMutation("parent", Map.of("title", "Rollback")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "Duplicate")), new OwnedRecordRow(null, null, Map.of("title", "Duplicate"))));
    assertThatThrownBy(() -> service.execute(bad)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED));
    assertThatThrownBy(() -> service.findResult(bad)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED));
    assertThatThrownBy(() -> service.execute(bad)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED));
    assertThat(queries.count(new RecordCountQuery("model-1", SCHEMA, null))).isZero();
    var create = command("create", new RecordCreateMutation("parent", Map.of("title", "中文・日本語", "amount", "9007199254740993.01")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "明细・明細", "amount", "9007199254740993.02"))));
    var ordinaryPreview = service.preview(create);
    assertThat(ordinaryPreview.values()).containsEntry("amount", "9007199254740993.01");
    assertThat(ordinaryPreview.details().get("child-1").getFirst()).containsEntry("amount", "9007199254740993.02")
        .doesNotContainKeys("parent_record_id", "row_order", "id", "record_version");
    assertThat(service.findResult(create)).isNull();
    assertThat(queries.count(new RecordCountQuery("model-1", SCHEMA, null))).isZero();
    create = new OwnedRecordCommand(create.parent(), create.details(),
        new OwnedRecordSnapshotRequest(List.of(new OwnedRecordModel("child-1", SCHEMA))));
    var created = service.execute(create);
    var originalSnapshot = service.snapshot(create);
    assertThat(originalSnapshot.parent().values()).containsEntry("title", "中文・日本語").containsEntry("amount", "9007199254740993.01");
    assertThat(originalSnapshot.details().get("child-1").getFirst().values()).containsEntry("amount", "9007199254740993.02");
    assertThat(mapper.readValue(mapper.writeValueAsBytes(originalSnapshot), OwnedRecordView.class)).isEqualTo(originalSnapshot);
    assertThat(originalSnapshot.details().get("child-1").getFirst().values()).containsEntry("title", "明细・明細");
    assertThat(service.execute(create)).isEqualTo(created);
    var current = service.get(query(created.recordId()));
    var row = current.details().get("child-1").getFirst();
    assertThat(row.values()).containsEntry("title", "明细・明細");
    var update = command("update", new RecordUpdateMutation("parent", created.recordId(), 0, Map.of("title", "Updated")),
        List.of(new OwnedRecordRow(row.id(), row.version(), Map.of("title", "Edited")), new OwnedRecordRow(null, null, Map.of("title", "Added"))));
    trackWrites.set(true);
    try {
      assertThat(service.execute(update).version()).isEqualTo(1);
    } finally {
      trackWrites.set(false);
    }
    assertThat(businessConnections).hasSize(1);
    assertThat(transactionalReads.get()).isGreaterThanOrEqualTo(2);
    assertThat(service.get(query(created.recordId())).details().get("child-1")).hasSize(2);
    var remove = command("remove", new RecordUpdateMutation("parent", created.recordId(), 1, Map.of("title", "No details")), List.of());
    service.execute(remove);
    assertThat(service.get(query(created.recordId())).details().get("child-1")).isEmpty();
    service.execute(command("delete", new RecordDeleteMutation("parent", created.recordId(), 2), List.of()));
    assertThat(service.findResult(update).version()).isEqualTo(1);
    assertThat(service.snapshot(create)).isEqualTo(originalSnapshot);
    assertThat(service.execute(create)).isEqualTo(created);
    assertThat(queries.count(new RecordCountQuery("model-1", SCHEMA, null))).isZero();
    verifyCalculations(source, core, dialect, context, target, parent, child, models, policies, ddl, service, backend, compiler, access,
        catalog, postgres ? namespace : null, trackWrites, businessConnections, transactionalReads);
  }

  private static void verifyCalculations(DataSource source, JdbcDatabaseDialect core, RuntimeSchemaDialect dialect,
      JdbcDatabaseDialect.SupportContext context, PhysicalSchemaTarget target, RuntimeRecordModel parent,
      RuntimeRecordModel child, RuntimeRecordModelResolver models, RuntimeRecordAccessPolicy policies, JdbcPhysicalSchemaBackend ddl,
      OwnedRecordService service, RuntimeCalciteRecordBackend backend,
      RecordSqlCompiler compiler, RecordAccessContext access, String catalog, String namespace, AtomicBoolean tracked,
      Set<Connection> connections, AtomicInteger reads) throws Exception {
    var schemas = new org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec(new ObjectMapper().findAndRegisterModules());
    var rootReference = new RecordSchemaReference(1, schemas.encode(parent.requestedSchema()).checksum());
    var childReference = new RecordSchemaReference(1, schemas.encode(child.requestedSchema()).checksum());
    when(models.resolve(ACTOR, parent.physicalModelId(), rootReference)).thenReturn(parent);
    when(models.resolve(ACTOR, child.physicalModelId(), childReference)).thenReturn(child);
    var definition = new RecordCalculationDefinition("1", Map.of("amount", "SUM([lines.amount])", "computed_at", "IF([title]=\"缺失流程\",NULL,NOW())", "display_title", "UPPER([title])",
        "selection", "IF([title]=\"无效级联\",\"[\"\"中国\"\"]\",\"[\"\"中国\"\",\"\"上海\"\"]\")",
        "rating", "IF([title]=\"无效评分\",6,5)"), Map.of("lines",
        new RecordCalculationScope(child.physicalModelId(), childReference,
            Map.of("amount", "ROUND([price]*[quantity]/[parent.rate],2)"), Map.of("title", new RecordFieldConstraints(2, 128, null, List.of())))),
        Map.of("title", new RecordFieldConstraints(2, 128, null, List.of()),
            "display_title", new RecordFieldConstraints(2, 20, "^[\\p{L}・]+$", List.of())), List.of(
                new RecordValidationRule("selection-domain", RecordValidationRule.Kind.FIELD,
                    new RecordValidationField("parent", "selection"), null, null, null, null,
                    new RecordValidationCondition(RecordValueConditionOperator.TRUE, null, List.of(), List.of()),
                    new RecordFieldConstraints(null, null, null, List.of(), new RecordJsonSelection(RecordJsonSelection.Mode.PATH,
                        List.of(List.of("中国", "上海"))))),
                new RecordValidationRule("rating-domain", RecordValidationRule.Kind.RANGE,
                    new RecordValidationField("parent", "rating"), null, null, "0", "5",
                    new RecordValidationCondition(RecordValueConditionOperator.IS_NOT_EMPTY,
                        new RecordValidationField("parent", "rating"), List.of(), List.of())),
                new RecordValidationRule("historical-output", RecordValidationRule.Kind.FIELD,
                    new RecordValidationField("parent", "display_title"), null, null, null, null,
                    new RecordValidationCondition(RecordValueConditionOperator.TRUE, null, List.of(), List.of()),
                    new RecordFieldConstraints(null, null, "^[^禁]*$", List.of())),
                new RecordValidationRule("bounded-total", RecordValidationRule.Kind.RANGE,
                    new RecordValidationField("parent", "amount"), null, null, "0", "1000",
                    new RecordValidationCondition(RecordValueConditionOperator.TRUE, null, List.of(), List.of())),
                new RecordValidationRule("exact-sum", RecordValidationRule.Kind.DETAIL_SUM,
                    new RecordValidationField("parent", "amount"), new RecordValidationField("lines", "amount"), null, null, null,
                    new RecordValidationCondition(RecordValueConditionOperator.TRUE, null, List.of(), List.of()))),
        new RecordStateTransitions("state", List.of("DRAFT", "SUBMITTED"),
            Map.of("DRAFT", List.of("DRAFT", "SUBMITTED"), "SUBMITTED", List.of("SUBMITTED"))),
        List.of(new org.simplepoint.plugin.dna.runtime.api.record.RecordFieldWriteRule(new RecordValidationField("parent", "title"), true, List.of("DRAFT")),
            new org.simplepoint.plugin.dna.runtime.api.record.RecordFieldWriteRule(new RecordValidationField("lines", "title"), true, List.of("DRAFT"))));
    // Fixture-only activation: public publication remains guarded until backfill/read-policy integration is complete.
    try (var connection = source.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      var key = new RecordWriteFenceKey(ACTOR.tenantId(), parent.physicalModelId());
      var fence = new RecordWriteFence(dialect.recordWriteFenceSql(core, context, target));
      fence.publish(connection, key, 0, 1, "1".repeat(64), ACTOR.actorId(), 30_000, borrowed -> {
        var insert = new RecordCalculationStore().insert(key, 1, ACTOR.actorId(), Instant.now(),
            new RecordCalculationPolicy(1, "app", "form", rootReference, definition));
        var engine = new DefaultCalciteQueryEngine();
        var result = engine.executeUpdate(new org.simplepoint.data.calcite.core.query.CalciteUpdateRequest(
            insert.sql(), null, 30_000, RecordSqlParameters.bind(insert.parameters(), dialect)),
            root -> root.add("runtime", org.apache.calcite.adapter.jdbc.JdbcSchema.create(root, "runtime",
                new TransactionConnectionDataSource(borrowed), catalog, namespace)));
        assertThat(result.affectedRows()).isEqualTo(1);
      });
      connection.commit();
    }
    // A consumer requirement is checked on the final captured projection inside the write transaction.
    for (var requirement : List.of(
        new org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement("computed_at", true, null, null, false, false, false),
        new org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement("display_title", true, 1, null, false, false, false),
        new org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement("display_title", true, 100, "[A-Za-z]+", false, false, false))) {
      var candidate = calculated("capture-invalid-" + requirement.fieldId() + requirement.maxTextUnits(),
          new RecordCreateMutation("parent", Map.of("title", "缺失流程")), List.of(), true);
      var rejected = new OwnedRecordCommand(candidate.parent(), candidate.details(),
          new OwnedRecordSnapshotRequest(candidate.snapshot().details(), List.of(requirement)));
      assertThatThrownBy(() -> service.execute(rejected)).isInstanceOf(DnaRuntimeException.class);
      assertThat(service.findResult(rejected)).isNull();
      assertThat(service.snapshot(rejected)).isNull();
      // The same key without consumer requirements can commit only if the previous physical write rolled back.
      var successful = service.execute(candidate);
      service.execute(calculated("cleanup-" + candidate.parent().idempotencyKey(),
          new RecordDeleteMutation("parent", successful.recordId(), successful.version()), null, false));
    }
    var create = calculated("calc-create", new RecordCreateMutation("parent", Map.of("title", "出差报销")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "交通", "unit_price", "10.01", "quantity", 3)),
            new OwnedRecordRow(null, null, Map.of("title", "住宿费", "unit_price", "8.25", "quantity", 2))), true);
    var createPreview = service.preview(new OwnedRecordCommand(create.parent(), create.details()));
    assertThat(createPreview.values()).containsEntry("amount", "46.53");
    assertThat(createPreview.writePolicyVersion()).isEqualTo(1);
    assertThat(createPreview.details().get("child-1")).extracting(row -> row.get("amount")).containsExactly("30.03", "16.50");
    assertThat(service.findResult(create)).isNull();
    assertThat(service.snapshot(create)).isNull();
    assertThatThrownBy(() -> service.preview(create)).isInstanceOf(DnaRuntimeException.class);
    connections.clear();
    reads.set(0);
    tracked.set(true);
    RecordCommandResult created;
    try {
      created = service.execute(create);
    } finally {
      tracked.set(false);
    }
    assertThat(connections).hasSize(1);
    assertThat(service.execute(create)).isEqualTo(created);
    var snapshot = service.snapshot(create);
    assertThat(snapshot.parent().values()).containsEntry("amount", "46.53")
        .containsEntry("exchange_rate", "1.0000").containsEntry("computed_at", created.updatedAt().toString());
    assertThat(snapshot.parent().values()).containsEntry("selection", "[\"中国\",\"上海\"]").containsEntry("rating", 5);
    assertThat(snapshot.details().get("child-1")).extracting(row -> row.values().get("amount")).containsExactly("30.03", "16.50");
    var forbidden = calculated("calc-forbidden", new RecordUpdateMutation("parent", created.recordId(), 0,
        Map.of("amount", "999")), null, false);
    assertThatThrownBy(() -> service.execute(forbidden)).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> service.preview(forbidden)).isInstanceOf(DnaRuntimeException.class);
    var forgedDetail = calculated("calc-forged-child", new RecordUpdateMutation("parent", created.recordId(), 0,
        Map.of("title", "报销")), List.of(new OwnedRecordRow(null, null, Map.of("title", "伪造", "amount", "999"))), false);
    assertThatThrownBy(() -> service.execute(forgedDetail)).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> service.preview(forgedDetail)).isInstanceOf(DnaRuntimeException.class);
    var changed = calculated("calc-parent-update", new RecordUpdateMutation("parent", created.recordId(), 0,
        Map.of("title", "报销", "exchange_rate", "0.9500")), null, false);
    assertThat(service.preview(changed).values()).containsEntry("amount", "48.98");
    assertThat(service.preview(changed).details().get("child-1")).hasSize(2);
    assertThat(calculatedView(service, created.recordId()).parent().values()).containsEntry("amount", "46.53");
    assertThat(service.findResult(changed)).isNull();
    connections.clear();
    reads.set(0);
    tracked.set(true);
    try {
      assertThat(service.execute(changed).version()).isEqualTo(1);
    } finally {
      tracked.set(false);
    }
    assertThat(connections).hasSize(1);
    assertThat(reads.get()).isGreaterThanOrEqualTo(2);
    connections.clear();
    reads.set(0);
    tracked.set(true);
    OwnedRecordView current;
    try {
      current = calculatedView(service, created.recordId());
    } finally {
      tracked.set(false);
    }
    assertThat(connections).as("Parent, children and parent version check share one read transaction").hasSize(1);
    assertThat(reads.get()).isGreaterThanOrEqualTo(3);
    assertThat(current.parent().values()).containsEntry("amount", "48.98");
    assertThatThrownBy(() -> service.preview(changed)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT));
    assertThat(current.details().get("child-1")).extracting(RecordView::version).containsExactly(1L, 1L);
    var ruleFailure = calculated("calc-aggregate-rule", new RecordUpdateMutation("parent", created.recordId(), 1,
        Map.of("exchange_rate", "0.0001")), null, true);
    assertThatThrownBy(() -> service.execute(ruleFailure)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
    assertThat(service.findResult(ruleFailure)).isNull();
    assertThat(service.snapshot(ruleFailure)).isNull();
    assertThat(calculatedView(service, created.recordId())).isEqualTo(current);
    assertThat(service.snapshot(create)).isEqualTo(snapshot);
    var historicalFailure = calculated("calc-historical-rule", new RecordUpdateMutation("parent", created.recordId(), 1,
        Map.of("title", "禁止")), null, true);
    assertThatThrownBy(() -> service.preview(new OwnedRecordCommand(historicalFailure.parent(), historicalFailure.details())))
        .isInstanceOfSatisfying(DnaRuntimeException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
    assertThatThrownBy(() -> service.execute(historicalFailure)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
    assertThat(service.findResult(historicalFailure)).isNull();
    assertThat(service.snapshot(historicalFailure)).isNull();
    assertThat(calculatedView(service, created.recordId())).isEqualTo(current);
    assertThat(service.snapshot(create)).isEqualTo(snapshot);
    var formulaFailure = calculated("calc-zero", new RecordUpdateMutation("parent", created.recordId(), 1,
        Map.of("exchange_rate", "0")), null, false);
    assertThatThrownBy(() -> service.execute(formulaFailure)).isInstanceOf(DnaRuntimeException.class);
    assertThat(service.findResult(formulaFailure)).isNull();
    assertThat(calculatedView(service, created.recordId())).isEqualTo(current);
    for (String title : List.of("无效级联", "无效评分")) {
      var domainFailure = calculated("calc-component-" + (title.equals("无效级联") ? "selection" : "rating"),
          new RecordUpdateMutation("parent", created.recordId(), 1, Map.of("title", title)), null, true);
      assertThatThrownBy(() -> service.execute(domainFailure)).isInstanceOfSatisfying(DnaRuntimeException.class,
          failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
      assertThatThrownBy(() -> service.preview(new OwnedRecordCommand(domainFailure.parent(), domainFailure.details())))
          .isInstanceOf(DnaRuntimeException.class);
      assertThat(service.findResult(domainFailure)).isNull();
      assertThat(service.snapshot(domainFailure)).isNull();
      assertThat(calculatedView(service, created.recordId())).isEqualTo(current);
      assertThat(service.snapshot(create)).isEqualTo(snapshot);
    }
    var databaseFailure = calculated("calc-unique", new RecordUpdateMutation("parent", created.recordId(), 1,
        Map.of("title", "失败回滚")), List.of(new OwnedRecordRow(null, null, Map.of("title", "重复")),
            new OwnedRecordRow(null, null, Map.of("title", "重复"))), false);
    assertThatThrownBy(() -> service.execute(databaseFailure)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED));
    assertThatThrownBy(() -> service.findResult(databaseFailure)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED));
    assertThat(calculatedView(service, created.recordId())).isEqualTo(current);
    var old = current.details().get("child-1").getFirst();
    var removeAndAdd = calculated("calc-detail-change", new RecordUpdateMutation("parent", created.recordId(), 1,
        Map.of("title", "报销")), List.of(new OwnedRecordRow(old.id(), old.version(), Map.of("title", "城际交通")),
            new OwnedRecordRow(null, null, Map.of("title", "餐饮"))), false);
    assertThat(service.preview(removeAndAdd).values()).containsEntry("amount", "42.15");
    assertThat(calculatedView(service, created.recordId())).isEqualTo(current);
    service.execute(removeAndAdd);
    assertThat(calculatedView(service, created.recordId()).parent().values()).containsEntry("amount", "42.15");
    assertThat(service.snapshot(create)).isEqualTo(snapshot);
    var bypass = compiler.mutation(parent, access, new RecordUpdateMutation("bypass", created.recordId(), 2,
        Map.of("amount", "999")), Instant.now());
    assertThatThrownBy(() -> backend.execute(parent, new RecordWriteAdmission(ACTOR.tenantId(), 1), List.of(bypass), 30_000))
        .isInstanceOf(DnaRuntimeException.class);
    var stale = command("calc-stale-page", new RecordUpdateMutation("parent", created.recordId(), 2,
        Map.of("amount", "999")), List.of());
    assertThatThrownBy(() -> service.execute(stale)).isInstanceOf(DnaRuntimeException.class);
    var first = calculated("calc-race-first", new RecordUpdateMutation("parent", created.recordId(), 2,
        Map.of("exchange_rate", "1")), null, false);
    var second = calculated("calc-race-second", new RecordUpdateMutation("parent", created.recordId(), 2,
        Map.of("exchange_rate", "2")), null, false);
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var a = workers.submit(() -> race(service, first, start));
      var b = workers.submit(() -> race(service, second, start));
      start.countDown();
      assertThat(List.of(a.get(40, TimeUnit.SECONDS), b.get(40, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
    }
    var won = calculatedView(service, created.recordId());
    assertThat(won.parent().version()).isEqualTo(3);
    assertThat(won.parent().values().get("amount")).isIn("40.04", "20.03");
    assertThat(won.details().get("child-1")).hasSize(2);
    var replayA = service.findResult(first);
    var replayB = service.findResult(second);
    assertThat((replayA == null) != (replayB == null)).isTrue();
    assertThat(service.execute(replayA != null ? first : second)).isEqualTo(replayA != null ? replayA : replayB);
    var clear = calculated("calc-clear", new RecordUpdateMutation("parent", created.recordId(), 3,
        Map.of("title", "清空", "lifecycle_state", "SUBMITTED")), List.of(), false);
    service.execute(clear);
    assertThat(calculatedView(service, created.recordId()).parent().values()).containsEntry("amount", "0.00");
    var beforeInvalidTransition = calculatedView(service, created.recordId());
    var lockedMain = calculated("calc-locked-main", new RecordUpdateMutation("parent", created.recordId(), 4,
        Map.of("title", "不能修改")), null, true);
    for (var locked : List.of(lockedMain)) {
      assertThatThrownBy(() -> service.execute(locked)).isInstanceOfSatisfying(DnaRuntimeException.class,
          failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
      assertThatThrownBy(() -> service.preview(new OwnedRecordCommand(locked.parent(), locked.details())))
          .isInstanceOf(DnaRuntimeException.class);
      assertThat(service.findResult(locked)).isNull();
      assertThat(service.snapshot(locked)).isNull();
      assertThat(calculatedView(service, created.recordId())).isEqualTo(beforeInvalidTransition);
    }
    for (String next : List.of("DRAFT", "UNKNOWN")) {
      var invalidTransition = calculated("calc-transition-" + next, new RecordUpdateMutation("parent", created.recordId(), 4,
          Map.of("lifecycle_state", next)), null, true);
      assertThatThrownBy(() -> service.execute(invalidTransition)).isInstanceOfSatisfying(DnaRuntimeException.class,
          failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
      assertThatThrownBy(() -> service.preview(new OwnedRecordCommand(invalidTransition.parent(), invalidTransition.details())))
          .isInstanceOf(DnaRuntimeException.class);
      assertThat(service.findResult(invalidTransition)).isNull();
      assertThat(service.snapshot(invalidTransition)).isNull();
      assertThat(calculatedView(service, created.recordId())).isEqualTo(beforeInvalidTransition);
    }
    service.execute(calculated("calc-delete", new RecordDeleteMutation("parent", created.recordId(), 4), null, false));
    assertThat(service.execute(create)).isEqualTo(created);
    assertThat(service.snapshot(create)).isEqualTo(snapshot);

    var rowPolicySeed = service.execute(calculated("row-policy-seed", new RecordCreateMutation("parent",
        Map.of("title", "明细准入", "lifecycle_state", "SUBMITTED")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "原始明细"))), false));
    var rowPolicyBefore = calculatedView(service, rowPolicySeed.recordId());
    var lockedRow = rowPolicyBefore.details().get("child-1").getFirst();
    var lockedChild = calculated("row-policy-locked", new RecordUpdateMutation("parent", rowPolicySeed.recordId(), 0,
        Map.of("exchange_rate", "1")), List.of(new OwnedRecordRow(lockedRow.id(), lockedRow.version(), Map.of("title", "不能修改"))), true);
    assertThatThrownBy(() -> service.execute(lockedChild)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
    assertThatThrownBy(() -> service.preview(new OwnedRecordCommand(lockedChild.parent(), lockedChild.details())))
        .isInstanceOf(DnaRuntimeException.class);
    assertThat(service.findResult(lockedChild)).isNull();
    assertThat(service.snapshot(lockedChild)).isNull();
    assertThat(calculatedView(service, rowPolicySeed.recordId())).isEqualTo(rowPolicyBefore);
    // New detail rows retain the existing APP create semantics even on an already submitted aggregate.
    service.execute(calculated("row-policy-add", new RecordUpdateMutation("parent", rowPolicySeed.recordId(), 0,
        Map.of("exchange_rate", "1")), List.of(new OwnedRecordRow(lockedRow.id(), lockedRow.version(), Map.of()),
            new OwnedRecordRow(null, null, Map.of("title", "新增明细"))), false));
    assertThat(calculatedView(service, rowPolicySeed.recordId()).details().get("child-1")).hasSize(2);
    service.execute(calculated("row-policy-cleanup", new RecordDeleteMutation("parent", rowPolicySeed.recordId(), 1), null, false));

    CalculatedTransactionDatabaseAssertions.verify(ACTOR, SCHEMA, parent, policies, models, backend, compiler, service);
    var renamedParent = rename(parent, "rate", "conversion_rate");
    var renamedChild = rename(child, "price", "price_amount");
    for (var pair : List.of(List.of(parent, renamedParent), List.of(child, renamedChild))) {
      var plan = ddl.plan(pair.getFirst().target(), "Aggregate", pair.getFirst().activeSchema(), pair.getLast().activeSchema());
      for (var step : plan.steps()) {
        ddl.execute(pair.getFirst().target(), plan.dialectId(), step);
      }
    }
    when(models.resolve(ACTOR, parent.physicalModelId(), SCHEMA)).thenReturn(renamedParent);
    when(models.resolve(ACTOR, parent.physicalModelId(), rootReference)).thenReturn(renamedParent);
    when(models.resolve(ACTOR, child.physicalModelId(), SCHEMA)).thenReturn(renamedChild);
    when(models.resolve(ACTOR, child.physicalModelId(), childReference)).thenReturn(renamedChild);
    when(policies.authorize(ACTOR, renamedParent)).thenReturn(access);
    when(policies.owned(ACTOR, renamedChild, access)).thenReturn(access);
    var historical = calculated("calc-historical-columns", new RecordCreateMutation("parent",
        Map.of("title", "历史页面", "exchange_rate", "0.95")), List.of(new OwnedRecordRow(null, null,
            Map.of("title", "交通费", "unit_price", "10.01"))), true);
    var historicalPreview = service.preview(new OwnedRecordCommand(historical.parent(), historical.details()));
    assertThat(historicalPreview.values()).containsEntry("amount", "10.54").containsEntry("exchange_rate", "0.95");
    assertThat(historicalPreview.details().get("child-1").getFirst()).containsEntry("unit_price", "10.01");
    var historicalResult = service.execute(historical);
    assertThat(service.snapshot(historical).parent().values()).containsEntry("amount", "10.54")
        .containsEntry("exchange_rate", "0.9500");
    assertThat(service.snapshot(historical).details().get("child-1").getFirst().values()).containsEntry("unit_price", "10.01");
    assertThat(service.snapshot(create)).isEqualTo(snapshot);
    service.execute(calculated("calc-historical-delete", new RecordDeleteMutation("parent", historicalResult.recordId(), 0), null, false));
    var privacyCommand = calculated("calc-read-privacy", new RecordCreateMutation("parent",
        Map.of("title", "授权读取", "exchange_rate", "0.95")), List.of(new OwnedRecordRow(null, null,
            Map.of("title", "交通费", "unit_price", "10.01"))), true);
    var privacyResult = service.execute(privacyCommand);
    CalculatedReadDatabaseAssertions.verify(source, core, dialect, context, catalog, namespace, renamedParent, renamedChild,
        models, policies, backend, compiler, service, access, SCHEMA, rootReference, privacyCommand, privacyResult);
  }

  private static RuntimeRecordModel rename(RuntimeRecordModel model, String id, String code) {
    var fields = model.activeSchema().fields().stream().map(field -> !field.fieldId().equals(id) ? field
        : new LogicalFieldDefinition(field.fieldId(), code, field.displayName(), field.type(), field.nullable(), field.length(),
            field.precision(), field.scale(), field.defaultValue())).toList();
    var schema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, fields, model.activeSchema().indexes(), model.activeSchema().owner());
    return new RuntimeRecordModel(model.physicalModelId(), model.appId(), model.formId(), 2, schema,
        model.requestedVersion(), model.requestedSchema(), model.target());
  }

  private static boolean race(OwnedRecordService service, OwnedRecordCommand command, CountDownLatch start) throws Exception {
    start.await(10, TimeUnit.SECONDS);
    try {
      service.execute(command);
      return true;
    } catch (DnaRuntimeException failure) {
      assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT);
      return false;
    }
  }

  private static OwnedRecordView calculatedView(OwnedRecordService service, String id) {
    return service.get(new OwnedRecordQuery(new RecordLookupQuery("model-1", SCHEMA, id, List.of("title", "amount")),
        List.of(new OwnedRecordModel("child-1", SCHEMA))));
  }

  private static OwnedRecordCommand calculated(String key, RecordMutation mutation, List<OwnedRecordRow> rows, boolean snapshot) {
    return new OwnedRecordCommand(new RecordTransactionCommand("model-1", SCHEMA, List.of(mutation), 30_000, key, 1),
        rows == null ? List.of() : List.of(new OwnedRecordSet(new OwnedRecordModel("child-1", SCHEMA), rows)),
        snapshot ? new OwnedRecordSnapshotRequest(List.of(new OwnedRecordModel("child-1", SCHEMA))) : null);
  }

  private static DataSource tracked(DataSource source, AtomicBoolean enabled, Set<Connection> connections, AtomicInteger reads) {
    return new org.springframework.jdbc.datasource.DelegatingDataSource(source) {
      @Override
      public Connection getConnection() throws SQLException {
        Connection physical = super.getConnection();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              if (enabled.get() && method.getName().equals("prepareStatement") && arguments[0] instanceof String sql
                  && sql.matches("(?is).*\\b(parents|details)\\b.*")) {
                assertThat(physical.getAutoCommit()).as("Business aggregate SQL must share the physical transaction").isFalse();
                connections.add(physical);
                if (sql.stripLeading().toUpperCase(java.util.Locale.ROOT).startsWith("SELECT")) {
                  reads.incrementAndGet();
                }
              }
              try {
                return method.invoke(physical, arguments);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
      }
    };
  }

  private static OwnedRecordQuery query(String id) {
    return new OwnedRecordQuery(new RecordLookupQuery("model-1", SCHEMA, id, List.of("title")), List.of(new OwnedRecordModel("child-1", SCHEMA)));
  }
  private static OwnedRecordCommand command(String key, RecordMutation mutation, List<OwnedRecordRow> rows) {
    return new OwnedRecordCommand(new RecordTransactionCommand("model-1", SCHEMA, List.of(mutation), 30_000, key),
        List.of(new OwnedRecordSet(new OwnedRecordModel("child-1", SCHEMA), rows)));
  }
}
