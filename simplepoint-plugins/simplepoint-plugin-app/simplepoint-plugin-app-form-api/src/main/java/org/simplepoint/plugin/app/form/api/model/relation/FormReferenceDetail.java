package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.Objects;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeView;

/** One authorized current record and its exact resolved form; never a caller-supplied URL. */
public record FormReferenceDetail(
    ApplicationFormRuntimeView form, ApplicationFormRecordView record) {
  public FormReferenceDetail {
    Objects.requireNonNull(form, "form");
    Objects.requireNonNull(record, "record");
  }
}
