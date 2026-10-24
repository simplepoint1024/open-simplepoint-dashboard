package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.*;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;

/** Real target reads, snapshot provenance, renamed columns and policy-activation races. */
final class CalculatedReadDatabaseAssertions {
  private CalculatedReadDatabaseAssertions() {}

  static void verify(DataSource source, JdbcDatabaseDialect core, RuntimeSchemaDialect dialect,
      JdbcDatabaseDialect.SupportContext context, String catalog, String namespace,
      RuntimeRecordModel parent, RuntimeRecordModel child, RuntimeRecordModelResolver models,
      RuntimeRecordAccessPolicy policies, RuntimeCalciteRecordBackend backend, RecordSqlCompiler compiler,
      OwnedRecordService owned, RecordAccessContext original, RecordSchemaReference schema,
      RecordSchemaReference policySchema, OwnedRecordCommand command, RecordCommandResult created) throws Exception {
    var actor = original.actor();
    var requests = mock(RuntimeRequestContext.class);
    when(requests.requireActor()).thenReturn(actor);
    var queries = new RecordQueryServiceImpl(requests, models, policies, compiler, backend, mock(RecordAuditSupport.class),
        new RecordAdmissionController(new DnaRuntimeExecutionProperties()), mock(RecordExecutionObserver.class),
        RecordCalculationTestSupport.access(models, policies, compiler));
    var parentLookup = new RecordLookupQuery(parent.physicalModelId(), schema, created.recordId(), List.of());
    var aggregate = new OwnedRecordQuery(parentLookup, List.of(new OwnedRecordModel(child.physicalModelId(), schema)));
    var full = owned.snapshot(command);
    var key = new RecordWriteFenceKey(actor.tenantId(), parent.physicalModelId());
    var restricted = new RecordAccessContext(actor, original.resourceCode(), original.readableFields(), original.writableFields(),
        Set.of("unit_price"), original.rowScope(), original.organizationId(), original.resourceGeneration());
    when(policies.owned(actor, child, original)).thenReturn(restricted);
    assertThat(queries.get(parentLookup).values()).doesNotContainKey("amount").containsKeys("title", "computed_at");
    var hidden = owned.get(aggregate);
    var previewCommand = new OwnedRecordCommand(new RecordTransactionCommand(parent.physicalModelId(), schema,
        List.of(new RecordUpdateMutation("preview", created.recordId(), 0, Map.of("title", "权限试算"))),
        30_000, "preview-privacy", 1), List.of());
    var preview = owned.preview(previewCommand);
    assertThat(preview.values()).doesNotContainKey("amount").containsEntry("display_title", "权限试算");
    assertThat(preview.details().get(child.physicalModelId()).getFirst()).containsEntry("unit_price", "***")
        .doesNotContainKeys("amount", "parent_record_id", "row_order");
    assertThat(owned.findResult(previewCommand)).isNull();
    assertThat(owned.get(aggregate)).isEqualTo(hidden);
    assertThat(hidden.parent().values()).doesNotContainKey("amount");
    assertThat(hidden.details().get(child.physicalModelId()).getFirst().values())
        .containsEntry("unit_price", "***").doesNotContainKey("amount");
    var projected = owned.snapshot(command);
    assertThat(projected.parent().values()).doesNotContainKey("amount");
    assertThat(projected.details().get(child.physicalModelId()).getFirst().values())
        .containsEntry("unit_price", "***").doesNotContainKey("amount");
    var predicate = new RecordFilter(RecordFilterOperator.GREATER_THAN, "amount", List.of("0"), List.of());
    denied(() -> queries.get(new RecordLookupQuery(parent.physicalModelId(), schema, created.recordId(), List.of("amount"))));
    denied(() -> queries.count(new RecordCountQuery(parent.physicalModelId(), schema, predicate)));
    denied(() -> queries.page(new RecordPageQuery(parent.physicalModelId(), schema, 0, 10, List.of("title"), predicate, List.of())));
    denied(() -> queries.page(new RecordPageQuery(parent.physicalModelId(), schema, 0, 10, List.of("title"), null,
        List.of(new RecordSort("amount", RecordSortDirection.ASC)))));
    assertThat(queries.page(new RecordPageQuery(parent.physicalModelId(), schema, 0, 10, List.of(), null, List.of()))
        .records().getFirst().values()).doesNotContainKey("amount");
    var before = backend.read(parent, List.of(), original, 30_000, session -> {
      var operation = RecordOperationIdentity.of(actor, command.parent(), new RecordRequestFingerprint(new com.fasterxml.jackson.databind.ObjectMapper()).of(command));
      var stored = session.snapshot(operation);
      assertThat(stored.calculationRevision()).isEqualTo(1);
      assertThat(session.historical(stored)).isEqualTo(session.calculation());
      return stored;
    });
    // While this reader owns policy v1, an actual target publication cannot install/activate v2.
    var admitted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var publishing = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var read = workers.submit(() -> backend.read(parent, List.of(), original, 30_000, session -> {
        assertThat(session.calculation().revision()).isEqualTo(1);
        admitted.countDown();
        await(release);
        assertThat(session.historical(before).revision()).isEqualTo(1);
        return session.revision();
      }));
      assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();
      var write = workers.submit(() -> {
        try (var connection = source.getConnection()) {
          connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
          connection.setAutoCommit(false);
          publishing.countDown();
          new RecordWriteFence(dialect.recordWriteFenceSql(core, context, parent.target())).publish(connection, key, 1, 2,
              "2".repeat(64), actor.actorId(), 30_000, borrowed -> {
                var statement = new RecordCalculationStore().insert(key, 2, actor.actorId(), Instant.now(),
                    new RecordCalculationPolicy(1, parent.appId(), parent.formId(), policySchema,
                        new RecordCalculationDefinition("1", Map.of(), Map.of())));
                var result = new DefaultCalciteQueryEngine().executeUpdate(new CalciteUpdateRequest(statement.sql(), null, 30_000,
                    RecordSqlParameters.bind(statement.parameters(), dialect)), root -> root.add("runtime",
                    org.apache.calcite.adapter.jdbc.JdbcSchema.create(root, "runtime", new TransactionConnectionDataSource(borrowed), catalog, namespace)));
                assertThat(result.affectedRows()).isEqualTo(1);
              });
          connection.commit();
          return true;
        }
      });
      try {
        assertThat(publishing.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> write.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
      } finally {release.countDown();}
      assertThat(read.get(30, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(write.get(30, TimeUnit.SECONDS)).isTrue();
    } finally {release.countDown();}
    // Removed formulas no longer limit the ordinary stored column, but old snapshots retain v1 dependencies.
    assertThat(queries.get(parentLookup).values()).containsEntry("amount", full.parent().values().get("amount"));
    assertThatThrownBy(() -> owned.preview(previewCommand)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT));
    for (long submittedVersion : List.of(0L, 1L, 2L, 50L)) {
      var parentCommand = command.parent();
      var replay = new OwnedRecordCommand(new RecordTransactionCommand(parentCommand.physicalModelId(), parentCommand.schemaReference(),
          parentCommand.mutations(), parentCommand.timeoutMs(), parentCommand.idempotencyKey(), submittedVersion), command.details(), command.snapshot());
      assertThat(owned.snapshot(replay)).isEqualTo(projected);
    }
    when(policies.owned(actor, child, original)).thenReturn(original);
    assertThat(owned.snapshot(command)).isEqualTo(full);
    // A missing historical definition cannot silently become an unrestricted legacy snapshot.
    try (var connection = source.getConnection(); var statement = connection.prepareStatement("UPDATE "
        + (namespace == null ? "`" + catalog + "`" : "\"" + namespace + "\"")
        + ".simpoint_dna_record_calculations_v1 SET deleted_at = CURRENT_TIMESTAMP WHERE model_id = ? AND policy_revision = 1")) {
      statement.setString(1, parent.physicalModelId());
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
    assertThatThrownBy(() -> owned.snapshot(command)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.PERSISTENCE_FAILED));
  }

  private static void denied(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
  }
  private static void await(CountDownLatch latch) {
    try {assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();}
    catch (InterruptedException failure) {Thread.currentThread().interrupt();throw new AssertionError(failure);}
  }
}
