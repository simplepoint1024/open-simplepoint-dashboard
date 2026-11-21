package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;

class RecordReferenceCopiedInputsTest {
  private static final String ROW = "11111111-1111-1111-1111-111111111111";
  private static final String TARGET = "33333333-3333-3333-3333-333333333333";
  private final RuntimeRecordModel parent = model();

  @Test
  void confirmedCopyFeedsTheSameRecalculationAndFinalBusinessVersion() {
    var binding = binding(true);
    var old = RecordReferenceCopyStoreTest.origin();
    var copied = copied();
    var after = values("New", "new");
    var statement =
        new RecordSqlStatement(
            "UPDATE business",
            List.of(),
            RecordMutationType.UPDATE,
            ROW,
            1,
            Instant.EPOCH,
            new RecordSqlStatement.Subject("source", "tenant", "actor"));
    var prepared =
        RecordDerivedCopyWrite.capture(
            binding,
            parent,
            binding.installed(),
            "tenant",
            Map.of(
                "source",
                List.of(new RecordView(ROW, 0, Map.of("address", "Old", "total", "old")))),
            after,
            List.of(statement),
            plan -> ledger(old),
            Map.of(List.of("source", ROW), copied));
    assertThat(prepared.mutations()).hasSize(1);
    assertThat(prepared.origins())
        .singleElement()
        .satisfies(
            proof -> {
              assertThat(proof.sourceVersion()).isEqualTo(1);
              assertThat(proof.origins()).containsExactly(copied.origins().getFirst());
              assertThat(proof.derivedOrigins())
                  .singleElement()
                  .satisfies(
                      derived -> {
                        assertThat(derived.destinationFieldId()).isEqualTo("total");
                        assertThat(derived.requirements())
                            .containsExactlyElementsOf(copied.origins().getFirst().requirements());
                        assertThat(derived.requirements())
                            .doesNotContainAnyElementsOf(old.requirements());
                      });
            });
  }

