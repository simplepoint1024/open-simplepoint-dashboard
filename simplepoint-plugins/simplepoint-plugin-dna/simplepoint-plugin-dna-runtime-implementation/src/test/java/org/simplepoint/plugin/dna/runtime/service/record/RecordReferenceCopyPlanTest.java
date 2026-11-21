package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceConversion;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping.Mode;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping.NullPolicy;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingObservation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.service.record.RecordReferenceCopyPlan.BoundField;

class RecordReferenceCopyPlanTest {
  private static final String REVISION = "a".repeat(64);
  private static final String RECORD = "11111111-1111-1111-1111-111111111111";
  private static final String OTHER = "22222222-2222-2222-2222-222222222222";

  @Test
  void managedCopiesAndEditableSuggestionsNeverShareWriteChannels() {
    var plan =
        new RecordReferenceCopyPlan(
            REVISION,
            List.of(
                bound("copy", Mode.SERVER_SNAPSHOT, NullPolicy.CLEAR),
                bound("suggestion", Mode.EDITABLE_DEFAULT, NullPolicy.CLEAR)));
    var prepared =
        plan.prepare(
            RECORD,
            row(4, "客户・日本語😀"),
            confirmation(4),
            Set.of("target_code"),
            Set.of(),
            Set.of("copy_code", "suggestion_code"),
            Set.of("map-suggestion"));
    assertThat(prepared.managed()).containsOnlyKeys("copy");
    assertThat(prepared.suggestions()).containsOnlyKeys("suggestion");
    assertThat(prepared.managed().get("copy").canonicalValue()).isEqualTo("客户・日本語😀");
    assertThat(prepared.observed()).isEqualTo(confirmation(4));
    assertThatThrownBy(() -> prepared.managed().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void stalePreviewCannotSubstituteNewerValuesAndPostLockRecheckDetectsConcurrentChanges() {
    var plan = plan(Mode.SERVER_SNAPSHOT, NullPolicy.CLEAR);
    conflict(() -> prepare(plan, row(5, "new-price"), confirmation(4)));
    conflict(
        () ->
            prepare(
                plan,
                row(4, "price"),
                new RecordReferenceMappingObservation("b".repeat(64), RECORD, 4L)));
    conflict(
        () ->
            prepare(
                plan, new RecordView(OTHER, 4, Map.of("target_code", "other")), confirmation(4)));
    var prepared = prepare(plan, row(4, "price"), confirmation(4));
    RecordReferenceCopyPlan.verify(row(4, "price"), prepared.observed());
    conflict(() -> RecordReferenceCopyPlan.verify(row(5, "changed"), prepared.observed()));
    conflict(() -> RecordReferenceCopyPlan.verify(null, prepared.observed()));
    conflict(
        () ->
            plan.prepare(
                OTHER,
                row(4, "price"),
                confirmation(4),
                Set.of("target_code"),
                Set.of(),
                Set.of("copy_code"),
                Set.of()));
  }

  @Test
  void nullNeverBypassesEitherSideFieldPolicyAndOmittedProjectionIsNotStoredNull() {
    var plan = plan(Mode.SERVER_SNAPSHOT, NullPolicy.CLEAR);
    for (Object value : new Object[] {null, "secret"}) {
      conflict(
          () ->
              plan.prepare(
                  RECORD,
                  row(1, value),
                  confirmation(1),
                  Set.of(),
                  Set.of(),
                  Set.of("copy_code"),
                  Set.of()));
      conflict(
          () ->
              plan.prepare(
                  RECORD,
                  row(1, value),
                  confirmation(1),
                  Set.of("target_code"),
                  Set.of("target_code"),
                  Set.of("copy_code"),
                  Set.of()));
      conflict(
          () ->
              plan.prepare(
                  RECORD,
                  row(1, value),
                  confirmation(1),
                  Set.of("target_code"),
                  Set.of(),
                  Set.of(),
                  Set.of()));
    }
    assertThatThrownBy(() -> prepare(plan, new RecordView(RECORD, 1, Map.of()), confirmation(1)))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE));
  }

