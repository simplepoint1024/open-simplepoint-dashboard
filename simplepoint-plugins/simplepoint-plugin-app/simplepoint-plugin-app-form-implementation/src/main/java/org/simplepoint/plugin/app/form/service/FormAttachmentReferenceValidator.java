package org.simplepoint.plugin.app.form.service;

import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.storage.api.service.ObjectStorageSourceService;
import org.springframework.stereotype.Component;

/** Checks authoritative file metadata through the storage contract, never through its persistence tables. */
@Component
public class FormAttachmentReferenceValidator {
  private final ObjectStorageSourceService storage;
  private final FormRequestContext actors;

  /** Creates the reference validator over the routed storage contract. */
  public FormAttachmentReferenceValidator(ObjectStorageSourceService storage, FormRequestContext actors) {
    this.storage = storage;
    this.actors = actors;
  }

  /** New references must be uploaded by the actor; existing references retain their original identity. */
  public void validate(List<FormFieldDefinition> fields, Map<String, Object> existing, Map<String, Object> requested) {
    for (var field : fields) {
      if (field.storageMode() != FormStorageMode.ATTACHMENT || !requested.containsKey(field.fieldId())) {
        continue;
      }
      try {
        var old = FormAttachmentValues.parse(existing.get(field.fieldId()));
        for (var file : FormAttachmentValues.parse(requested.get(field.fieldId()))) {
          var actor = actors.requireActor();
          var metadata = storage.inspectSource(file.id(), actor.tenantId(), "simplepoint-app-form");
          if (!file.name().equals(metadata.fileName()) || file.size() != metadata.contentLength()
              || !old.contains(file) && !actor.actorId().equals(metadata.createdBy())) {
            throw new IllegalArgumentException("Attachment reference is not owned by actor");
          }
        }
      } catch (RuntimeException failure) {
        throw new ApplicationFormException(ApplicationFormErrorCode.RECORD_INVALID,
            "附件不可用或与上传信息不一致，请重新上传：" + field.label(), failure);
      }
    }
  }
}
