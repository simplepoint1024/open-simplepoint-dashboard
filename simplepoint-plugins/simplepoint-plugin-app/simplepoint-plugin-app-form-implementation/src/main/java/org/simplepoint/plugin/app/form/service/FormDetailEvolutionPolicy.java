package org.simplepoint.plugin.app.form.service;

import java.util.Objects;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;

/**
 * Prevents implicitly dropping or migrating owned data until child migration review is available.
 */
final class FormDetailEvolutionPolicy {
  private FormDetailEvolutionPolicy() {}

  static void validate(
      ApplicationFormDocument previous,
      ApplicationFormDocument next,
      FormStorageProjectionCompiler compiler) {
    for (var old : previous.details()) {
      var replacement =
          next.details().stream()
              .filter(detail -> detail.detailId().equals(old.detailId()))
              .findFirst();
      if (replacement.isEmpty()
          || !Objects.equals(old.storageCode(), replacement.get().storageCode())
          || !compiler
              .compileDetail(old, "owner")
              .checksum()
              .equals(compiler.compileDetail(replacement.get(), "owner").checksum())
          || !old.fields().equals(replacement.get().fields())) {
        throw new ApplicationFormException(
            ApplicationFormErrorCode.PUBLICATION_REVIEW_REQUIRED,
            "Existing detail definitions must remain unchanged; add a new collection for a new"
                + " structure");
      }
    }
  }
}
