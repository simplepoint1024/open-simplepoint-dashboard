package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordReferenceSql;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;

class RecordRowLocksTest {
  private static final String FIRST = "00000000-0000-0000-0000-000000000001";
  private static final String SECOND = "00000000-0000-0000-0000-000000000002";
  private final RuntimeRecordModel parent = model("parent", "z_parent");
  private final RuntimeRecordModel child = model("child", "a_child");
  private final Connection connection = mock(Connection.class);
  private final ManagedRuntimeDataSource handle = mock(ManagedRuntimeDataSource.class);
  private final List<String> executed = new ArrayList<>();
  private Object deletedAt;
  private boolean present = true;

  @Test
  void sequencePromotesFutureTargetMutationBeforeAnyStepAndRejectsUnplannedRows() throws Exception {
    var linked = linkedChild();
    var locks = locks(Map.of("parent", parent, "child", linked));
    locks.discover(
        new RecordSequenceLockPlan(
            List.of(
                new RecordSequenceLockPlan.Source("child", FIRST),
                new RecordSequenceLockPlan.Source("parent", SECOND)),
            List.of(new RecordSequenceLockPlan.Reference("child", "parent-ref", SECOND)),
            () -> {}));
    locks.acquire();
    assertThat(executed).containsExactly("WRITE:a_child:" + FIRST, "WRITE:z_parent:" + SECOND);
    locks.requireCovered(prepared(linked, FIRST, SECOND));
    assertThatThrownBy(() -> locks.requireCovered(prepared(linked, SECOND, SECOND)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> locks.requireCovered(prepared(linked, FIRST, FIRST)))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void missingOldTargetsCanBeUnlinkedButCannotBeAcquiredLaterInSequence() throws Exception {
    var linked = linkedChild();
    var locks = locks(Map.of("parent", parent, "child", linked));
    locks.discover(
        new RecordSequenceLockPlan(
            List.of(new RecordSequenceLockPlan.Source("child", FIRST)),
            List.of(new RecordSequenceLockPlan.Reference("child", "parent-ref", SECOND)),
            () -> {}));
    present = false;
    locks.acquire();
    locks.requireCovered(prepared(linked, FIRST, null));
    present = true;
    assertThatThrownBy(() -> locks.requireCovered(prepared(linked, FIRST, SECOND)))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT));
    assertThat(executed).hasSize(2);
  }

  private RecordWritePreparation prepared(RuntimeRecordModel source, String id, String target) {
    return new RecordWritePreparation(
        List.of(statement(source, id, "tenant")),
        null,
        1,
        List.of(new RecordReferenceCheck("parent-ref", "child", id, target, true)));
  }

  private RuntimeRecordModel linkedChild() {
    var schema =
        new PhysicalSchemaDefinition(
            org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion.CURRENT,
            List.of(
                new LogicalFieldDefinition(
                    "parent",
                    "parent_id",
                    "Parent",
                    LogicalFieldType.UUID,
                    true,
                    null,
                    null,
                    null,
                    null)),
            List.of(),
            null,
            true,
            List.of(new LogicalReferenceDefinition("parent-ref", "parent", "parent", false)));
    return new RuntimeRecordModel(
        "child",
        "app",
        "form",
        1,
        schema,
        new PhysicalSchemaTarget(
            "child",
            child.target().metadataTarget(),
            Map.of("parent", parent.target().metadataTarget())));
  }

