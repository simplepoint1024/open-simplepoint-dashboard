package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField;

class FormRecordSystemFieldsTest {
  @Test
  void usesPlatformNamesWithoutMixingBusinessFieldsIntoMetadata() {
    var projected = FormRecordSystemFields.fromStorage(Map.of(
        "tenant_id", "tenant", "created_org_dept_id", "department", "created_by", "actor",
        "created_at", "2026-10-15T00:00:00Z", "business", "private"));
    assertThat(projected).containsOnlyKeys("tenantId", "createOrgDeptId", "createdBy", "createdAt")
        .containsEntry("createOrgDeptId", "department");
  }

  @Test
  void frozenMetadataReappliesCurrentFieldPolicyWithoutInventingUncapturedValues() {
    var captured = Map.<String, Object>of("createdBy", "actor", "createOrgDeptId", "dept", "createdAt", "instant");
    var projected = FormRecordSystemFields.project(captured, "model", Map.of(
        "dna.runtime.record.model#created_by", "HIDDEN",
        "dna.runtime.record.model#created_org_dept_id", "MASKED",
        "dna.runtime.record.model#created_at", "VISIBLE"));
    assertThat(projected).containsExactlyInAnyOrderEntriesOf(Map.of("createOrgDeptId", "***", "createdAt", "instant"));
    assertThat(FormRecordSystemFields.project(Map.of(), "model", Map.of())).isEmpty();
    assertThat(captured.get("createdBy")).isEqualTo("actor");
  }

  @Test
  void publishedBaseFieldsAreDiscoverableButAlwaysReadOnly() {
    var descriptor = FormPermissionDescriptorService.model("model", List.of(), "明细 / ");
    assertThat(descriptor.model()).isEqualTo("dna.runtime.record.model");
    assertThat(descriptor.owner()).isEqualTo("created_by");
    assertThat(descriptor.department()).isEqualTo("created_org_dept_id");
    assertThat(descriptor.properties().keySet()).containsExactlyInAnyOrderElementsOf(RecordSystemField.storageCodes());
    assertThat(descriptor.properties().values()).allSatisfy(field -> {
      assertThat(field.readOnly()).isTrue(); assertThat(field.operators()).isNotEmpty();
    });
    assertThat(descriptor.properties()).doesNotContainKeys("id", "record_version");
  }
}
