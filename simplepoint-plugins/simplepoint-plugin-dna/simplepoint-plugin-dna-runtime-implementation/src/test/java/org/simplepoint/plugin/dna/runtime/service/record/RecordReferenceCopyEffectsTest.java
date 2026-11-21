package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceConversion;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingObservation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;

class RecordReferenceCopyEffectsTest {
  private static final String RECORD = "11111111-1111-1111-1111-111111111111";
  private static final String OTHER = "22222222-2222-2222-2222-222222222222";
  private static final String REVISION = "a".repeat(64);

  @Test
  void copiedValuesAndProtectedOriginsDescribeTheSameObservedSource() {
    var rule = rule(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    var prepared = prepare(rule, RECORD, "日本語😀", Set.of());
    var applied = apply(List.of(), rule, prepared);
    assertThat(applied.values().get("destination").canonicalValue()).isEqualTo("日本語😀");
    var origin = applied.origins().getFirst();
    assertThat(origin.targetRecordId()).isEqualTo(RECORD);
    assertThat(origin.targetVersion()).isEqualTo(3);
    assertThat(origin.mappingRevision()).isEqualTo(REVISION);
    assertThat(origin.targetFieldId()).isEqualTo("target");
    assertThat(origin.mode()).isEqualTo(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    assertThatThrownBy(() -> applied.values().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void changingSelectionWithoutAcceptingDefaultDoesNotReadOrOverwriteItOrRetagItsOrigin() {
    var rule = rule(RecordReferenceMapping.Mode.EDITABLE_DEFAULT);
    var first = apply(List.of(), rule, prepare(rule, RECORD, "Old", Set.of("copy")));
    // A retained edited default does not require target-field access; only an explicitly applied
    // default may read that field. The ordinary relation owner still checks target-row admission.
    var unselected =
        plan(rule)
            .prepare(
                OTHER,
                new RecordView(OTHER, 3, Map.of()),
                new RecordReferenceMappingObservation(REVISION, OTHER, 3L),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of());
    var retained = apply(first.origins(), rule, unselected);
    assertThat(retained.values()).isEmpty();
    assertThat(retained.origins()).isEqualTo(first.origins());
    assertThat(retained.origins().getFirst().targetRecordId()).isEqualTo(RECORD);
    var applied = apply(retained.origins(), rule, prepare(rule, OTHER, "New", Set.of("copy")));
    assertThat(applied.values().get("destination").canonicalValue()).isEqualTo("New");
    assertThat(applied.origins().getFirst().targetRecordId()).isEqualTo(OTHER);
    assertThat(applied.origins().getFirst().mode())
        .isEqualTo(RecordReferenceMapping.Mode.EDITABLE_DEFAULT);
  }

  @Test
  void clearingManagedValueRemovesOnlyItsCurrentOriginAndNeverMutatesPriorSnapshots() {
    var rule = rule(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    var first = apply(List.of(), rule, prepare(rule, RECORD, "Old", Set.of()));
    var clear =
        plan(rule)
            .prepare(
                null,
                null,
                new RecordReferenceMappingObservation(REVISION, null, null),
                Set.of(),
                Set.of(),
                Set.of("destination_code"),
                Set.of());
    var cleared = apply(first.origins(), rule, clear);
    assertThat(cleared.values()).containsEntry("destination", LogicalValue.nullValue());
    assertThat(cleared.origins()).isEmpty();
    assertThat(first.origins()).hasSize(1);
    assertThat(first.values().get("destination").canonicalValue()).isEqualTo("Old");
  }

  @Test
  void preservationAndUnselectedDefaultsCannotInventNewProvenance() {
    var original = rule(RecordReferenceMapping.Mode.EDITABLE_DEFAULT);
    var first = apply(List.of(), original, prepare(original, RECORD, "Old", Set.of("copy")));
    var preserve =
        new RecordReferenceMapping(
            "copy",
            "target",
            "destination",
            RecordReferenceMapping.Mode.EDITABLE_DEFAULT,
            RecordReferenceConversion.IDENTITY,
            RecordReferenceMapping.NullPolicy.PRESERVE);
    var rule =
        new RecordReferenceRule(
            "1",
            "customer",
            new RecordValidationField("parent", "reference"),
            "customers",
            "app",
            "customer-form",
            "customers-page",
            null,
            null,
            null,
            List.of(preserve));
    var prepared =
        plan(rule)
            .prepare(
                OTHER,
                new RecordView(OTHER, 3, java.util.Collections.singletonMap("target_code", null)),
                new RecordReferenceMappingObservation(REVISION, OTHER, 3L),
                Set.of("target_code"),
                Set.of(),
                Set.of("destination_code"),
                Set.of("copy"));
    var retained = apply(first.origins(), rule, prepared);
    assertThat(retained.values()).isEmpty();
    assertThat(retained.origins()).isEqualTo(first.origins());
  }

  @Test
  void unknownDefaultsAndCrossedManagedChannelsFailBeforeAnyEffectsAreProduced() {
    var rule = rule(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    assertThatThrownBy(() -> prepare(rule, RECORD, "Value", Set.of("copy")))
        .isInstanceOf(DnaRuntimeException.class);
    var observation = new RecordReferenceMappingObservation(REVISION, RECORD, 3L);
    assertThatThrownBy(
            () ->
                apply(
                    List.of(),
                    rule,
                    new RecordReferenceCopyPlan.Prepared(
                        observation, Map.of(), Map.of("destination", LogicalValue.nullValue()))))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                apply(
                    List.of(),
                    rule,
                    new RecordReferenceCopyPlan.Prepared(
                        observation, Map.of("foreign", LogicalValue.nullValue()), Map.of())))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private static RecordReferenceCopyEffects.Applied apply(
      List<org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin> previous,
      RecordReferenceRule rule,
      RecordReferenceCopyPlan.Prepared prepared) {
    var requirements =
        prepared.observed().targetRecordId() == null
            ? Map
                .<String,
                    List<
                        org.simplepoint.plugin.dna.runtime.api.record
                            .RecordReferenceReadRequirement>>
                    of()
            : Map.of(
                "target",
                List.of(
                    new org.simplepoint.plugin.dna.runtime.api.record
                        .RecordReferenceReadRequirement(
                        rule.targetModelId(),
                        rule.targetAppId(),
                        rule.targetFormId(),
                        rule.targetResourceCode(),
                        prepared.observed().targetRecordId(),
                        null,
                        null,
                        List.of("target"))));
    return RecordReferenceCopyEffects.apply(previous, rule, prepared, requirements);
  }

  @Test
  void missingFrozenPermissionDependenciesCannotCreateCopiedValues() {
    var rule = rule(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    assertThatThrownBy(
            () ->
                RecordReferenceCopyEffects.apply(
                    List.of(), rule, prepare(rule, RECORD, "Sensitive", Set.of()), Map.of()))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private static RecordReferenceRule rule(RecordReferenceMapping.Mode mode) {
    return new RecordReferenceRule(
        "1",
        "customer",
        new RecordValidationField("parent", "reference"),
        "customers",
        "app",
        "customer-form",
        "customers-page",
        null,
        null,
        null,
        List.of(
            new RecordReferenceMapping(
                "copy",
                "target",
                "destination",
                mode,
                RecordReferenceConversion.IDENTITY,
                RecordReferenceMapping.NullPolicy.CLEAR)));
  }

  private static RecordReferenceCopyPlan plan(RecordReferenceRule rule) {
    return new RecordReferenceCopyPlan(
        REVISION,
        List.of(
            new RecordReferenceCopyPlan.BoundField(
                rule.mappings().getFirst(),
                new LogicalFieldDefinition(
                    "target",
                    "target_code",
                    "Target",
                    LogicalFieldType.STRING,
                    true,
                    100,
                    null,
                    null,
                    null),
                new LogicalFieldDefinition(
                    "destination",
                    "destination_code",
                    "Destination",
                    LogicalFieldType.STRING,
                    true,
                    100,
                    null,
                    null,
                    null))));
  }

  private static RecordReferenceCopyPlan.Prepared prepare(
      RecordReferenceRule rule, String targetId, String value, Set<String> defaults) {
    return plan(rule)
        .prepare(
            targetId,
            new RecordView(targetId, 3, Map.of("target_code", value)),
            new RecordReferenceMappingObservation(REVISION, targetId, 3L),
            Set.of("target_code"),
            Set.of(),
            Set.of("destination_code"),
            defaults);
  }
}