  @Test
  void locksAcrossTablesAndIdsAreOrderedAndSharedWriteCollisionsAcquireOnceAsWrite()
      throws Exception {
    var locks = locks();
    locks.reference(parent.target(), SECOND);
    locks.mutation(statement(parent, SECOND, "tenant"));
    locks.mutation(statement(child, SECOND, "tenant"));
    locks.reference(child.target(), FIRST);
    locks.mutation(statement(parent, FIRST, "tenant"));
    locks.acquire();
    assertThat(executed)
        .containsExactly(
            "READ:a_child:" + FIRST,
            "WRITE:a_child:" + SECOND,
            "WRITE:z_parent:" + FIRST,
            "WRITE:z_parent:" + SECOND);
    assertThatThrownBy(() -> locks.reference(parent.target(), FIRST))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(locks::acquire).isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void missingOrDeletedTargetsRemainRequiredAfterPromotionToMutationLocks() throws Exception {
    for (boolean missing : List.of(true, false)) {
      var locks = locks();
      present = !missing;
      deletedAt = missing ? null : Instant.now();
      locks.reference(parent.target(), FIRST);
      locks.mutation(statement(parent, FIRST, "tenant"));
      assertThatThrownBy(locks::acquire)
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT));
      var mutation = locks();
      mutation.mutation(statement(parent, SECOND, "tenant"));
      mutation.acquire(); // Missing/deleted mutation sources retain the ordinary DML/version
      // diagnostic.
    }
  }

  @Test
  void rejectsOtherTenantNamespaceAndNonTransactionalExecutionBeforeIssuingLocks()
      throws Exception {
    var locks = locks();
    assertThatThrownBy(() -> locks.mutation(statement(parent, FIRST, "other")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                locks.reference(
                    new PhysicalSchemaTarget(
                        "foreign",
                        new RuntimeMetadataTarget("other-store", "db", "public", "other")),
                    FIRST))
        .isInstanceOf(DnaRuntimeException.class);
    locks.mutation(statement(parent, FIRST, "tenant"));
    when(connection.getAutoCommit()).thenReturn(true);
    assertThatThrownBy(locks::acquire).isInstanceOf(DnaRuntimeException.class);
    verify(connection, never()).prepareStatement(anyString());
  }

  private RecordRowLocks locks() throws Exception {
    return locks(Map.of(parent.physicalModelId(), parent, child.physicalModelId(), child));
  }

  private RecordRowLocks locks(Map<String, RuntimeRecordModel> sources) throws Exception {
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    var dialect = mock(RuntimeSchemaDialect.class);
    when(handle.runtimeDialect()).thenReturn(dialect);
    when(dialect.recordReferenceSql(any(), any(), any(), eq("id")))
        .thenAnswer(
            call -> {
              String table =
                  ((PhysicalSchemaTarget) call.getArgument(2)).metadataTarget().tableName();
              return new RecordReferenceSql("", "READ:" + table, "", "WRITE:" + table);
            });
    when(connection.prepareStatement(anyString()))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              var query = mock(PreparedStatement.class);
              var id = new AtomicReference<String>();
              doAnswer(
                      parameter -> {
                        if ((int) parameter.getArgument(0) == 2) {
                          id.set(parameter.getArgument(1));
                        }
                        return null;
                      })
                  .when(query)
                  .setString(anyInt(), anyString());
              when(query.executeQuery())
                  .thenAnswer(
                      ignored -> {
                        executed.add(sql + ":" + id.get());
                        var rows = mock(ResultSet.class);
                        when(rows.next()).thenReturn(present, false);
                        when(rows.getObject(1)).thenReturn(deletedAt);
                        return rows;
                      });
              return query;
            });
    return new RecordRowLocks(
        connection, handle, "tenant", new RecordExecutionDeadline(10_000), sources);
  }

  private static RuntimeRecordModel model(String id, String table) {
    return new RuntimeRecordModel(
        id,
        "app",
        "form",
        1,
        new PhysicalSchemaDefinition(
            org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion.CURRENT,
            List.of(),
            List.of()),
        new PhysicalSchemaTarget(id, new RuntimeMetadataTarget("store", "db", "public", table)));
  }

  private static RecordSqlStatement statement(RuntimeRecordModel model, String id, String tenant) {
    return new RecordSqlStatement(
        "",
        List.of(),
        RecordMutationType.UPDATE,
        id,
        1,
        Instant.now(),
        new RecordSqlStatement.Subject(model.physicalModelId(), tenant));
  }
}