  @Test
  void nullableClearAndPreserveHaveDifferentEffectsWithoutInventingProvenance() {
    var managed =
        prepare(plan(Mode.SERVER_SNAPSHOT, NullPolicy.CLEAR), row(1, null), confirmation(1));
    assertThat(managed.managed()).containsEntry("copy", LogicalValue.nullValue());
    var preserved =
        plan(Mode.EDITABLE_DEFAULT, NullPolicy.PRESERVE)
            .prepare(
                RECORD,
                row(1, null),
                confirmation(1),
                Set.of("target_code"),
                Set.of(),
                Set.of("copy_code"),
                Set.of("map-copy"));
    assertThat(preserved.managed()).isEmpty();
    assertThat(preserved.suggestions()).isEmpty();
    conflict(
        () ->
            prepare(plan(Mode.SERVER_SNAPSHOT, NullPolicy.REJECT), row(1, null), confirmation(1)));
    assertThatThrownBy(() -> bound("copy", Mode.SERVER_SNAPSHOT, NullPolicy.PRESERVE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void clearingTheRelationCanClearNullableDestinationsEvenIfItsFormerTargetFieldWasRequired() {
    var ordinary = bound("copy", Mode.SERVER_SNAPSHOT, NullPolicy.CLEAR);
    var requiredTarget =
        new BoundField(
            ordinary.mapping(), field("target", "target_code", false), ordinary.destination());
    var plan = new RecordReferenceCopyPlan(REVISION, List.of(requiredTarget));
    var cleared =
        plan.prepare(
            null,
            null,
            new RecordReferenceMappingObservation(REVISION, null, null),
            Set.of(),
            Set.of(),
            Set.of("copy_code"),
            Set.of());
    assertThat(cleared.managed()).containsEntry("copy", LogicalValue.nullValue());
    var requiredDestination =
        new RecordReferenceCopyPlan(
            REVISION,
            List.of(
                new BoundField(
                    ordinary.mapping(), ordinary.target(), field("copy", "copy_code", false))));
    conflict(
        () ->
            requiredDestination.prepare(
                null,
                null,
                new RecordReferenceMappingObservation(REVISION, null, null),
                Set.of("target_code"),
                Set.of(),
                Set.of("copy_code"),
                Set.of()));
  }

  @Test
  void retryBuildsNewImmutableResultsAndInputProjectionIsNeverModified() {
    var values = new LinkedHashMap<String, Object>();
    values.put("target_code", "old");
    var target = new RecordView(RECORD, 1, values);
    var plan = plan(Mode.SERVER_SNAPSHOT, NullPolicy.CLEAR);
    var first = prepare(plan, target, confirmation(1));
    values.put("target_code", "modified-caller-map");
    var replay = prepare(plan, target, confirmation(1));
    assertThat(first).isEqualTo(replay);
    assertThat(first.managed().get("copy").canonicalValue()).isEqualTo("old");
    assertThat(target.values()).containsOnlyKeys("target_code");
  }

  @Test
  void boundsAndStableIdentityChecksRejectMalformedTrustedMetadata() {
    var bound = bound("copy", Mode.SERVER_SNAPSHOT, NullPolicy.CLEAR);
    assertThatThrownBy(() -> new RecordReferenceCopyPlan(REVISION, List.of(bound, bound)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                new BoundField(
                    bound.mapping(), field("wrong", "target_code", true), bound.destination()))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                new RecordReferenceCopyPlan(
                    REVISION,
                    IntStream.range(0, 33)
                        .mapToObj(i -> bound("copy" + i, Mode.SERVER_SNAPSHOT, NullPolicy.CLEAR))
                        .toList()))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> new RecordReferenceMappingObservation(REVISION, RECORD, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordReferenceMappingObservation(REVISION, RECORD, -1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordReferenceMappingObservation("raw-expression", RECORD, 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RecordReferenceCopyPlan plan(Mode mode, NullPolicy nullPolicy) {
    return new RecordReferenceCopyPlan(REVISION, List.of(bound("copy", mode, nullPolicy)));
  }

  private static BoundField bound(String destination, Mode mode, NullPolicy policy) {
    return new BoundField(
        new RecordReferenceMapping(
            "map-" + destination,
            "target",
            destination,
            mode,
            RecordReferenceConversion.IDENTITY,
            policy),
        field("target", "target_code", true),
        field(destination, destination + "_code", true));
  }

  private static LogicalFieldDefinition field(String id, String code, boolean nullable) {
    return new LogicalFieldDefinition(
        id, code, id, LogicalFieldType.STRING, nullable, 100, null, null, null);
  }

  private static RecordReferenceCopyPlan.Prepared prepare(
      RecordReferenceCopyPlan plan,
      RecordView row,
      RecordReferenceMappingObservation confirmation) {
    return plan.prepare(
        RECORD, row, confirmation, Set.of("target_code"), Set.of(), Set.of("copy_code"), Set.of());
  }

  private static RecordReferenceMappingObservation confirmation(long version) {
    return new RecordReferenceMappingObservation(REVISION, RECORD, version);
  }

  private static RecordView row(long version, Object value) {
    var values = new LinkedHashMap<String, Object>();
    values.put("target_code", value);
    return new RecordView(RECORD, version, values);
  }

  private static void conflict(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT));
  }
}
