package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.locale.LocalizedText;
import org.simplepoint.core.permission.PermissionModelDescriptor;
import org.simplepoint.core.permission.PermissionPageDescriptor;
import org.simplepoint.plugin.app.form.api.model.*;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;

class FormPermissionLocalizationTest {
  @Test void authoredTitlesSurvivePermissionRegistrationRoundTripAndCommonFieldProjection() {
    var translation = new LocalizedText("Customer", "en-US", Map.of("ja", "顧客", "zh-CN", "客户"));
    var field = new FormFieldDefinition("stable-id", "storage_column", "Customer", "Customer", FormFieldType.STRING,
        FormStorageMode.STORED, false, false, 120, null, null, null, List.of(), null, null, null, null, translation);
    var model = FormPermissionDescriptorService.model("physical-model", List.of(field), "");
    var registered = PermissionModelDescriptor.fromJson(model.toJson());
    var identity = RecordPermissionModel.field(field.fieldId());
    assertThat(registered.properties().get(identity).titleLocalization()).isEqualTo(translation);
    assertThat(registered.properties()).doesNotContainKey("storage_column");
    var page = PermissionPageDescriptor.fromJson(new PermissionPageDescriptor(List.of(registered)).toJson());
    assertThat(page.commonProperties().get(identity).titleLocalization()).isEqualTo(translation);
    assertThat(page.commonProperties().get(identity).operators()).contains("equals");
  }
}
