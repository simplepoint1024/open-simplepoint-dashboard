package org.simplepoint.plugin.app.form.api.service;

import java.util.List;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTargetMetadata;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTargetOption;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTargetPage;

/** Designer-only discovery. Selecting a candidate never grants runtime or publication authority. */
public interface ApplicationFormRelationCatalogService {
  /** Searches authorized, published source candidates with server pagination. */
  FormRelationTargetPage search(
      String applicationId, String formId, String keyword, int page, int size);

  /** Hydrates selected resource labels within the designer owner scope. */
  List<FormRelationTargetOption> resolve(
      String applicationId, String formId, List<String> resourceIds);

  /** Describes readable target fields without exposing storage metadata. */
  FormRelationTargetMetadata describe(
      String applicationId, String formId, FormRelationTarget target);

  /** Lists readable owning references from a selected source back to the designed form. */
  org.simplepoint.plugin.app.form.api.model.relation.FormInverseSourceMetadata inverseSource(
      String applicationId, String formId, FormRelationTarget source);
}
