package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceSnapshot;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

class RecordReferenceSnapshotHistoryTest {
  private static final RuntimeActor ACTOR = new RuntimeActor("tenant", "reader");
  private static final String SOURCE_ID = UUID.randomUUID().toString();
  private static final String TARGET_ID = UUID.randomUUID().toString();

  @AfterEach
  void clear() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void currentAuthorizationReadsOnlyIdsAndKeepsFrozenTextAndVersion() {
    var f = new Fixture();
    var result = f.read(view(label(TARGET_ID)));
    assertThat(result.referenceSnapshots()).containsExactly(label(TARGET_ID));
    assertThat(f.queries).hasSize(1);
    assertThat(f.queries.getFirst().sql())
        .startsWith("SELECT \"id\" FROM ")
        .doesNotContain("current_name");
    assertThat(f.queries.getFirst().parameters()).contains("tenant", "sales", TARGET_ID);
  }

  @Test
  void manyDistinctTargetsAreBatchedAndRepeatedTargetsAreDeduplicated() {
    var f = new Fixture();
    var labels = new ArrayList<RecordReferenceSnapshot>();
    for (int index = 0; index < 101; index++) {
      String target = UUID.randomUUID().toString();
      for (int duplicate = 0; duplicate < 2; duplicate++) {
        labels.add(
            new RecordReferenceSnapshot(
                "product",
                "detail",
                UUID.randomUUID().toString(),
                "product",
                "target",
                "app",
                "customers",
                "customers",
                target,
                4,
                "name",
                "Frozen"));
      }
    }
    f.responses =
        query ->
            result(
                query.parameters().stream()
                    .filter(value -> value instanceof String text && text.length() == 36)
                    .map(value -> List.<Object>of(value))
                    .toList(),
                false);
    var view = new OwnedRecordView(new RecordView(SOURCE_ID, 1, Map.of()), Map.of(), labels);
    assertThat(f.read(view).referenceSnapshots()).isEqualTo(labels);
    assertThat(f.queries).hasSize(2);
    assertThat(f.queries.stream().mapToInt(RecordSelectPlan::maxRows).sum()).isEqualTo(101);
  }

  @Test
  void missingExplicitAuthorityAndMaskedFieldsNeverReadTheTarget() {
    var absent = new Fixture();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    assertThat(absent.read(view(label(TARGET_ID))).referenceSnapshots()).isEmpty();
    verifyNoInteractions(absent.models, absent.policies, absent.backend);
    for (var masked : List.of(Set.of("current_name"), Set.of("id"))) {
      var f = new Fixture();
      when(f.policies.authorizeReference(ACTOR, f.target, "customers"))
          .thenReturn(f.access(masked));
      assertThat(f.read(view(label(TARGET_ID))).referenceSnapshots()).isEmpty();
      verifyNoInteractions(f.backend);
    }
  }

  @Test
  void currentTransitiveFieldRestrictionsSuppressLabelsBeforeAnyRecordQuery() {
    var f = new Fixture();
    when(f.calculations.resolveReference(any(), any(), any()))
        .thenReturn(new RecordCalculationAccess.Rules(Map.of("target", Set.of("name")), Map.of()));
    assertThat(f.read(view(label(TARGET_ID))).referenceSnapshots()).isEmpty();
    assertThat(f.queries).isEmpty();
  }

