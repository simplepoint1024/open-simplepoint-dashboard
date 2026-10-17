package org.simplepoint.plugin.app.form.api.model;

import java.util.List;
import java.util.Map;

/** Authorized inputs for one version-pinned business submission, without exposing engine internals. */
public record ApplicationFormSubmissionPreparation(
    String definitionId,
    int definitionVersion,
    String previousSubmissionId,
    String title,
    ApplicationFormRuntimeView form,
    ApplicationFormRecordView record,
    List<Variable> variables,
    Map<String, Object> values,
    List<String> ccUserIds,
    boolean copyOnResubmission
) {
  /** Existing editable forms continue updating their original record. */
  public ApplicationFormSubmissionPreparation(String definitionId, int definitionVersion,
      String previousSubmissionId, String title, ApplicationFormRuntimeView form,
      ApplicationFormRecordView record, List<Variable> variables, Map<String, Object> values,
      List<String> ccUserIds) {
    this(definitionId, definitionVersion, previousSubmissionId, title, form, record, variables, values, ccUserIds, false);
  }

  /** Only unmapped starter inputs are editable; mapped facts come from the committed DNA snapshot. */
  public record Variable(String name, String label, String type, String scope, boolean required, Object defaultValue) {
  }
}
