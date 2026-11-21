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
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

class RecordReferenceCopyAuthorizationTest {
  private static final RuntimeActor ACTOR = new RuntimeActor("tenant", "reader");
  private static final String ROOT = UUID.randomUUID().toString();
  private static final String CHILD = UUID.randomUUID().toString();

  @AfterEach
  void clear() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void batchesIdentitiesAcrossFieldsAndPreservesResourceRowScopesWithoutReadingValues() {
    var f = new Fixture();
    var origins =
        java.util.stream.IntStream.range(0, 201)
            .mapToObj(
                i -> {
                  String id = UUID.randomUUID().toString();
                  return origin(id, List.of(requirement(id, null, null)));
                })
            .collect(java.util.stream.Collectors.toSet());
    assertThat(f.read(origins)).isEqualTo(origins);
    assertThat(f.queries)
        .hasSize(3)
        .allSatisfy(
            query -> {
              assertThat(query.sql())
                  .startsWith("SELECT \"id\" FROM")
                  .contains("\"tenant_id\"", "\"created_org_dept_id\"", "\"deleted_at\" IS NULL");
              assertThat(query.sql()).doesNotContain("current_amount");
              assertThat(query.maxRows()).isLessThanOrEqualTo(100);
            });
  }

  @Test
  void absentTargetGrantAndMasksDoNotBorrowSourcePermissionOrReadTargetRows() {
    var f = new Fixture();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    var origin = origin(ROOT, List.of(requirement(ROOT, null, null)));
    assertThat(f.read(Set.of(origin))).isEmpty();
    verifyNoInteractions(f.backend, f.models);
    f.authority();
    when(f.policies.authorizeReference(ACTOR, f.parent, "page"))
        .thenReturn(access(Set.of("current_amount")));
    assertThat(f.read(Set.of(origin))).isEmpty();
    assertThat(f.queries).isEmpty();
  }

  @Test
  void everyFrozenAncestorMustRemainReadableEvenWhenTheImmediateSourceIsReadable() {
    var f = new Fixture();
    var ancestor =
        new RecordReferenceReadRequirement(
            "ancestor", "app", "form", "secret-page", ROOT, null, null, List.of("amount"));
    var origin = origin(ROOT, List.of(requirement(ROOT, null, null), ancestor));
    assertThat(f.read(Set.of(origin))).isEmpty();
    assertThat(f.queries).hasSize(1);
    assertThat(f.read(Set.of(origin(ROOT, List.of(requirement(ROOT, null, null)))))).hasSize(1);
  }

  @Test
  void ownedDependenciesCheckBothOriginalRootAndCurrentChildMembership() {
    var f = new Fixture();
    var origin =
        origin(ROOT, List.of(requirement(ROOT, null, null), requirement(ROOT, "lines", CHILD)));
    assertThat(f.read(Set.of(origin))).containsExactly(origin);
    assertThat(f.queries).hasSize(2);
    assertThat(f.queries.getLast().sql()).contains("\"parent_record_id\" = ?");
    f.queries.clear();
    f.responses =
        plan ->
            plan.sql().startsWith("SELECT \"id\",") ? result(List.of(), false) : f.rootResult(plan);
    assertThat(f.read(Set.of(origin))).isEmpty();
    f.queries.clear();
    f.responses = plan -> result(List.of(), false);
    assertThat(f.read(Set.of(origin))).isEmpty();
    assertThat(f.queries).hasSize(1);
  }

  @Test
  void emptyHistoricalCollectionsStillRequireOwnedFieldGrantsWithoutInventingRows() {
    var f = new Fixture();
    var origin =
        origin(ROOT, List.of(requirement(ROOT, null, null), requirement(ROOT, "lines", null)));
    assertThat(f.read(Set.of(origin))).containsExactly(origin);
    assertThat(f.queries).hasSize(1);
    when(f.policies.owned(ACTOR, f.child, f.parentAccess))
        .thenReturn(access(Set.of("current_amount")));
    assertThat(f.read(Set.of(origin))).isEmpty();
  }

