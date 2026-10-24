package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory;
import org.simplepoint.plugin.rbac.tenant.api.vo.AuditIdentityLabels;
import org.simplepoint.plugin.rbac.tenant.api.vo.AuditIdentityRequest;

class FormRecordIdentityLabelsTest {
  private final AuditIdentityDirectory directory = mock(AuditIdentityDirectory.class);
  private final FormRecordIdentityLabels labels = new FormRecordIdentityLabels(directory);

  @Test
  void batchesOnlyAuthorizedVisibleIdsAndKeepsStorageValuesUnchanged() {
    when(directory.resolve(any())).thenReturn(new AuditIdentityLabels("tenant", "工作空间", Map.of("actor", "员工・日本語😀"), Map.of("dept", "研发部")));
    var record = record(Map.of("createdBy", "actor", "updatedBy", "***", "tenantId", "tenant", "createOrgDeptId", "dept"));
    var result = labels.decorate("tenant", List.of(record, record));
    var request = ArgumentCaptor.forClass(AuditIdentityRequest.class);
    verify(directory).resolve(request.capture());
    assertThat(request.getValue().userIds()).containsExactly("actor");
    assertThat(request.getValue().departmentIds()).containsExactly("dept");
    assertThat(request.getValue().includeTenant()).isTrue();
    assertThat(result.getFirst().systemValues()).isEqualTo(record.systemValues());
    assertThat(result.getFirst().values()).isEqualTo(record.values());
    assertThat(result.getFirst().systemLabels()).doesNotContainKey("updatedBy");
    assertThat(result.getFirst().systemLabels().get("createdBy").label()).isEqualTo("员工・日本語😀");
    assertThat(record.systemLabels()).isEmpty();
  }

  @Test
  void maskedAbsentAndNullIdentitiesNeverTriggerDirectoryAccess() {
    var values = new java.util.LinkedHashMap<String,Object>();
    values.put("tenantId", "***");values.put("createdBy", "***");values.put("createOrgDeptId", null);
    assertThat(labels.decorate("tenant", record(values)).systemLabels()).isEmpty();
    assertThat(labels.decorate("tenant", List.of())).isEmpty();
    verifyNoInteractions(directory);
  }

  @Test
  void pagesUseBoundedBatchesWithTenantNameRequestedOnlyOnce() {
    when(directory.resolve(any())).thenAnswer(call -> {
      AuditIdentityRequest request = call.getArgument(0);
      return new AuditIdentityLabels(request.tenantId(), request.includeTenant() ? "工作空间" : null, Map.of(), Map.of());
    });
    var records = IntStream.range(0, 130).mapToObj(i -> record(Map.of("tenantId", "tenant", "createdBy", "u"+i,
        "updatedBy", "u"+i, "createOrgDeptId", "d"+i))).toList();
    var result = labels.decorate("tenant", records);
    var request = ArgumentCaptor.forClass(AuditIdentityRequest.class);
    verify(directory, times(3)).resolve(request.capture());
    assertThat(request.getAllValues()).allSatisfy(value -> assertThat(value.userIds().size()+value.departmentIds().size()).isLessThanOrEqualTo(100));
    assertThat(request.getAllValues().stream().filter(AuditIdentityRequest::includeTenant)).hasSize(1);
    assertThat(result).allSatisfy(value -> assertThat(value.systemLabels().get("tenantId").label()).isEqualTo("工作空间"));
  }

  @Test
  void foreignOrFailedResponsesCannotSupplyNamesOrBreakAuthorizedReads() {
    var record = record(Map.of("createdBy", "actor", "tenantId", "tenant"));
    when(directory.resolve(any())).thenReturn(new AuditIdentityLabels("other", "Foreign", Map.of("actor", "Foreign person"), Map.of()));
    var foreign = labels.decorate("tenant", record);
    assertThat(foreign.systemLabels().values()).allSatisfy(value -> assertThat(value.label()).isNull());
    when(directory.resolve(any())).thenThrow(new IllegalStateException("directory unavailable"));
    var failed = labels.decorate("tenant", record);
    assertThat(failed.systemValues()).isEqualTo(record.systemValues());
    assertThat(failed.systemLabels().get("createdBy").id()).isEqualTo("actor");
    assertThat(failed.systemLabels().get("createdBy").label()).isNull();
  }

  @Test
  void oldSnapshotSerializationRemainsUnchangedAndResponseLabelsStaySeparate() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var record = record(Map.of("createdBy", "actor"));
    var original = mapper.writeValueAsString(record);
    assertThat(original).doesNotContain("systemLabels");
    assertThat(mapper.readValue(original, ApplicationFormRecordView.class)).isEqualTo(record);
    when(directory.resolve(any())).thenReturn(new AuditIdentityLabels("tenant", null, Map.of("actor", "员工"), Map.of()));
    var decorated = labels.decorate("tenant", record);
    assertThat(mapper.readValue(mapper.writeValueAsString(decorated), ApplicationFormRecordView.class)).isEqualTo(decorated);
    assertThat(mapper.writeValueAsString(record)).isEqualTo(original);
    when(directory.resolve(any())).thenReturn(new AuditIdentityLabels("tenant", null, Map.of(), Map.of()));
    mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    var unavailable = labels.decorate("tenant", record);
    var json = mapper.writeValueAsString(unavailable);
    assertThat(mapper.readTree(json).path("systemLabels").path("createdBy").has("label")).isTrue();
    assertThat(mapper.readTree(json).path("systemLabels").path("createdBy").path("label").isNull()).isTrue();
    assertThat(mapper.readValue(json, ApplicationFormRecordView.class)).isEqualTo(unavailable);
  }

  private static ApplicationFormRecordView record(Map<String,Object> system) {
    return new ApplicationFormRecordView("record", 1, FormRecordState.SUBMITTED, Map.of("amount", "10.01"), system);
  }
}
