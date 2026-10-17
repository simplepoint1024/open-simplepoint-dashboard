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

    var provider = mock(ManagedRuntimeDataSourceProvider.class);
    when(provider.open(any())).thenReturn(new ManagedRuntimeDataSource(new SimpleDataSource(source), false, context, core, dialect,
        database, catalog, postgres ? namespace : null));
    var title = new LogicalFieldDefinition("title", "title", "Title", LogicalFieldType.STRING, false, 128, null, null, null);
    var schema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(title, new LogicalFieldDefinition("amount", "amount", "Amount", LogicalFieldType.DECIMAL, true, null, 24, 2, null)), List.of());
    var childSchema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(title, new LogicalFieldDefinition("amount", "amount", "Amount", LogicalFieldType.DECIMAL, true, null, 24, 2, null),
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
    var access = new RecordAccessContext(ACTOR, "app.form", Set.of("id", "record_version", "title", "amount", "parent_record_id", "row_order"),
        Set.of("title", "amount"), Set.of(), new DataScopeCondition("ALL", "created_org_dept_id", "created_by", ACTOR.actorId(), Set.of()), null);
    when(requestContext.requireActor()).thenReturn(ACTOR);
    when(models.resolve(ACTOR, "model-1", SCHEMA)).thenReturn(parent);
    when(models.resolve(ACTOR, "child-1", SCHEMA)).thenReturn(child);
    when(policies.authorize(ACTOR, parent)).thenReturn(access);
    when(policies.owned(ACTOR, child, access)).thenReturn(access);
    var compiler = new RecordSqlCompiler(new StandardLogicalValueConverter());
    var audit = mock(RecordAuditSupport.class);
    var admission = new RecordAdmissionController(new DnaRuntimeExecutionProperties());
    var observer = mock(RecordExecutionObserver.class);
    var queries = new RecordQueryServiceImpl(requestContext, models, policies, compiler, backend, audit, admission, observer);
    var service = new OwnedRecordService(requestContext, models, policies, compiler, backend, queries,
        new RecordRequestFingerprint(mapper), admission, audit, observer);
    var bad = command("rollback", new RecordCreateMutation("parent", Map.of("title", "Rollback")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "Duplicate")), new OwnedRecordRow(null, null, Map.of("title", "Duplicate"))));
    assertThatThrownBy(() -> service.execute(bad)).isInstanceOf(DnaRuntimeException.class);
    assertThat(service.findResult(bad)).isNull();
    assertThat(queries.count(new RecordCountQuery("model-1", SCHEMA, null))).isZero();
    var create = command("create", new RecordCreateMutation("parent", Map.of("title", "中文・日本語", "amount", "9007199254740993.01")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "明细・明細", "amount", "9007199254740993.02"))));
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
    assertThat(service.execute(update).version()).isEqualTo(1);
    assertThat(service.get(query(created.recordId())).details().get("child-1")).hasSize(2);
    var remove = command("remove", new RecordUpdateMutation("parent", created.recordId(), 1, Map.of("title", "No details")), List.of());
    service.execute(remove);
    assertThat(service.get(query(created.recordId())).details().get("child-1")).isEmpty();
    service.execute(command("delete", new RecordDeleteMutation("parent", created.recordId(), 2), List.of()));
    assertThat(service.findResult(update).version()).isEqualTo(1);
    assertThat(service.snapshot(create)).isEqualTo(originalSnapshot);
    assertThat(service.execute(create)).isEqualTo(created);
    assertThat(queries.count(new RecordCountQuery("model-1", SCHEMA, null))).isZero();
  }

  private static OwnedRecordQuery query(String id) {
    return new OwnedRecordQuery(new RecordLookupQuery("model-1", SCHEMA, id, List.of("title")), List.of(new OwnedRecordModel("child-1", SCHEMA)));
  }
  private static OwnedRecordCommand command(String key, RecordMutation mutation, List<OwnedRecordRow> rows) {
    return new OwnedRecordCommand(new RecordTransactionCommand("model-1", SCHEMA, List.of(mutation), 30_000, key),
        List.of(new OwnedRecordSet(new OwnedRecordModel("child-1", SCHEMA), rows)));
  }
}
