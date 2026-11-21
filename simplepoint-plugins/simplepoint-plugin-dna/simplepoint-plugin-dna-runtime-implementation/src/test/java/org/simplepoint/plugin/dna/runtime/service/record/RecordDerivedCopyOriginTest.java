package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class RecordDerivedCopyOriginTest {
  private static final String ROW = "11111111-1111-1111-1111-111111111111";
  private final RecordReferenceCopyStore store = new RecordReferenceCopyStore();
  private final RecordReferenceCopyStore.Key key =
      new RecordReferenceCopyStore.Key("tenant", "source", ROW);
  private final RecordDerivedCopyOrigin derived = derived(ROW);

  @Test
  void ledgerPreservesDerivedInputsAcrossOrdinaryEditsAndRequiresExplicitReplacement() {
    var created = store.write(key, "actor", null, 1, List.of(), List.of(derived), Instant.EPOCH);
    String json = (String) created.parameters().getLast();
    assertThat(json).contains("\"contractVersion\":2", "localRequirements");
    var current = store.read(key, result(1, json));
    assertThat(current.derivedOrigins()).containsExactly(derived);
    var retained = store.write(key, "actor", current, 2, List.of(), Instant.EPOCH);
    assertThat(store.read(key, result(2, (String) retained.parameters().get(3))).derivedOrigins())
        .containsExactly(derived);
    var cleared = store.write(key, "actor", current, 3, List.of(), List.of(), Instant.EPOCH);
    var clearJson = (String) cleared.parameters().get(3);
    assertThat(clearJson).isEqualTo("{\"contractVersion\":1,\"origins\":[]}");
    assertThat(store.read(key, result(3, clearJson)).derivedOrigins()).isEmpty();
    for (String invalid :
        List.of(
            json.replace("\"calculationRevision\":7,", ""),
            json.replace("\"recordId\":\"" + ROW + "\",", ""),
            json + " {}")) {
      assertThatThrownBy(() -> store.read(key, result(1, invalid)))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  @Test
  void snapshotsUseExplicitVersionAndRemovedGraphsCannotDeclassifyStoredResults() throws Exception {
    var values =
        new OwnedRecordView(
            new RecordView(ROW, 1, Map.of("total_code", "secret", "input_code", "value")),
            Map.of());
    var captured = new RecordReferenceCopySnapshot(null, ROW, 1, List.of(), List.of(derived));
    var snapshot = RecordStoredSnapshot.capture(values, null).withCopyOrigins(List.of(captured));
    assertThat(snapshot.contractVersion()).isEqualTo(4);
    var json = new ObjectMapper().writeValueAsString(snapshot);
    assertThat(new ObjectMapper().readValue(json, RecordStoredSnapshot.class)).isEqualTo(snapshot);
    assertThatThrownBy(() -> new RecordStoredSnapshot(3, 0, null, values, List.of(captured)))
        .isInstanceOf(IllegalArgumentException.class);
    var projection = new RecordReferenceCopyProjection(model(), List.of(), Map.of());
    assertThat(projection.visibleDerived(values, List.of(captured))).containsExactly(derived);
    assertThat(projection.project(values, List.of(captured), Set.of()).parent().values())
        .containsOnlyKeys("input_code");
    assertThat(projection.project(values, List.of(captured), Set.of(), Set.of(derived)))
        .isEqualTo(values);
  }

  @Test
  void localFieldMasksAndOriginalRootIdentityDoNotRequireBusinessDataReads() {
    var models = mock(RuntimeRecordModelResolver.class);
    var policies = mock(RuntimeRecordAccessPolicy.class);
    var backend = mock(RuntimeCalciteRecordBackend.class);
    var authorization =
        new RecordReferenceCopyAuthorization(
            models,
            policies,
            mock(RecordSqlCompiler.class),
            backend,
            mock(RecordCalculationAccess.class));
    var actor = new RuntimeActor("tenant", "actor");
    var access =
        new RecordAccessContext(
            actor,
            "source-page",
            Set.of("id", "input_code", "total_code"),
            Set.of(),
            Set.of(),
            null,
            null);
    assertThat(
            authorization.readableDerived(
                model(), access, ROW, Set.of(derived), new RecordExecutionDeadline(30000)))
        .containsExactly(derived);
    var masked =
        new RecordAccessContext(
            actor,
            "source-page",
            access.readableFields(),
            Set.of(),
            Set.of("input_code"),
            null,
            null);
    assertThat(
            authorization.readableDerived(
                model(), masked, ROW, Set.of(derived), new RecordExecutionDeadline(30000)))
        .isEmpty();
    assertThat(
            authorization.readableDerived(
                model(),
                access,
                ROW,
                Set.of(derived("33333333-3333-3333-3333-333333333333")),
                new RecordExecutionDeadline(30000)))
        .isEmpty();
    verifyNoInteractions(models, policies, backend);
  }

  @Test
  void recopyAfterFormulaRemovalKeepsOriginalExternalAndRelativeLocalInputs() {
    var values =
        new OwnedRecordView(new RecordView(ROW, 1, Map.of("total_code", "retained")), Map.of());
    var captured = new RecordReferenceCopySnapshot(null, ROW, 1, List.of(), List.of(derived));
    var requirements =
        RecordReferenceCopyRequirements.capture(
                model(), null, values, List.of(captured), "new-entrance", Set.of("total"))
            .get("total");
    assertThat(requirements).containsAll(derived.requirements());
    assertThat(
            requirements.stream()
                .filter(requirement -> requirement.resourceCode().equals("new-entrance"))
                .toList())
        .singleElement()
        .satisfies(
            requirement -> {
              assertThat(requirement.recordId()).isEqualTo(ROW);
              assertThat(requirement.fieldIds()).containsExactly("input", "total");
            });
  }

  @Test
  void duplicateProvidersAndUnboundedOrAbsentProofAreRejected() {
    assertThatThrownBy(
            () ->
                new RecordDerivedCopyOrigin(
                    1, "total", 7, "a".repeat(64), List.of(), derived.requirements()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RecordReferenceCopyStore.Stored(1, List.of(), List.of(derived, derived)))
        .isInstanceOf(DnaRuntimeException.class);
    var copied = RecordReferenceCopyStoreTest.origin();
    var conflicting =
        new RecordDerivedCopyOrigin(
            1,
            copied.destinationFieldId(),
            7,
            "a".repeat(64),
            derived.localRequirements(),
            derived.requirements());
    assertThatThrownBy(
            () -> new RecordReferenceCopyStore.Stored(1, List.of(copied), List.of(conflicting)))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private static RecordDerivedCopyOrigin derived(String root) {
    return new RecordDerivedCopyOrigin(
        1,
        "total",
        7,
        "a".repeat(64),
        List.of(new RecordAggregateReadRequirement("source", root, List.of("input"))),
        RecordReferenceCopyStoreTest.origin().requirements());
  }

  private static RuntimeRecordModel model() {
    var schema =
        new PhysicalSchemaDefinition(
            "v1",
            List.of(
                new LogicalFieldDefinition(
                    "input",
                    "input_code",
                    "Input",
                    LogicalFieldType.STRING,
                    true,
                    100,
                    null,
                    null,
                    null),
                new LogicalFieldDefinition(
                    "total",
                    "total_code",
                    "Total",
                    LogicalFieldType.STRING,
                    true,
                    100,
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

  private CalciteQueryResult result(long version, String json) {
    return new CalciteQueryResult(
        List.of(),
        List.of(List.of(key.tenantId(), key.modelId(), key.recordId(), version, json)),
        false,
        1,
        0,
        null);
  }
}