  @Test
  void proofCannotChangeWithoutWritingItsExactDestinationValue() {
    var previous =
        List.of(
            new RecordReferenceCopySnapshot(
                null, ROW, 1, List.of(RecordReferenceCopyStoreTest.origin())));
    assertThatThrownBy(
            () ->
                RecordReferenceCopiedInputs.merge(
                    binding(false),
                    values("Other", "old"),
                    previous,
                    Map.of(List.of("source", ROW), copied())))
        .isInstanceOf(DnaRuntimeException.class);
    var onlyProof = new RecordReferenceCopyEffects.Applied(Map.of(), copied().origins());
    assertThatThrownBy(
            () ->
                RecordReferenceCopiedInputs.merge(
                    binding(false),
                    values("Old", "old"),
                    previous,
                    Map.of(List.of("source", ROW), onlyProof)))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void clearingOrReplacingAnOldFormulaOutputRemovesOnlyItsReplacedProof() {
    var previous =
        List.of(
            new RecordReferenceCopySnapshot(
                null, ROW, 1, List.of(), List.of(derived("address"), derived("total"))));
    var clear =
        new RecordReferenceCopyEffects.Applied(
            Map.of("address", LogicalValue.nullValue()), List.of());
    var result =
        RecordReferenceCopiedInputs.merge(
            binding(false), values(null, "old"), previous, Map.of(List.of("source", ROW), clear));
    assertThat(result)
        .singleElement()
        .satisfies(
            proof -> {
              assertThat(proof.origins()).isEmpty();
              assertThat(proof.derivedOrigins()).containsExactly(derived("total"));
            });
    var replacement =
        RecordReferenceCopiedInputs.merge(
            binding(false),
            values("New", "old"),
            previous,
            Map.of(List.of("source", ROW), copied()));
    assertThat(replacement)
        .singleElement()
        .satisfies(
            proof -> {
              assertThat(proof.origins()).containsExactly(copied().origins().getFirst());
              assertThat(proof.derivedOrigins()).containsExactly(derived("total"));
            });
  }

  @Test
  void foreignRowsAndStaleProspectiveVersionsCannotBorrowAnotherRowsProof() {
    var previous =
        List.of(
            new RecordReferenceCopySnapshot(
                null, ROW, 2, List.of(RecordReferenceCopyStoreTest.origin())));
    assertThatThrownBy(
            () ->
                RecordReferenceCopiedInputs.merge(
                    binding(false),
                    values("New", "old"),
                    previous,
                    Map.of(List.of("source", ROW), copied())))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                RecordReferenceCopiedInputs.merge(
                    binding(false),
                    values("New", "old"),
                    List.of(),
                    Map.of(List.of("source", TARGET), copied())))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void absentInstalledProtectionCannotAcceptTrustedMappingEffects() {
    assertThatThrownBy(
            () ->
                RecordDerivedCopyWrite.capture(
                    null,
                    parent,
                    null,
                    "tenant",
                    Map.of(),
                    values("New", "new"),
                    List.of(),
                    query -> {
                      throw new AssertionError("Unexpected read");
                    },
                    Map.of(List.of("source", ROW), copied())))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void changedOrAbsentBindingRejectsBeforeReadingTheSourceLedger() {
    var installed = binding(false).installed();
    assertThatThrownBy(
            () ->
                RecordDerivedCopyWrite.capture(
                    null,
                    parent,
                    installed,
                    "tenant",
                    Map.of(),
                    values("New", "new"),
                    List.of(),
                    query -> {
                      throw new AssertionError("Unexpected read");
                    },
                    Map.of(List.of("source", ROW), copied())))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                RecordDerivedCopyWrite.capture(
                    binding(true),
                    parent,
                    installed,
                    "tenant",
                    Map.of(),
                    values("New", "new"),
                    List.of(),
                    query -> {
                      throw new AssertionError("Unexpected read");
                    },
                    Map.of(List.of("source", ROW), copied())))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private RecordCalculationWriteSupport.Binding binding(boolean formula) {
    var definition =
        new RecordCalculationDefinition(
            "1", formula ? Map.of("total", "LOWER(parent.address)") : Map.of(), Map.of());
    var installed =
        new RecordCalculationStore.Installed(
            2,
            new RecordCalculationPolicy(
                1,
                "app",
                "form",
                new RecordSchemaReference(1, "a".repeat(64)),
                definition,
                Map.of("source", Set.of("address", "total"))));
    return new RecordCalculationWriteSupport.Binding(
        installed,
        parent,
        Map.of(),
        formula ? Map.of("parent.total", Set.of("parent.address")) : Map.of(),
        null);
  }

  private static RecordReferenceCopyEffects.Applied copied() {
    var old = RecordReferenceCopyStoreTest.origin();
    var requirements =
        List.of(
            new RecordReferenceReadRequirement(
                old.targetModelId(),
                old.targetAppId(),
                old.targetFormId(),
                old.targetResourceCode(),
                TARGET,
                null,
                null,
                List.of(old.targetFieldId())));
    var origin =
        new RecordReferenceCopyOrigin(
            old.relationId(),
            old.mappingId(),
            old.mode(),
            old.destinationFieldId(),
            "b".repeat(64),
            old.targetModelId(),
            old.targetAppId(),
            old.targetFormId(),
            old.targetResourceCode(),
            TARGET,
            4,
            old.targetFieldId(),
            requirements);
    return new RecordReferenceCopyEffects.Applied(
        Map.of(
            "address",
            new LogicalValue(
                org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind.STRING, "New")),
        List.of(origin));
  }

  private static RecordDerivedCopyOrigin derived(String field) {
    return new RecordDerivedCopyOrigin(
        1,
        field,
        1,
        "a".repeat(64),
        List.of(new RecordAggregateReadRequirement("source", ROW, List.of("address"))),
        RecordReferenceCopyStoreTest.origin().requirements());
  }

  private static OwnedRecordView values(String address, String total) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("address", address);
    values.put("total", total);
    return new OwnedRecordView(new RecordView(ROW, 1, values), Map.of());
  }

  private static CalciteQueryResult ledger(RecordReferenceCopyOrigin origin) {
    var key = new RecordReferenceCopyStore.Key("tenant", "source", ROW);
    var statement =
        new RecordReferenceCopyStore().write(key, "actor", null, 0, List.of(origin), Instant.EPOCH);
    String json =
        statement.parameters().stream()
            .filter(value -> value instanceof String text && text.startsWith("{"))
            .map(String.class::cast)
            .findFirst()
            .orElseThrow();
    return new CalciteQueryResult(
        List.of(),
        List.of(List.of(key.id(), "tenant", "source", ROW, 0L, json)),
        false,
        1,
        0,
        null);
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
                    100,
                    null,
                    null,
                    null),
                new LogicalFieldDefinition(
                    "total",
                    "total",
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
}
