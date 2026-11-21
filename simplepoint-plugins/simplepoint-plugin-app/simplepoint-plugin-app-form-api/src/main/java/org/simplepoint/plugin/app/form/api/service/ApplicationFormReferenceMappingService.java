package org.simplepoint.plugin.app.form.api.service;

import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingQuery;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingView;

/** Relation-owned mapping observations, independent of transport and physical storage contracts. */
public interface ApplicationFormReferenceMappingService {
  /** Authorizes both source operation and target values before returning a prospective mapping. */
  FormReferenceMappingView observe(
      String applicationId, String pageId, String relationId, FormReferenceMappingQuery query);
}
