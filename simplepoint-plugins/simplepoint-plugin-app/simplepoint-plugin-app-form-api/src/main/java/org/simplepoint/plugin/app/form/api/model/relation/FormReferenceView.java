package org.simplepoint.plugin.app.form.api.model.relation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashSet;
import java.util.List;
import org.simplepoint.plugin.app.form.api.model.FormCondition;

/** Default presentation of a reference; eligibility remains a separate business policy. */
public record FormReferenceView(
    String labelFieldId,
    List<String> searchFieldIds,
    List<String> displayFieldIds,
    @JsonInclude(JsonInclude.Include.NON_NULL) FormCondition candidateFilter,
    boolean allowDrillThrough,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FormReferenceContextFilter> contextFilters) {
  /** Preserves existing immutable views with no context-dependent candidate filters. */
  public FormReferenceView(
      String labelFieldId,
      List<String> searchFieldIds,
      List<String> displayFieldIds,
      FormCondition candidateFilter,
      boolean allowDrillThrough) {
    this(
        labelFieldId,
        searchFieldIds,
        displayFieldIds,
        candidateFilter,
        allowDrillThrough,
        List.of());
  }

  /** Validates presentation identities and independent static/context predicate budgets. */
  public FormReferenceView {
    labelFieldId = FormRelationIdentity.require(labelFieldId);
    searchFieldIds = fields(searchFieldIds, 8);
    displayFieldIds = fields(displayFieldIds, 12);
    contextFilters = contextFilters == null ? List.of() : List.copyOf(contextFilters);
    if (contextFilters.size() > 16
        || new HashSet<>(contextFilters).size() != contextFilters.size()) {
      throw new IllegalArgumentException(
          "Reference candidate context budget or uniqueness is invalid");
    }
    if (candidateFilter != null) {
      // Same bounded typed predicate shape, without turning browse preferences into write policy.
      FormRelationQualification.validateCondition(candidateFilter);
    }
  }

  private static List<String> fields(List<String> fields, int limit) {
    var result = fields == null ? List.<String>of() : List.copyOf(fields);
    if (result.size() > limit || new HashSet<>(result).size() != result.size()) {
      throw new IllegalArgumentException("Reference view field budget or uniqueness is invalid");
    }
    result.forEach(FormRelationIdentity::require);
    return result;
  }
}