  @Test
  void rowScopeOmissionsRemoveOnlyThoseLabelsAndBadResultsFailClosed() {
    var f = new Fixture();
    f.result = result(List.of(), false);
    assertThat(f.read(view(label(TARGET_ID))).referenceSnapshots()).isEmpty();
    for (var bad :
        List.of(
            result(List.of(List.of(UUID.randomUUID().toString())), false),
            result(List.of(List.of(TARGET_ID), List.of(TARGET_ID)), false),
            result(List.of(List.of(TARGET_ID)), true))) {
      f.result = bad;
      assertThatThrownBy(() -> f.read(view(label(TARGET_ID))))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  @Test
  void deniedOrOfflineTargetsAreOmittedButStorageFailureIsNotHidden() {
    for (var code :
        List.of(
            DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
            DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED,
            DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND)) {
      var f = new Fixture();
      when(f.models.resolveCurrent(ACTOR, "target"))
          .thenThrow(new DnaRuntimeException(code, "unavailable"));
      assertThat(f.read(view(label(TARGET_ID))).referenceSnapshots()).isEmpty();
      verifyNoInteractions(f.backend);
    }
    var failure = new Fixture();
    when(failure.models.resolveCurrent(ACTOR, "target"))
        .thenThrow(
            new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED, "storage unavailable"));
    assertThatThrownBy(() -> failure.read(view(label(TARGET_ID))))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void currentOwnerMismatchCannotResolveAgainstAnUnrelatedApplication() {
    var f = new Fixture();
    when(f.models.resolveCurrent(ACTOR, "target"))
        .thenReturn(
            new RuntimeRecordModel(
                "target",
                "another-app",
                "customers",
                2,
                f.target.activeSchema(),
                f.target.target()));
    assertThatThrownBy(() -> f.read(view(label(TARGET_ID))))
        .isInstanceOf(DnaRuntimeException.class);
    verifyNoInteractions(f.backend);
  }

  @Test
  void interruptedSharedBudgetStopsBeforeTargetResolution() {
    var f = new Fixture();
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> f.read(view(label(TARGET_ID))))
          .isInstanceOf(DnaRuntimeException.class);
      verifyNoInteractions(f.models, f.backend);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void stableSourceIdentityUsesHistoricalCodeAndRequiresVisibleMatchingReference() {
    var parent = model("source", "orders", "customer", "old_customer_id", LogicalFieldType.UUID);
    var definition =
        new RecordCalculationDefinition(
            "1",
            Map.of(),
            Map.of(),
            null,
            null,
            null,
            null,
            List.of(
                new RecordReferenceRule(
                    "1",
                    "customer",
                    new RecordValidationField("parent", "customer"),
                    "target",
                    "app",
                    "customers",
                    "customers",
                    null,
                    null,
                    "name")));
    var policy =
        new RecordCalculationStore.Installed(
            1,
            new RecordCalculationPolicy(
                1, "app", "orders", new RecordSchemaReference(1, "a".repeat(64)), definition));
    var captured = List.of(label(TARGET_ID));
    assertThat(
            RecordReferenceSnapshotProvenance.visible(
                parent, List.of(), view(label(TARGET_ID)), captured, policy))
        .containsExactly(label(TARGET_ID));
    for (var values :
        List.of(Map.<String, Object>of(), Map.<String, Object>of("old_customer_id", "***"))) {
      var hidden = new OwnedRecordView(new RecordView(SOURCE_ID, 1, values), Map.of());
      assertThat(
              RecordReferenceSnapshotProvenance.visible(
                  parent, List.of(), hidden, captured, policy))
          .isEmpty();
    }
    assertThatThrownBy(
            () ->
                RecordReferenceSnapshotProvenance.visible(
                    parent, List.of(), view(label(TARGET_ID)), captured, null))
        .isInstanceOf(DnaRuntimeException.class);
    var mismatch = List.of(label(UUID.randomUUID().toString()));
    assertThatThrownBy(
            () ->
                RecordReferenceSnapshotProvenance.visible(
                    parent, List.of(), view(label(TARGET_ID)), mismatch, policy))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private static OwnedRecordView view(RecordReferenceSnapshot label) {
    return new OwnedRecordView(
        new RecordView(SOURCE_ID, 1, Map.of("old_customer_id", TARGET_ID)),
        Map.of(),
        List.of(label));
  }

  private static RecordReferenceSnapshot label(String target) {
    return new RecordReferenceSnapshot(
        "customer",
        "source",
        SOURCE_ID,
        "customer",
        "target",
        "app",
        "customers",
        "customers",
        target,
        7,
        "name",
        "历史名称-日本語");
  }

  private static RuntimeRecordModel model(
      String id, String form, String field, String code, LogicalFieldType type) {
    var schema =
        new PhysicalSchemaDefinition(
            "v1",
            List.of(
                new LogicalFieldDefinition(
                    field,
                    code,
                    field,
                    type,
                    true,
                    type == LogicalFieldType.STRING ? 128 : null,
                    null,
                    null,
                    null)),
            List.of());
    return new RuntimeRecordModel(
        id,
        "app",
        form,
        1,
        schema,
        new PhysicalSchemaTarget(id, new RuntimeMetadataTarget("db", null, "public", id)));
  }

  private static CalciteQueryResult result(List<List<Object>> rows, boolean truncated) {
    return new CalciteQueryResult(List.of(), rows, truncated, rows.size(), 0, null);
  }

  private static final class Fixture {
    final RuntimeRecordModelResolver models = mock(RuntimeRecordModelResolver.class);
    final RuntimeRecordAccessPolicy policies = mock(RuntimeRecordAccessPolicy.class);
    final RuntimeCalciteRecordBackend backend = mock(RuntimeCalciteRecordBackend.class);
    final RecordCalculationAccess calculations = mock(RecordCalculationAccess.class);
    final RuntimeRecordModel target =
        model("target", "customers", "name", "current_name", LogicalFieldType.STRING);
    final List<RecordSelectPlan> queries = new ArrayList<>();
    CalciteQueryResult result = result(List.of(List.of(TARGET_ID)), false);
    Function<RecordSelectPlan, CalciteQueryResult> responses = query -> result;
    final RecordReferenceSnapshotReader reader =
        new RecordReferenceSnapshotReader(
            models,
            policies,
            new RecordSqlCompiler(new StandardLogicalValueConverter()),
            backend,
            calculations);

    Fixture() {
      var authority = new AuthorizationContext();
      authority.setResourceDataScopes(
          Map.of("customers", new ResourceDataScope("CUSTOM", Set.of("sales"), false)));
      authority.setResourceFieldPermissions(Map.of("customers", Map.of()));
      RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authority);
      when(models.resolveCurrent(ACTOR, "target")).thenReturn(target);
      when(policies.authorizeReference(ACTOR, target, "customers")).thenReturn(access(Set.of()));
      when(calculations.resolveReference(any(), any(), any()))
          .thenReturn(new RecordCalculationAccess.Rules(Map.of(), Map.of()));
      doAnswer(
              invocation -> {
                Function<RecordReadSession, Set<String>> callback = invocation.getArgument(4);
                return callback.apply(
                    new RecordReadSession(
                        new RecordWriteFenceKey("tenant", "target"),
                        1,
                        null,
                        query -> {
                          queries.add(query);
                          return responses.apply(query);
                        }));
              })
          .when(backend)
          .read(any(), anyList(), any(), anyInt(), any());
    }

    RecordAccessContext access(Set<String> masked) {
      return new RecordAccessContext(
          ACTOR,
          "customers",
          Set.of("id", "current_name"),
          Set.of(),
          masked,
          new DataScopeCondition(
              "CUSTOM", "created_org_dept_id", "created_by", "reader", Set.of("sales")),
          null,
          3);
    }

    OwnedRecordView read(OwnedRecordView values) {
      return reader.authorize(ACTOR, values, new RecordExecutionDeadline(30_000));
    }
  }
}
