package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.*;
import org.simplepoint.plugin.storage.api.model.ObjectStorageSourceMetadata;
import org.simplepoint.plugin.storage.api.service.ObjectStorageSourceService;

class FormAttachmentReferenceValidatorTest {
  @Test
  void checksTenantSourceUploaderAndAuthoritativeMetadata() {
    var storage = mock(ObjectStorageSourceService.class);
    var actors = mock(FormRequestContext.class);
    when(actors.requireActor()).thenReturn(new FormRequestContext.FormActor("tenant", "employee"));
    var validator = new FormAttachmentReferenceValidator(storage, actors);
    var field = new FormFieldDefinition("files", "files", "凭证", "凭证", FormFieldType.JSON,
        FormStorageMode.ATTACHMENT, true, false, null, null, null, null, List.of());
    var values = Map.<String, Object>of("files", "[{\"id\":\"file-1\",\"name\":\"发票.pdf\",\"size\":128}]");
    when(storage.inspectSource("file-1", "tenant", "simplepoint-app-form"))
        .thenReturn(new ObjectStorageSourceMetadata("file-1", "发票.pdf", 128, "employee"));
    validator.validate(List.of(field), Map.of(), values);
    when(storage.inspectSource("file-1", "tenant", "simplepoint-app-form"))
        .thenReturn(new ObjectStorageSourceMetadata("file-1", "发票.pdf", 128, "other"));
    assertThatThrownBy(() -> validator.validate(List.of(field), Map.of(), values))
        .isInstanceOf(ApplicationFormException.class);
    // Retaining an already authorized immutable reference does not transfer ownership.
    validator.validate(List.of(field), values, values);
    when(storage.inspectSource("file-1", "tenant", "simplepoint-app-form"))
        .thenReturn(new ObjectStorageSourceMetadata("file-1", "发票.pdf", 999, "employee"));
    assertThatThrownBy(() -> validator.validate(List.of(field), values, values))
        .isInstanceOf(ApplicationFormException.class);
  }
}
