package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;

/** Frozen business records are revised by creating a linked record, never by weakening DNA policy. */
final class FormResubmissionMode {
  private FormResubmissionMode() {}

  static boolean requiresCopy(ApplicationFormDocument document) {
    return java.util.stream.Stream.concat(document.fields().stream(),
        document.details().stream().flatMap(detail -> detail.fields().stream()))
        .anyMatch(field -> field.effectiveWritePolicy() != FormFieldWritePolicy.EDITABLE);
  }
}
