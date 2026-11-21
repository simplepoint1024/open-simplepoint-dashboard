package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

class RecordReferenceMappingTargetsTest {
  private static final String FIRST = "11111111-1111-1111-1111-111111111111";
  private static final String SECOND = "22222222-2222-2222-2222-222222222222";
  private static final String CHILD = "33333333-3333-3333-3333-333333333333";

  @Test
  void batchesExactEntranceAndChecksOriginalPermissionsAfterClosingTheRead() {
    var fixture = new Fixture();
    var result = fixture.read(Set.of(FIRST, SECOND), Set.of("address"));
    assertThat(result).containsOnlyKeys(FIRST, SECOND);
    assertThat(fixture.queries).hasSize(2);
    assertThat(fixture.authorizationCalls).isEqualTo(1);
    assertThat(result.get(FIRST).value().values()).containsEntry("address", "客户😀");
    assertThat(result.get(FIRST).requirements().get("address"))
        .singleElement()
        .satisfies(
            requirement -> {
              assertThat(requirement.resourceCode()).isEqualTo("exact-page");
              assertThat(requirement.recordId()).isEqualTo(FIRST);
            });
  }

  @Test
  void policyAndRowChangesRejectBeforeReturningValues() {
    var policy = new Fixture();
    policy.fence = 2;
    assertThatThrownBy(() -> policy.read(Set.of(FIRST), Set.of("address")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThat(policy.queries).isEmpty();
    var row = new Fixture();
    row.versionDrift = true;
    assertThatThrownBy(() -> row.read(Set.of(FIRST), Set.of("address")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThat(row.authorizationCalls).isZero();
  }

  @Test
  void decliningAllEditableDefaultsNeedsOnlyAnAuthorizedIdentityObservation() {
    var fixture = new Fixture();
    fixture.installed =
        new RecordCalculationStore.Installed(
            1,
            new RecordCalculationPolicy(
                1,
                "app",
                "form",
                new RecordSchemaReference(1, "a".repeat(64)),
                new RecordCalculationDefinition("1", Map.of(), Map.of()),
                Map.of("target", Set.of("address"))));
    var result = fixture.read(Set.of(FIRST), Set.of());
    assertThat(result.get(FIRST).value().id()).isEqualTo(FIRST);
    assertThat(result.get(FIRST).value().version()).isZero();
    assertThat(result.get(FIRST).value().values()).isEmpty();
    assertThat(result.get(FIRST).requirements()).isEmpty();
    assertThat(fixture.authorizationCalls).isZero();
    assertThat(fixture.queries)
        .hasSize(2)
        .allSatisfy(
            query -> {
              assertThat(query.valueFields()).isEmpty();
              assertThat(query.sql()).doesNotContain("origins_json", "address");
            });
  }

  @Test
  void missingMaskedOrDeniedDependenciesNeverBecomeAnEmptyAuthorizedCopy() {
    var hidden = new Fixture();
    hidden.rules = new RecordCalculationAccess.Rules(Map.of("target", Set.of("address")), Map.of());
    assertThatThrownBy(() -> hidden.read(Set.of(FIRST), Set.of("address")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThat(hidden.queries).isEmpty();
    var missing = new Fixture();
    missing.parentRows.remove(SECOND);
    assertThatThrownBy(() -> missing.read(Set.of(FIRST, SECOND), Set.of("address")))
        .isInstanceOf(DnaRuntimeException.class);
    var denied = new Fixture();
    denied.deny = true;
    assertThatThrownBy(() -> denied.read(Set.of(FIRST), Set.of("address")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThat(denied.authorizationCalls).isEqualTo(1);
  }

  @Test
  void retainedCopiedOriginsAreReadInBatchAndCheckedAtTheirOriginalEntrance() {
    var fixture = new Fixture();
    fixture.installed =
        new RecordCalculationStore.Installed(
            1,
            new RecordCalculationPolicy(
                1,
                "app",
                "form",
                new RecordSchemaReference(1, "a".repeat(64)),
                new RecordCalculationDefinition("1", Map.of(), Map.of()),
                Map.of("target", Set.of("address"))));
    fixture.ledger = true;
    var result = fixture.read(Set.of(FIRST, SECOND), Set.of("address"));
    assertThat(fixture.queries.stream().filter(query -> query.sql().contains("origins_json")))
        .hasSize(1);
    assertThat(result.get(FIRST).requirements().get("address"))
        .containsAll(RecordReferenceCopyStoreTest.origin().requirements());
    assertThat(result.get(SECOND).requirements().get("address")).hasSize(1);
    fixture.ledgerVersion = 1;
    assertThatThrownBy(() -> fixture.read(Set.of(FIRST), Set.of("address")))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void wideSelectionsUseBoundedVerticalQueriesWithoutExposingUnselectedInputs() {
    var fields =
        java.util.stream.IntStream.range(0, 101)
            .mapToObj(index -> field("field_" + index))
            .toList();
    var model =
        new RuntimeRecordModel(
            "target",
            "app",
            "form",
            1,
            new PhysicalSchemaDefinition("v1", fields, List.of()),
            new PhysicalSchemaTarget(
                "target", new RuntimeMetadataTarget("db", null, "public", "target")));
    var fixture = new Fixture(model);
    Map<String, Object> values = new LinkedHashMap<>();
    fields.forEach(field -> values.put(field.code(), "值"));
    fixture.parentRows.put(FIRST, new RecordView(FIRST, 0, values));
    var selected =
        fields.stream()
            .map(LogicalFieldDefinition::fieldId)
            .collect(java.util.stream.Collectors.toSet());
    var result = fixture.read(Set.of(FIRST), selected);
    assertThat(result.get(FIRST).value().values()).hasSize(101).containsAllEntriesOf(values);
    assertThat(fixture.queries)
        .hasSize(3)
        .allSatisfy(query -> assertThat(query.valueFields().size()).isLessThanOrEqualTo(100));
    assertThat(fixture.authorizationCalls).isEqualTo(1);
  }

  @Test
  void formulaInputsUseOneOwnedBatchAndRetainEmptyCollectionFieldRequirements() {
    var fixture = new Fixture();
    var child = model("lines", true);
    when(fixture.policies.owned(any(), any(), any())).thenReturn(access(child));
    when(fixture.calculations.bind(any(), any(), any()))
        .thenReturn(
            new RecordCalculationWriteSupport.Binding(
                null,
                fixture.parent,
                Map.of("lines", child),
                Map.of("parent.total", Set.of("parent.address", "lines.address")),
                null));
    fixture.detailRows =
        List.of(new RecordView(CHILD, 0, Map.of("address", "input", "parent_record_id", FIRST)));
    var result = fixture.read(Set.of(FIRST, SECOND), Set.of("total"));
    assertThat(result.get(FIRST).value().values()).containsOnlyKeys("total");
    assertThat(result.get(FIRST).requirements().get("total")).hasSize(2);
    assertThat(result.get(SECOND).requirements().get("total"))
        .hasSize(2)
        .anySatisfy(
            requirement -> {
              assertThat(requirement.detailModelId()).isEqualTo("lines");
              assertThat(requirement.detailRecordId()).isNull();
            });
    assertThat(fixture.queries).hasSize(4);
    assertThat(fixture.authorizationCalls).isEqualTo(1);
  }

  @Test
  void excessiveOrForeignOwnedRowsAndOversizedRequestsFailClosed() {
    var fixture = new Fixture();
    assertThatThrownBy(() -> fixture.read(Set.of(), Set.of("address")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> fixture.read(Set.of(FIRST), Set.of("unknown")))
        .isInstanceOf(DnaRuntimeException.class);
    var child = model("lines", true);
    when(fixture.policies.owned(any(), any(), any())).thenReturn(access(child));
    when(fixture.calculations.bind(any(), any(), any()))
        .thenReturn(
            new RecordCalculationWriteSupport.Binding(
                null,
                fixture.parent,
                Map.of("lines", child),
                Map.of("parent.total", Set.of("parent.address", "lines.address")),
                null));
    fixture.detailRows =
        List.of(new RecordView(CHILD, 0, Map.of("address", "input", "parent_record_id", SECOND)));
    assertThatThrownBy(() -> fixture.read(Set.of(FIRST), Set.of("total")))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private static RuntimeRecordModel model(String id, boolean owned) {
    var fields = new ArrayList<>(List.of(field("address"), field("total")));
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

  private static LogicalFieldDefinition field(String code) {
    return new LogicalFieldDefinition(
        code, code, code, LogicalFieldType.STRING, true, 100, null, null, null);
  }

  private static RecordAccessContext access(RuntimeRecordModel model) {
    var fields = new java.util.HashSet<>(model.requestedFieldCodes());
    fields.addAll(Set.of("id", "record_version", "created_at", "row_order"));
    return new RecordAccessContext(
        new RuntimeActor("tenant", "actor"), "exact-page", fields, fields, Set.of(), null, null, 1);
  }

  private static CalciteQueryResult result(List<List<Object>> rows) {
    return new CalciteQueryResult(List.of(), rows, false, rows.size(), 0, null);
  }

  private static final class Fixture {
    final RuntimeRecordModel parent;
    final RuntimeRecordAccessPolicy policies = mock(RuntimeRecordAccessPolicy.class);
    final RuntimeCalciteRecordBackend backend = mock(RuntimeCalciteRecordBackend.class);
    final RecordCalculationWriteSupport calculations = mock(RecordCalculationWriteSupport.class);
    final RecordCalculationAccess access = mock(RecordCalculationAccess.class);
    final RecordReferenceCopyAuthorization authorization =
        mock(RecordReferenceCopyAuthorization.class);
    final List<RecordSelectPlan> queries = new ArrayList<>();
    final Map<String, RecordView> parentRows = new LinkedHashMap<>();
    List<RecordView> detailRows = List.of();
    RecordCalculationStore.Installed installed;
    RecordCalculationAccess.Rules rules = new RecordCalculationAccess.Rules(Map.of(), Map.of());
    int authorizationCalls;
    long fence = 1;
    boolean reading;
    boolean versionDrift;
    boolean deny;
    boolean ledger;
    long ledgerVersion;
    final RecordReferenceMappingTargets reader =
        new RecordReferenceMappingTargets(
            policies,
            new RecordSqlCompiler(new StandardLogicalValueConverter()),
            backend,
            calculations,
            access,
            authorization,
            new PhysicalSchemaCodec(new ObjectMapper()));

    Fixture() {
      this(model("target", false));
    }

    Fixture(RuntimeRecordModel parent) {
      this.parent = parent;
      parentRows.put(FIRST, new RecordView(FIRST, 0, Map.of("address", "客户😀", "total", "one")));
      parentRows.put(
          SECOND, new RecordView(SECOND, 0, Map.of("address", "second", "total", "two")));
      when(access.resolveReference(any(), any(), any())).thenAnswer(invocation -> rules);
      when(authorization.authorizeRequirements(any(), anySet(), any()))
          .thenAnswer(
              invocation -> {
                assertThat(reading).isFalse();
                authorizationCalls++;
                Set<RecordReferenceReadRequirement> requirements = invocation.getArgument(1);
                return deny ? Set.of() : requirements;
              });
      doAnswer(
              invocation -> {
                Function<RecordReadSession, Object> callback = invocation.getArgument(4);
                reading = true;
                try {
                  return callback.apply(
                      new RecordReadSession(
                          new RecordWriteFenceKey("tenant", "target"),
                          fence,
                          installed,
                          this::query));
                } finally {
                  reading = false;
                }
              })
          .when(backend)
          .read(any(), anyList(), any(), anyInt(), any());
    }

    Map<String, RecordReferenceMappingTargets.Captured> read(Set<String> ids, Set<String> fields) {
      return reader.read(
          new RecordReferenceAdmission.Target(
              parent, access(parent), new RecordReferenceAdmission.TargetSnapshot(1, installed)),
          ids,
          fields,
          new RecordExecutionDeadline(30_000));
    }

    CalciteQueryResult query(RecordSelectPlan query) {
      assertThat(reading).isTrue();
      queries.add(query);
      if (query.sql().contains("origins_json")) {
        if (!ledger) {
          return result(List.of());
        }
        var key = new RecordReferenceCopyStore.Key("tenant", "target", FIRST);
        var write =
            new RecordReferenceCopyStore()
                .write(
                    key,
                    "actor",
                    null,
                    0,
                    List.of(RecordReferenceCopyStoreTest.origin()),
                    Instant.now());
        String json =
            write.parameters().stream()
                .filter(value -> value instanceof String text && text.startsWith("{"))
                .map(String.class::cast)
                .findFirst()
                .orElseThrow();
        return result(List.of(List.of(key.id(), "tenant", "target", FIRST, ledgerVersion, json)));
      }
      if (query.sql().startsWith("SELECT COUNT")) {
        return result(List.of(List.of((long) detailRows.size())));
      }
      var rows =
          query.sql().contains("\"lines\"")
              ? detailRows
              : parentRows.values().stream()
                  .filter(row -> query.parameters().contains(row.id()))
                  .toList();
      List<List<Object>> output = new ArrayList<>();
      for (var row : rows) {
        List<Object> values = new ArrayList<>();
        values.add(row.id());
        values.add(versionDrift && query.valueFields().isEmpty() ? 1L : row.version());
        query.valueFields().forEach(field -> values.add(row.values().get(field)));
        output.add(values);
      }
      return result(output);
    }
  }
}
