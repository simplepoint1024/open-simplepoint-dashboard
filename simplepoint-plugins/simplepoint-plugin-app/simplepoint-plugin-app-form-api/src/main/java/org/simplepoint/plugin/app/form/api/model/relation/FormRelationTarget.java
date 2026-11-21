package org.simplepoint.plugin.app.form.api.model.relation;

/** Stable independent form identity and its explicit authorized entry; tenant is server-derived. */
public record FormRelationTarget(String applicationId, String formId, String accessResourceId) {

  public FormRelationTarget {
    applicationId = FormRelationIdentity.require(applicationId);
    formId = FormRelationIdentity.require(formId);
    accessResourceId = FormRelationIdentity.require(accessResourceId);
  }
}
