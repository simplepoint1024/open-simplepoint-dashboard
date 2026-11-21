package org.simplepoint.plugin.app.form.api.model.relation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;

/** An additional candidate predicate, never a grant or a write-time eligibility rule. */
public record FormReferenceContextFilter(
    String targetFieldId,
    FormConditionOperator operator,
    Source source,
    @JsonInclude(JsonInclude.Include.NON_NULL) String sourceFieldId) {
  /** Validates stable identities and limits organization values to trusted server context. */
  public FormReferenceContextFilter {
    targetFieldId = FormRelationIdentity.require(targetFieldId);
    if (source == null
        || operator == null
        || !Set.of(
                FormConditionOperator.EQUALS, FormConditionOperator.NOT_EQUALS,
                FormConditionOperator.GREATER_THAN, FormConditionOperator.GREATER_THAN_OR_EQUAL,
                FormConditionOperator.LESS_THAN, FormConditionOperator.LESS_THAN_OR_EQUAL)
            .contains(operator)) {
      throw new IllegalArgumentException("Invalid reference context predicate");
    }
    if (source == Source.ORGANIZATION) {
      if (sourceFieldId != null) {
        throw new IllegalArgumentException("Organization comes from the trusted request context");
      }
    } else {
      sourceFieldId = FormRelationIdentity.require(sourceFieldId);
    }
  }

  /** ROW is restricted to the reference's own detail; no arbitrary row or form lookup. */
  public enum Source {
    FORM,
    ROW,
    ORGANIZATION
  }
}
