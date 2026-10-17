package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCountQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

import org.simplepoint.plugin.dna.runtime.api.record.*;
import org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation;
class OwnedRecordServiceDatabaseTest {
  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));
  private static final OwnedRecordModel CHILD = new OwnedRecordModel("child-1", SCHEMA);

  @Test
  void createsUpdatesDeletesAndReplaysOneAggregate() throws Exception {
    var service = fixture();
    var create = command("create", new RecordCreateMutation("parent", Map.of("title", "Parent")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "First"))));
    var created = service.execute(create);
    assertThat(service.execute(create)).isEqualTo(created);
    var current = service.get(query(created.recordId()));
    var row = current.details().get("child-1").getFirst();
    assertThat(row.values()).containsEntry("title", "First");
    var update = command("update", new RecordUpdateMutation("parent", created.recordId(), 0, Map.of("title", "Updated")),
        List.of(new OwnedRecordRow(row.id(), row.version(), Map.of("title", "Edited")),
            new OwnedRecordRow(null, null, Map.of("title", "Second"))));
    assertThat(service.execute(update).version()).isEqualTo(1);
    assertThat(service.get(query(created.recordId())).details().get("child-1")).hasSize(2);
    service.execute(command("delete", new RecordDeleteMutation("parent", created.recordId(), 1), List.of()));
    assertThat(service.findResult(update).version()).isEqualTo(1);
    assertThatThrownBy(() -> service.get(query(created.recordId()))).isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void failingChildRollsBackParentAndReceipt() throws Exception {
    var service = fixture();
    var bad = command("rollback", new RecordCreateMutation("parent", Map.of("title", "Never committed")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "duplicate")),
            new OwnedRecordRow(null, null, Map.of("title", "duplicate"))));
    assertThatThrownBy(() -> service.execute(bad)).isInstanceOf(RuntimeException.class);
    assertThat(service.findResult(bad)).isNull();
    var good = command("rollback", new RecordCreateMutation("parent", Map.of("title", "Committed")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "unique"))));
    var created = service.execute(good);
    assertThat(service.get(query(created.recordId())).parent().values()).containsEntry("title", "Committed");
  }

  @Test
  void rejectsForeignRowsAndStaleParentWithoutChangingEitherAggregate() throws Exception {
    var service = fixture();
    var first = service.execute(command("first", new RecordCreateMutation("parent", Map.of("title", "A")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "A row")))));
    var second = service.execute(command("second", new RecordCreateMutation("parent", Map.of("title", "B")), List.of()));
    var row = service.get(query(first.recordId())).details().get("child-1").getFirst();
    assertThatThrownBy(() -> service.execute(command("foreign", new RecordUpdateMutation("parent", second.recordId(), 0, Map.of("title", "Bad")),
        List.of(new OwnedRecordRow(row.id(), row.version(), Map.of("title", "Stolen"))))))
        .isInstanceOfSatisfying(DnaRuntimeException.class, failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT));
    assertThat(service.get(query(first.recordId())).details().get("child-1").getFirst().values()).containsEntry("title", "A row");
    assertThat(service.get(query(second.recordId())).parent().version()).isZero();
    assertThatThrownBy(() -> service.execute(command("stale", new RecordUpdateMutation("parent", first.recordId(), 99, Map.of("title", "Bad")), List.of())))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void snapshotsFreezeExactCommittedValuesAcrossLaterUpdatesDeletesAndRetries() throws Exception {
    var service = fixture();
    var base = command("snapshot-create", new RecordCreateMutation("parent", Map.of("title", "提交時", "amount", "9007199254740993.01")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "中文・日本語", "amount", "9007199254740993.02"))));
    var captured = new OwnedRecordCommand(base.parent(), base.details(), new OwnedRecordSnapshotRequest(List.of(CHILD)));
    assertThat(service.snapshot(captured)).isNull();
    var created = service.execute(captured);
    var snapshot = service.snapshot(captured);
    assertThat(snapshot.parent().values()).containsEntry("title", "提交時").containsEntry("amount", "9007199254740993.01");
    assertThat(snapshot.details().get("child-1").getFirst().values()).containsEntry("amount", "9007199254740993.02");
    var wire = new ObjectMapper().readValue(new ObjectMapper().writeValueAsBytes(snapshot), OwnedRecordView.class);
    assertThat(wire).isEqualTo(snapshot);
    assertThat(snapshot.details().get("child-1").getFirst().values()).containsEntry("title", "中文・日本語");
    var patch = new OwnedRecordCommand(new RecordTransactionCommand("model-1", SCHEMA,
        List.of(new RecordUpdateMutation("parent", created.recordId(), 0, Map.of("title", "修改后"))),
        30_000, "capture-unchanged-children"), List.of(), new OwnedRecordSnapshotRequest(List.of(CHILD)));
    service.execute(patch);
    assertThat(service.snapshot(patch).details()).isEqualTo(snapshot.details());
    service.execute(command("delete-snapshotted", new RecordDeleteMutation("parent", created.recordId(), 1), List.of()));
    assertThat(service.snapshot(captured)).isEqualTo(snapshot);
    assertThat(service.execute(captured)).isEqualTo(created);
    assertThat(service.snapshot(captured)).isEqualTo(snapshot);
  }

  @Test
  void failedAggregateDoesNotLeaveABusinessSnapshot() throws Exception {
    var service = fixture();
    var base = command("failed-snapshot", new RecordCreateMutation("parent", Map.of("title", "No snapshot")),
        List.of(new OwnedRecordRow(null, null, Map.of("title", "duplicate")),
            new OwnedRecordRow(null, null, Map.of("title", "duplicate"))));
    var captured = new OwnedRecordCommand(base.parent(), base.details(), new OwnedRecordSnapshotRequest(List.of(CHILD)));
    assertThatThrownBy(() -> service.execute(captured)).isInstanceOf(RuntimeException.class);
    assertThat(service.snapshot(captured)).isNull();
    assertThat(service.findResult(captured)).isNull();
  }

  private static OwnedRecordQuery query(String id) {
    return new OwnedRecordQuery(new RecordLookupQuery("model-1", SCHEMA, id, List.of("title")), List.of(CHILD));
  }
  private static OwnedRecordCommand command(String key, RecordMutation parent, List<OwnedRecordRow> rows) {
    return new OwnedRecordCommand(new RecordTransactionCommand("model-1", SCHEMA, List.of(parent), 30_000, key),
        List.of(new OwnedRecordSet(CHILD, rows)));
  }

  private static OwnedRecordService fixture() throws Exception {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:owned-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"); h2.setUser("sa");
    SimpleDataSource dataSource = new SimpleDataSource(h2);
    String system = "id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, record_version BIGINT NOT NULL, "
        + "created_by VARCHAR(64) NOT NULL, created_org_dept_id VARCHAR(64), created_at TIMESTAMP NOT NULL, "
        + "updated_by VARCHAR(64) NOT NULL, updated_at TIMESTAMP NOT NULL, deleted_at TIMESTAMP, title VARCHAR(128) NOT NULL";
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE simpoint_dna_record_operations_v1 (" + system.replace(", title VARCHAR(128) NOT NULL", "")
          + ", model_id VARCHAR(64), operation_key VARCHAR(128), request_fingerprint VARCHAR(64), result_json CLOB)");
      statement.execute("CREATE TABLE simpoint_dna_record_snapshots_v1 (" + system.replace(", title VARCHAR(128) NOT NULL", "")
          + ", model_id VARCHAR(64), operation_key VARCHAR(128), request_fingerprint VARCHAR(64), snapshot_json CLOB)");
      statement.execute("CREATE TABLE records (" + system + ", amount DECIMAL(24,2))");
      statement.execute("CREATE TABLE details (" + system + ", amount DECIMAL(24,2), parent_record_id VARCHAR(36) NOT NULL, row_order INT NOT NULL, UNIQUE(parent_record_id, title))");
    }
    var title = new LogicalFieldDefinition("title", "title", "Title", LogicalFieldType.STRING, false, 128, null, null, null);
    var schema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(title, new LogicalFieldDefinition("amount", "amount", "Amount", LogicalFieldType.DECIMAL, true, null, 24, 2, null)), List.of());
    var childSchema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(title, new LogicalFieldDefinition("amount", "amount", "Amount", LogicalFieldType.DECIMAL, true, null, 24, 2, null),
        new LogicalFieldDefinition("parent", "parent_record_id", "Parent", LogicalFieldType.UUID, false, null, null, null, null),
        new LogicalFieldDefinition("order", "row_order", "Order", LogicalFieldType.INT32, false, null, null, null, null)),
        List.of(), new OwnedModelRelation("model-1", "parent"));
    var parent = new RuntimeRecordModel("model-1", "app", "form", 1, schema, 1, schema,
        new PhysicalSchemaTarget("model-1", new RuntimeMetadataTarget("source", null, "PUBLIC", "records")));
    var child = new RuntimeRecordModel("child-1", "app", "detail", 1, childSchema, 1, childSchema,
        new PhysicalSchemaTarget("child-1", new RuntimeMetadataTarget("source", null, "PUBLIC", "details")));
    var provider = mock(ManagedRuntimeDataSourceProvider.class);
    var dialect = H2RecordWriteFenceFixture.prepare(dataSource, "tenant-a", "model-1");
    when(provider.open(any())).thenReturn(new ManagedRuntimeDataSource(dataSource, false, null, null, dialect, null, null, "PUBLIC"));
    var context = mock(RuntimeRequestContext.class);
    var models = mock(RuntimeRecordModelResolver.class);
    var policies = mock(RuntimeRecordAccessPolicy.class);
    var actor = new RuntimeActor("tenant-a", "user-a");
    var access = new RecordAccessContext(actor, "app.form", Set.of("id", "record_version", "title", "amount", "parent_record_id", "row_order"),
        Set.of("title", "amount"), Set.of(), new DataScopeCondition("ALL", "created_org_dept_id", "created_by", actor.actorId(), Set.of()), "dept");
    when(context.requireActor()).thenReturn(actor);
    when(models.resolve(actor, "model-1", SCHEMA)).thenReturn(parent);
    when(models.resolve(actor, "child-1", SCHEMA)).thenReturn(child);
    when(policies.authorize(actor, parent)).thenReturn(access);
    when(policies.owned(actor, child, access)).thenReturn(access);
    var compiler = new RecordSqlCompiler(new StandardLogicalValueConverter());
    var backend = new RuntimeCalciteRecordBackend(provider, new DefaultCalciteQueryEngine());
    var audit = mock(RecordAuditSupport.class);
    var admission = new RecordAdmissionController(new DnaRuntimeExecutionProperties());
    var observer = mock(RecordExecutionObserver.class);
    var queries = new RecordQueryServiceImpl(context, models, policies, compiler, backend, audit, admission, observer);
    return new OwnedRecordService(context, models, policies, compiler, backend, queries, new RecordRequestFingerprint(new ObjectMapper()), admission, audit, observer);
  }
}
