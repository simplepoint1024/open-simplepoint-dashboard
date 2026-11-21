package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceSnapshot;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class RecordReferenceLabelOriginTest {
  private static final String SOURCE = "11111111-1111-1111-1111-111111111111";
  private static final String TARGET = "22222222-2222-2222-2222-222222222222";
  private static final RuntimeActor VIEWER = new RuntimeActor("tenant", "approver");
  private final RecordReferenceSnapshot label =
      new RecordReferenceSnapshot(
          "customer",
          "source",
          SOURCE,
          "customer",
          "target",
          "app",
          "customers",
          "customers",
          TARGET,
          3,
          "name",
          "历史-日本語😀");
  private final OwnedRecordView values =
      new OwnedRecordView(
          new RecordView(SOURCE, 1, Map.of("customer", TARGET, "note", "保留")),
          Map.of(),
          List.of(label));
  private final RecordReferenceReadRequirement direct = requirement("target", "customers", "name");
  private final RecordReferenceReadRequirement original =
      requirement("secret", "payroll", "salary");
  private final RecordReferenceLabelOrigin proof =
      new RecordReferenceLabelOrigin("customer", "source", SOURCE, 3, List.of(direct, original));

  @Test
  void originalDependencyOrderDoesNotChangeImmutableProof() {
    assertThat(
            new RecordReferenceLabelOrigin(
                "customer", "source", SOURCE, 3, List.of(original, direct)))
        .isEqualTo(proof);
  }

  @Test
  void currentViewerMustRetainOriginalDependencyEvenWhenTargetFormulaWasRemoved() {
    var authorization = mock(RecordReferenceCopyAuthorization.class);
    var deadline = new RecordExecutionDeadline(10000);
    when(authorization.authorizeRequirements(any(), any(), any())).thenReturn(Set.of(direct));
    var denied =
        RecordSnapshotRead.authorizeLabels(values, List.of(proof), VIEWER, authorization, deadline);
    assertThat(denied.referenceSnapshots()).isEmpty();
    assertThat(denied.parent()).isEqualTo(values.parent());
    verify(authorization).authorizeRequirements(VIEWER, Set.of(direct, original), deadline);
    when(authorization.authorizeRequirements(any(), any(), any()))
        .thenReturn(Set.of(direct, original));
    assertThat(
            RecordSnapshotRead.authorizeLabels(
                    values, List.of(proof), VIEWER, authorization, deadline)
                .referenceSnapshots())
        .containsExactly(label);
  }

  @Test
  void legacyMissingProofHidesOnlyLabelsAndDoesNotTryToReconstructHistoricalInputs() {
    var authorization = mock(RecordReferenceCopyAuthorization.class);
    var result =
        RecordSnapshotRead.authorizeLabels(
            values, List.of(), VIEWER, authorization, new RecordExecutionDeadline(10000));
    assertThat(result.referenceSnapshots()).isEmpty();
    assertThat(result.parent()).isEqualTo(values.parent());
    verifyNoInteractions(authorization);
  }

  @Test
  void newEnvelopeRoundTripsAndLegacyEnvelopeDoesNotGainOptionalFields() throws Exception {
    var mapper = new ObjectMapper();
    var legacy = RecordStoredSnapshot.capture(values, null);
    assertThat(mapper.writeValueAsString(legacy)).doesNotContain("labelOrigins", "copyOrigins");
    var stored = legacy.withLabelOrigins(List.of(proof));
    assertThat(stored.contractVersion()).isEqualTo(5);
    assertThat(mapper.readValue(mapper.writeValueAsBytes(stored), RecordStoredSnapshot.class))
        .isEqualTo(stored);
    assertThat(mapper.readValue(mapper.writeValueAsBytes(legacy), RecordStoredSnapshot.class))
        .isEqualTo(legacy);
  }

  @Test
  void missingRepeatedForeignOrWrongVersionEvidenceCannotEnterSnapshot() {
    assertThatThrownBy(() -> new RecordStoredSnapshot(5, 0, null, values, null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RecordStoredSnapshot.capture(values, null).withLabelOrigins(List.of(proof, proof)))
        .isInstanceOf(RuntimeException.class);
    for (var invalid :
        List.of(
            new RecordReferenceLabelOrigin("customer", "source", SOURCE, 4, List.of(direct)),
            new RecordReferenceLabelOrigin("customer", "source", SOURCE, 3, List.of(original)),
            new RecordReferenceLabelOrigin("other", "source", SOURCE, 3, List.of(direct)))) {
      assertThatThrownBy(
              () -> RecordStoredSnapshot.capture(values, null).withLabelOrigins(List.of(invalid)))
          .isInstanceOf(RuntimeException.class);
    }
    assertThatThrownBy(() -> new RecordStoredSnapshot(4, 0, null, values, null, List.of(proof)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RecordReferenceReadRequirement requirement(
      String model, String resource, String field) {
    return new RecordReferenceReadRequirement(
        model,
        "app",
        model.equals("target") ? "customers" : "payroll",
        resource,
        TARGET,
        null,
        null,
        List.of(field));
  }
}
