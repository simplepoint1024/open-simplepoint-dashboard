package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordSnapshotFieldRequirementTest {
  @Test
  void legacySnapshotShapeIsUnchangedAndNewRequirementsRoundTripImmutably() throws Exception {
    var mapper = new ObjectMapper();
    assertThat(mapper.writeValueAsString(new OwnedRecordSnapshotRequest(List.of()))).isEqualTo("{\"details\":[]}");
    var requirement = new RecordSnapshotFieldRequirement("amount", true, null, null, false, false, false);
    var fields = new ArrayList<>(List.of(requirement));
    var snapshot = new OwnedRecordSnapshotRequest(List.of(), fields);
    fields.clear();
    assertThat(snapshot.requirements()).containsExactly(requirement);
    assertThat(mapper.readValue(mapper.writeValueAsBytes(snapshot), OwnedRecordSnapshotRequest.class)).isEqualTo(snapshot);
    assertThatThrownBy(() -> snapshot.requirements().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsUnboundedOrDuplicateCaptureRequirements() {
    assertThatThrownBy(() -> new RecordSnapshotFieldRequirement("bad.field", true, null, null, false, false, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordSnapshotFieldRequirement("field", false, 16_385, null, false, false, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordSnapshotFieldRequirement("field", false, null, "x".repeat(513), false, false, false))
        .isInstanceOf(IllegalArgumentException.class);
    var requirement = new RecordSnapshotFieldRequirement("field", true, null, null, false, false, false);
    assertThatThrownBy(() -> new OwnedRecordSnapshotRequest(List.of(), List.of(requirement, requirement)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OwnedRecordSnapshotRequest(List.of(), java.util.stream.IntStream.range(0, 101)
        .mapToObj(i -> new RecordSnapshotFieldRequirement("field_" + i, true, null, null, false, false, false)).toList()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
