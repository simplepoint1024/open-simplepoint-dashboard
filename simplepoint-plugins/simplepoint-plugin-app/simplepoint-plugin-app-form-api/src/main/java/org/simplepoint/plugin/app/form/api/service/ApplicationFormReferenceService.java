package org.simplepoint.plugin.app.form.api.service;

import java.util.List;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceDetail;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceOption;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferencePage;

/** Relation-owned directory: clients never select target resources, models or arbitrary fields. */
public interface ApplicationFormReferenceService {
  /** Searches the authorized frozen candidate definition without browser context inputs. */
  FormReferencePage search(
      String applicationId,
      String pageId,
      String relationId,
      String keyword,
      int page,
      int size,
      org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity runtime);

  /** Adds only declared, typed uncommitted candidate inputs; policy still comes from the server. */
  FormReferencePage searchContext(
      String applicationId,
      String pageId,
      String relationId,
      String keyword,
      int page,
      int size,
      org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity runtime,
      org.simplepoint.plugin.app.form.api.model.relation.FormReferenceSearchContext context);

  /** Resolves readable selections even when they no longer satisfy candidate filters. */
  List<FormReferenceOption> resolve(
      String applicationId,
      String pageId,
      String relationId,
      List<String> recordIds,
      org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity runtime);

  /**
   * Opens a readable current target; neither a reference nor its label grants target record access.
   */
  FormReferenceDetail detail(
      String applicationId,
      String pageId,
      String relationId,
      String recordId,
      org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity runtime);
}
