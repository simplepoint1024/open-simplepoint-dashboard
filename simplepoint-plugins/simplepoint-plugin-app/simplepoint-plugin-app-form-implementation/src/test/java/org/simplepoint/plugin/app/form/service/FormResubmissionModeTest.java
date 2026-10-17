package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.*;

class FormResubmissionModeTest {
  @Test
  void frozenRecordsUseNewRevisionsWithoutWeakeningWritePolicies() {
    var editable = new ApplicationFormDocument("1", List.of(field(FormFieldWritePolicy.EDITABLE)),
        List.of(), List.of(), List.of());
    var frozen = new ApplicationFormDocument("1", List.of(field(FormFieldWritePolicy.UNTIL_SUBMITTED)),
        List.of(), List.of(), List.of());
    assertThat(FormResubmissionMode.requiresCopy(editable)).isFalse();
    assertThat(FormResubmissionMode.requiresCopy(frozen)).isTrue();
    var childFrozen = new ApplicationFormDocument("1", List.of(), List.of(), List.of(), List.of(),
        List.of(new FormDetailDefinition("lines", "lines", "明细", frozen.fields(), List.of())));
    assertThat(FormResubmissionMode.requiresCopy(childFrozen)).isTrue();
    var copy = new SubmitApplicationFormCommand(new ApplicationFormRuntimeIdentity("form", 1), "operation",
        null, null, "definition", 1, "a".repeat(64), "补充材料", Map.of("name", "修订"), Map.of(), List.of());
    assertThat(copy.recordId()).isNull();
    assertThat(copy.previousSubmissionId()).isNotNull();
  }

  private static FormFieldDefinition field(FormFieldWritePolicy policy) {
    return new FormFieldDefinition("name", "name", "名称", "名称", FormFieldType.STRING,
        FormStorageMode.STORED, true, false, 200, null, null, null, List.of(), policy);
  }
}
