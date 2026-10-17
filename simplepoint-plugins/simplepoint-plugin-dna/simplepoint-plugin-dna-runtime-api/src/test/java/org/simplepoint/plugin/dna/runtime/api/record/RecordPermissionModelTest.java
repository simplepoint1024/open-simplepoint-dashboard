package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordPermissionModelTest {
  @Test
  void stableGrantsApplyAcrossStorageRenamesAndRemainDistinctFromLegacyColumns() {
    String identity = RecordPermissionModel.field("field-name");
    assertThat(identity).matches("field_[a-f0-9]{64}").hasSize(70);
    assertThat(identity).isNotEqualTo(RecordPermissionModel.field("field_name"));
    var policy = Map.of(RecordPermissionModel.key("model") + "#" + identity, "HIDDEN");
    assertThat(RecordPermissionModel.access(policy, "model", "field-name", "old_name")).isEqualTo("HIDDEN");
    assertThat(RecordPermissionModel.access(policy, "model", "field-name", "new_name")).isEqualTo("HIDDEN");
    assertThat(RecordPermissionModel.access(policy, "other-model", "field-name", "new_name")).isNull();
  }

  @Test
  void migrationDoesNotWeakenRestrictiveLegacyGrants() {
    var policy = Map.of("dna.runtime.record.model#name", "HIDDEN",
        "dna.runtime.record.model#" + RecordPermissionModel.field("field-name"), "EDITABLE");
    assertThat(RecordPermissionModel.access(policy, "model", "field-name", "name")).isEqualTo("HIDDEN");
  }
}
