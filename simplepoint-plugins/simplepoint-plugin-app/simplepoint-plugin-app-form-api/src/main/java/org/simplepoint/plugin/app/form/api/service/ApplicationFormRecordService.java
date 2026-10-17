package org.simplepoint.plugin.app.form.api.service;

import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordMutationView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand;

/** Page-bound runtime record API backed by a server-selected DNA model. */
public interface ApplicationFormRecordService {

  /** Returns one authorized record page. */
  ApplicationFormRecordPageView page(String applicationId, String pageId,
      ApplicationFormRecordPageQuery query);

  /** Returns one authorized record. */
  ApplicationFormRecordView get(String applicationId, String pageId, String recordId);

  /** Creates one draft or submitted record idempotently. */
  ApplicationFormRecordMutationView create(String applicationId, String pageId,
      CreateApplicationFormRecordCommand command);

  /** Updates fields and lifecycle state using optimistic locking. */
  ApplicationFormRecordMutationView update(String applicationId, String pageId, String recordId,
      UpdateApplicationFormRecordCommand command);

  /** Soft deletes one record using optimistic locking. */
  void delete(String applicationId, String pageId, String recordId, long expectedVersion,
      ApplicationFormRuntimeIdentity runtime, String idempotencyKey);
}
