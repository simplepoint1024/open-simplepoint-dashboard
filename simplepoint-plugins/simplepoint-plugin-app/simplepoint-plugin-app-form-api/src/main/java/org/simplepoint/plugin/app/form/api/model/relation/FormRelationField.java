package org.simplepoint.plugin.app.form.api.model.relation;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Root or first-level owned-detail field in the definition's owning form. */
public record FormRelationField(
    @JsonInclude(JsonInclude.Include.NON_NULL) String detailId,
    String fieldId) {

  public FormRelationField {
    if (detailId != null) {
      detailId = FormRelationIdentity.require(detailId);
    }
    fieldId = FormRelationIdentity.require(fieldId);
  }
}
