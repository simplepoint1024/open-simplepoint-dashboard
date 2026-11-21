package org.simplepoint.plugin.app.form.api.service;

import java.util.Map;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordMutationView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewIdentity;

/**
 * Published inverse view facade; every operation resolves both entries and their current grants.
 */
public interface ApplicationFormInverseRecordService {
  /** Lists only inverse views whose parent and source entries are currently readable. */
  java.util.List<org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewSummary> views(
      String applicationId, String pageId, ApplicationFormRuntimeIdentity runtime, String recordId);

  /** Describes a source editor only after resolving its published owning relation and parent. */
  org.simplepoint.plugin.app.form.api.model.relation.FormInverseRuntimeView describe(
      String applicationId, String pageId, FormInverseViewIdentity view);

  /** Shares the standard table schema, narrowed by published view capabilities. */
  Map<String, Object> schema(String applicationId, String pageId, FormInverseViewIdentity view);

  /** Applies the owning relation before server filtering, counting and paging. */
  ApplicationFormRecordPageView page(
      String applicationId,
      String pageId,
      FormInverseViewIdentity view,
      ApplicationFormRecordPageQuery query);

  /** Reads one current authorized member, including its owned detail collections. */
  ApplicationFormRecordView get(
      String applicationId, String pageId, FormInverseViewIdentity view, String recordId);

  /** Creates through the source form's validation and atomic aggregate transaction. */
  ApplicationFormRecordMutationView create(
      String applicationId,
      String pageId,
      FormInverseViewIdentity view,
      CreateApplicationFormRecordCommand command);

  /** Edits an existing member without allowing an implicit rebind to another parent. */
  ApplicationFormRecordMutationView update(
      String applicationId,
      String pageId,
      FormInverseViewIdentity view,
      String recordId,
      UpdateApplicationFormRecordCommand command);

  /** Deletes an aggregate; removing a reference is a separate operation. */
  void delete(
      String applicationId,
      String pageId,
      FormInverseViewIdentity view,
      String recordId,
      long expectedVersion,
      ApplicationFormRuntimeIdentity runtime,
      String idempotencyKey);

  /** Clears the nullable owning reference, retaining the record and the selected owned row. */
  ApplicationFormRecordMutationView unlink(
      String applicationId,
      String pageId,
      FormInverseViewIdentity view,
      String recordId,
      UpdateApplicationFormRecordCommand command,
      String sourceRowId);
}
