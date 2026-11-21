package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class RecordCurrentCopyReadTest {
  private final RuntimeRecordModel parent = model();
  private final RecordAccessContext access =
      new RecordAccessContext(
          new RuntimeActor("tenant", "actor"),
          "source-page",
          Set.of("id", "address", "other"),
          Set.of(),
          Set.of(),
          null,
          null);
  private final RecordCalculationStore.Installed policy =
      new RecordCalculationStore.Installed(
          1,
          new RecordCalculationPolicy(
              1,
              "app",
              "form",
              new RecordSchemaReference(1, "a".repeat(64)),
              new RecordCalculationDefinition("1", Map.of(), Map.of()),
              Map.of("source", Set.of("address"))));

  @AfterEach
  void clearContext() {
    org.simplepoint.core.RequestContextHolder.clearContext(
        org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void fiveHundredRowsUseFiveLedgerBatchesAndOneDetachedGroupedAuthorization() {
    clearContext();
    var rows = rows(500, "sensitive");
    var queries = new ArrayList<RecordSelectPlan>();
    var captured =
        RecordCurrentCopyRead.capture(
            parent,
            access,
            List.of(),
            rows,
            policy,
            plan -> {
              queries.add(plan);
              return result(rows, plan, 1);
            });
    assertThat(queries).hasSize(5).allSatisfy(plan -> assertThat(plan.maxRows()).isEqualTo(100));
    var authorization = authorization();
    var denied = captured.authorize(authorization, new RecordExecutionDeadline(30000));
    assertThat(denied)
        .hasSize(500)
        .allSatisfy(value -> assertThat(value.parent().values()).containsOnlyKeys("other"));
    assertThat(denied.stream().map(value -> value.parent().id()).toList())
        .isEqualTo(rows.stream().map(value -> value.parent().id()).toList());
    verify(authorization, times(1)).authorizeRequirements(any(), any(), any());
    doReturn(Set.copyOf(RecordReferenceCopyStoreTest.origin().requirements()))
        .when(authorization)
        .authorizeRequirements(any(), any(), any());
    assertThat(captured.authorize(authorization, new RecordExecutionDeadline(30000)))
        .isEqualTo(rows);
  }

  @Test
  void hiddenOrMaskedValuesNeverAddExternalAuthorizationRequirements() {
    var rows = rows(1, "***");
    var captured =
        RecordCurrentCopyRead.capture(
            parent, access, List.of(), rows, policy, plan -> result(rows, plan, 1));
    var authorization = authorization();
    assertThat(
            captured
                .authorize(authorization, new RecordExecutionDeadline(30000))
                .getFirst()
                .parent()
                .values())
        .containsOnlyKeys("other");
    verify(authorization)
        .authorizeRequirements(any(), org.mockito.ArgumentMatchers.eq(Set.of()), any());
  }

  @Test
  void staleLedgerOrDuplicateSourceRowsFailBeforeReturningAnyCurrentValues() {
    var rows = rows(1, "sensitive");
    assertThatThrownBy(
            () ->
                RecordCurrentCopyRead.capture(
                    parent, access, List.of(), rows, policy, plan -> result(rows, plan, 0)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                RecordCurrentCopyRead.capture(
                    parent,
                    access,
                    List.of(),
                    List.of(rows.getFirst(), rows.getFirst()),
                    policy,
                    plan -> {
                      throw new AssertionError("Duplicates must fail before querying");
                    }))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void legacyOrdinaryAndEmptyPagesDoNotQueryTheOriginLedger() {
    var rows = rows(1, "ordinary");
    var captured =
        RecordCurrentCopyRead.capture(
            parent,
            access,
            List.of(),
            rows,
            null,
            plan -> {
              throw new AssertionError("Legacy ordinary read must not query origins");
            });
    assertThat(captured.authorize(authorization(), new RecordExecutionDeadline(30000)))
        .isEqualTo(rows);
    var empty =
        RecordCurrentCopyRead.capture(
            parent,
            access,
            List.of(),
            List.of(),
            policy,
            plan -> {
              throw new AssertionError("Empty read must not query origins");
            });
    assertThat(empty.authorize(authorization(), new RecordExecutionDeadline(30000))).isEmpty();
  }

  @Test
  void excessivePagesAreRejectedBeforeLedgerOrTargetQueries() {
    assertThatThrownBy(
            () ->
                RecordCurrentCopyRead.capture(
                    parent,
                    access,
                    List.of(),
                    rows(501, "value"),
                    policy,
                    plan -> {
                      throw new AssertionError("Over-budget page must fail before querying");
                    }))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(
                        org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode
                            .RECORD_QUERY_INVALID));
  }

  private CalciteQueryResult result(
      List<OwnedRecordView> source, RecordSelectPlan query, long version) {
    var store = new RecordReferenceCopyStore();
    List<List<Object>> rows = new ArrayList<>();
    for (var aggregate : source) {
      var key = new RecordReferenceCopyStore.Key("tenant", "source", aggregate.parent().id());
      if (query.parameters().contains(key.id())) {
        String json =
            (String)
                store
                    .write(
                        key,
                        "actor",
                        null,
                        version,
                        List.of(RecordReferenceCopyStoreTest.origin()),
                        Instant.EPOCH)
                    .parameters()
                    .getLast();
        rows.add(List.of(key.id(), key.tenantId(), key.modelId(), key.recordId(), version, json));
      }
    }
    return new CalciteQueryResult(List.of(), rows, false, rows.size(), 0, null);
  }

  private static List<OwnedRecordView> rows(int count, String copied) {
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(
            index ->
                new OwnedRecordView(
                    new RecordView(
                        UUID.randomUUID().toString(),
                        1,
                        Map.of("address", copied, "other", "ordinary")),
                    Map.of()))
        .toList();
  }

  private static RecordReferenceCopyAuthorization authorization() {
    return spy(
        new RecordReferenceCopyAuthorization(
            mock(RuntimeRecordModelResolver.class),
            mock(RuntimeRecordAccessPolicy.class),
            mock(RecordSqlCompiler.class),
            mock(RuntimeCalciteRecordBackend.class),
            mock(RecordCalculationAccess.class)));
  }

  private static RuntimeRecordModel model() {
    var schema =
        new PhysicalSchemaDefinition(
            "v1",
            List.of(
                new LogicalFieldDefinition(
                    "address",
                    "address",
                    "Address",
                    LogicalFieldType.STRING,
                    true,
                    255,
                    null,
                    null,
                    null),
                new LogicalFieldDefinition(
                    "other",
                    "other",
                    "Other",
                    LogicalFieldType.STRING,
                    true,
                    255,
                    null,
                    null,
                    null)),
            List.of());
    return new RuntimeRecordModel(
        "source",
        "app",
        "form",
        1,
        schema,
        new PhysicalSchemaTarget(
            "source", new RuntimeMetadataTarget("db", null, "public", "source")));
  }
}
