package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;

class RecordReferenceCopyBudgetTest {
  @Test
  void acceptedOriginGroupsFitTheReaderAndEveryDestinationHasDurableQueryProtection() {
    var origin = RecordReferenceCopyStoreTest.origin();
    List<RecordReferenceReadRequirement> requirements = new ArrayList<>();
    var source = origin.requirements().getFirst();
    requirements.add(source);
    for (int index = 0; index < 127; index++) {
      requirements.add(
          new RecordReferenceReadRequirement(
              source.modelId(),
              source.appId(),
              source.formId(),
              "resource-" + index,
              source.recordId(),
              null,
              null,
              source.fieldIds()));
    }
    var snapshot = snapshot(origin, requirements);
    RecordReferenceCopyBudget.validate(
        "source", List.of(snapshot), Map.of("source", Set.of("address")));
    assertThatThrownBy(
            () -> RecordReferenceCopyBudget.validate("source", List.of(snapshot), Map.of()))
        .isInstanceOf(DnaRuntimeException.class);
    requirements.add(
        new RecordReferenceReadRequirement(
            source.modelId(),
            source.appId(),
            source.formId(),
            "resource-128",
            source.recordId(),
            null,
            null,
            source.fieldIds()));
    var oversized = snapshot(origin, requirements);
    assertThatThrownBy(
            () ->
                RecordReferenceCopyBudget.validate(
                    "source", List.of(oversized), Map.of("source", Set.of("address"))))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void retainedOwnedDependenciesCannotExceedTheTargetReadersModelBudget() {
    var origin = RecordReferenceCopyStoreTest.origin();
    var source = origin.requirements().getFirst();
    List<RecordReferenceReadRequirement> requirements = new ArrayList<>();
    requirements.add(source);
    for (int index = 0; index < 6; index++) {
      requirements.add(
          new RecordReferenceReadRequirement(
              source.modelId(),
              source.appId(),
              source.formId(),
              source.resourceCode(),
              source.recordId(),
              "detail-" + index,
              null,
              source.fieldIds()));
    }
    var oversized = snapshot(origin, requirements);
    assertThatThrownBy(
            () ->
                RecordReferenceCopyBudget.validate(
                    "source", List.of(oversized), Map.of("source", Set.of("address"))))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private static RecordReferenceCopySnapshot snapshot(
      RecordReferenceCopyOrigin source, List<RecordReferenceReadRequirement> requirements) {
    return new RecordReferenceCopySnapshot(
        null,
        RecordPublicationPatchFixture.PARENT,
        1,
        List.of(
            new RecordReferenceCopyOrigin(
                source.relationId(),
                source.mappingId(),
                source.mode(),
                source.destinationFieldId(),
                source.mappingRevision(),
                source.targetModelId(),
                source.targetAppId(),
                source.targetFormId(),
                source.targetResourceCode(),
                source.targetRecordId(),
                source.targetVersion(),
                source.targetFieldId(),
                requirements)));
  }
}