  @Test
  void malformedTruncatedAndUnavailableInfrastructureNeverBecomeAuthorizationSuccess() {
    var f = new Fixture();
    var origins = Set.of(origin(ROOT, List.of(requirement(ROOT, null, null))));
    f.responses = plan -> result(List.of(List.of(ROOT)), true);
    assertThatThrownBy(() -> f.read(origins)).isInstanceOf(DnaRuntimeException.class);
    f.responses =
        plan -> {
          throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED, "Unavailable");
        };
    assertThatThrownBy(() -> f.read(origins))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED));
    f.responses =
        plan ->
            plan.sql().startsWith("SELECT \"id\",")
                ? result(List.of(List.of(CHILD, UUID.randomUUID().toString())), false)
                : f.rootResult(plan);
    assertThatThrownBy(
            () ->
                f.read(
                    Set.of(
                        origin(
                            ROOT,
                            List.of(
                                requirement(ROOT, null, null),
                                requirement(ROOT, "lines", CHILD))))))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private static RecordReferenceReadRequirement requirement(
      String root, String detail, String row) {
    return new RecordReferenceReadRequirement(
        "target", "app", "form", "page", root, detail, row, List.of("amount"));
  }

  private static RecordReferenceCopyOrigin origin(
      String root, List<RecordReferenceReadRequirement> requirements) {
    return new RecordReferenceCopyOrigin(
        "relation",
        "copy",
        RecordReferenceMapping.Mode.SERVER_SNAPSHOT,
        "destination",
        "a".repeat(64),
        "target",
        "app",
        "form",
        "page",
        root,
        3,
        "amount",
        requirements);
  }

  private static RecordAccessContext access(Set<String> masked) {
    return new RecordAccessContext(
        ACTOR,
        "page",
        Set.of("id", "current_amount"),
        Set.of(),
        masked,
        new DataScopeCondition(
            "CUSTOM", "created_org_dept_id", "created_by", "reader", Set.of("sales")),
        null,
        3);
  }

  private static CalciteQueryResult result(List<List<Object>> rows, boolean truncated) {
    return new CalciteQueryResult(List.of(), rows, truncated, rows.size(), 0, null);
  }

  @Test
  void fiveHundredDerivedRowsResolveLocalOwnedFieldPolicyOnceWithoutReadingBusinessRows() {
    var f = new Fixture();
    Map<String, Set<RecordDerivedCopyOrigin>> rows = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 500; i++) {
      String root = java.util.UUID.randomUUID().toString();
      var origin =
          new RecordDerivedCopyOrigin(
              1,
              "amount",
              1,
              "a".repeat(64),
              List.of(
                  new RecordAggregateReadRequirement(
                      "lines", java.util.UUID.randomUUID().toString(), List.of("amount"))),
              RecordReferenceCopyStoreTest.origin().requirements());
      rows.put(root, Set.of(origin));
    }
    assertThat(
            f.authorization.readableDerivedBatch(
                f.parent, f.parentAccess, rows, new RecordExecutionDeadline(30000)))
        .isEqualTo(rows);
    org.mockito.Mockito.verify(f.models, org.mockito.Mockito.times(1))
        .resolveCurrent(ACTOR, "lines");
    org.mockito.Mockito.verify(f.policies, org.mockito.Mockito.times(1))
        .owned(ACTOR, f.child, f.parentAccess);
    org.mockito.Mockito.verifyNoInteractions(f.backend);
  }

  private static RuntimeRecordModel model(String id, boolean owned) {
    var fields = new ArrayList<LogicalFieldDefinition>();
    fields.add(
        new LogicalFieldDefinition(
            "amount",
            "current_amount",
            "Amount",
            LogicalFieldType.DECIMAL,
            true,
            null,
            18,
            2,
            null));
    if (owned) {
      fields.add(
          new LogicalFieldDefinition(
              "owner",
              "parent_record_id",
              "Owner",
              LogicalFieldType.UUID,
              false,
              null,
              null,
              null,
              null));
    }
    var schema =
        new PhysicalSchemaDefinition(
            "v1", fields, List.of(), owned ? new OwnedModelRelation("target", "owner") : null);
    return new RuntimeRecordModel(
        id,
        "app",
        "form",
        1,
        schema,
        new PhysicalSchemaTarget(id, new RuntimeMetadataTarget("db", null, "public", id)));
  }

  private static final class Fixture {
    final RuntimeRecordModel parent = model("target", false);
    final RecordAccessContext parentAccess = access(Set.of());
    final RuntimeRecordModel child = model("lines", true);
    final RuntimeRecordModelResolver models = mock(RuntimeRecordModelResolver.class);
    final RuntimeRecordAccessPolicy policies = mock(RuntimeRecordAccessPolicy.class);
    final RuntimeCalciteRecordBackend backend = mock(RuntimeCalciteRecordBackend.class);
    final RecordCalculationAccess calculations = mock(RecordCalculationAccess.class);
    final List<RecordSelectPlan> queries = new ArrayList<>();
    Function<RecordSelectPlan, CalciteQueryResult> responses =
        plan ->
            plan.sql().startsWith("SELECT \"id\",")
                ? result(List.of(List.of(CHILD, ROOT)), false)
                : rootResult(plan);
    final RecordReferenceCopyAuthorization authorization =
        new RecordReferenceCopyAuthorization(
            models,
            policies,
            new RecordSqlCompiler(new StandardLogicalValueConverter()),
            backend,
            calculations);

    Fixture() {
      authority();
      when(models.resolveCurrent(ACTOR, "target")).thenReturn(parent);
      when(models.resolveCurrent(ACTOR, "lines")).thenReturn(child);
      when(policies.authorizeReference(ACTOR, parent, "page")).thenReturn(parentAccess);
      when(policies.owned(ACTOR, child, parentAccess)).thenReturn(access(Set.of()));
      when(calculations.resolveReference(any(), any(), any()))
          .thenReturn(new RecordCalculationAccess.Rules(Map.of(), Map.of()));
      doAnswer(
              invocation -> {
                Function<RecordReadSession, Set<RecordReferenceReadRequirement>> callback =
                    invocation.getArgument(4);
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

    void authority() {
      var authority = new AuthorizationContext();
      authority.setResourceDataScopes(
          Map.of("page", new ResourceDataScope("CUSTOM", Set.of("sales"), false)));
      authority.setResourceFieldPermissions(Map.of("page", Map.of()));
      RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authority);
    }

    CalciteQueryResult rootResult(RecordSelectPlan query) {
      var ids =
          query.parameters().stream()
              .filter(value -> value instanceof String text && text.matches("[a-f0-9-]{36}"))
              .map(value -> List.<Object>of(value))
              .toList();
      return result(ids, false);
    }

    Set<RecordReferenceCopyOrigin> read(Set<RecordReferenceCopyOrigin> origins) {
      return authorization.authorize(ACTOR, origins, new RecordExecutionDeadline(30_000));
    }
  }
}
