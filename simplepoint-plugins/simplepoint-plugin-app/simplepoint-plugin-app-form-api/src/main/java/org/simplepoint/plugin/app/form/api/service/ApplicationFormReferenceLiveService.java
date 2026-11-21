package org.simplepoint.plugin.app.form.api.service;

import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveQuery;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveView;

/** APP-owned authorized display projection; it has no write or publication side effects. */
public interface ApplicationFormReferenceLiveService {
  /** Rechecks the current target and original copied-field permissions on each read. */
  FormReferenceLiveView read(
      String applicationId, String pageId, String relationId, FormReferenceLiveQuery query);

  /** Hydrates at most 100 unique selections with one scoped value query. */
  java.util.List<FormReferenceLiveView> readBatch(
      String applicationId,
      String pageId,
      String relationId,
      org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveBatchQuery query);
}
