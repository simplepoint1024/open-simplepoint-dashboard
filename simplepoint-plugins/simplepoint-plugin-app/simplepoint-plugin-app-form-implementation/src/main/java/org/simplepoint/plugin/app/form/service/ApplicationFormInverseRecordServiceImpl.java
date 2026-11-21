package org.simplepoint.plugin.app.form.service;

import java.util.Map;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordMutationView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeCapabilities;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewIdentity;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormInverseRecordService;
import org.simplepoint.plugin.app.form.service.FormInverseRelationResolver.ResolvedView;
import org.springframework.stereotype.Service;

/** Resolves immutable view configuration on every request and reuses the standard record domain. */
@Service
public class ApplicationFormInverseRecordServiceImpl
    implements ApplicationFormInverseRecordService {
  private final FormInverseRelationResolver views;
  private final FormInverseRecordQueries queries;
  private final FormInverseRecordCommands commands;
  private final PublishedApplicationFormResolver forms;
  private final ApplicationFormRecordSchemaServiceImpl schemas;
  private final ApplicationFormRuntimeServiceImpl runtime;

  /** Uses domain-owned adapters; neither transport nor this facade accesses persistence. */
  public ApplicationFormInverseRecordServiceImpl(
      FormInverseRelationResolver views,
      FormInverseRecordQueries queries,
      FormInverseRecordCommands commands,
      PublishedApplicationFormResolver forms,
      ApplicationFormRecordSchemaServiceImpl schemas,
      ApplicationFormRuntimeServiceImpl runtime) {
    this.views = views;
    this.queries = queries;
    this.commands = commands;
    this.forms = forms;
    this.schemas = schemas;
    this.runtime = runtime;
  }

  @Override
  public Map<String, Object> schema(
      String applicationId, String pageId, FormInverseViewIdentity view) {
    var resolved = resolve(applicationId, pageId, view);
    return schemas.schema(resolved.source(), capabilities(resolved), canUnlink(resolved));
  }

  @Override
  public java.util.List<org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewSummary>
      views(
          String applicationId,
          String pageId,
          ApplicationFormRuntimeIdentity identity,
          String recordId) {
    return views.views(applicationId, pageId, identity, recordId);
  }

  @Override
  public org.simplepoint.plugin.app.form.api.model.relation.FormInverseRuntimeView describe(
      String applicationId, String pageId, FormInverseViewIdentity view) {
    var resolved = resolve(applicationId, pageId, view);
    var source = runtime.describe(resolved.source(), null);
    var descriptor =
        new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeView(
            source.applicationId(),
            source.pageId(),
            source.pageName(),
            source.formId(),
            source.formName(),
            source.formVersion(),
            source.sceneId(),
            source.resourceCode(),
            capabilities(resolved),
            source.document(),
            source.writePolicyVersion());
    return new org.simplepoint.plugin.app.form.api.model.relation.FormInverseRuntimeView(
        descriptor,
        resolved.sourceField().fieldId(),
        resolved.relation().sourceField().detailId(),
        canUnlink(resolved));
  }

  private boolean canUnlink(ResolvedView resolved) {
    return resolved.view().allowUnlink()
        && forms.capabilities(resolved.source()).canEdit()
        && !resolved.sourceField().storageRequired()
        && views.writable(
            resolved, org.simplepoint.plugin.app.form.api.model.FormRecordState.DRAFT);
  }

  private ApplicationFormRuntimeCapabilities capabilities(ResolvedView resolved) {
    var allowed = forms.capabilities(resolved.source());
    var configured = resolved.view();
    // The entry confirms saved data through the same scoped immutable submission capture.
    return new ApplicationFormRuntimeCapabilities(
        configured.allowCreate() && allowed.canCreate() && views.writable(resolved, null),
        configured.allowEdit() && allowed.canEdit(),
        configured.allowDelete() && allowed.canDelete(),
        configured.allowEdit() && allowed.canEdit() && allowed.canStartProcess());
  }

  @Override
  public ApplicationFormRecordPageView page(
      String applicationId,
      String pageId,
      FormInverseViewIdentity view,
      ApplicationFormRecordPageQuery query) {
    return queries.page(resolve(applicationId, pageId, view), query);
  }

  @Override
  public ApplicationFormRecordView get(
      String applicationId, String pageId, FormInverseViewIdentity view, String recordId) {
    return queries.get(resolve(applicationId, pageId, view), recordId);
  }

  @Override
  public ApplicationFormRecordMutationView create(
      String applicationId,
      String pageId,
      FormInverseViewIdentity view,
      CreateApplicationFormRecordCommand command) {
    return commands.create(resolve(applicationId, pageId, view), command);
  }

  @Override
  public ApplicationFormRecordMutationView update(
      String applicationId,
      String pageId,
      FormInverseViewIdentity view,
      String recordId,
      UpdateApplicationFormRecordCommand command) {
    return commands.update(resolve(applicationId, pageId, view), recordId, command);
  }

  @Override
  public void delete(
      String applicationId,
      String pageId,
      FormInverseViewIdentity view,
      String recordId,
      long expectedVersion,
      ApplicationFormRuntimeIdentity runtime,
      String idempotencyKey) {
    commands.delete(
        resolve(applicationId, pageId, view), recordId, expectedVersion, runtime, idempotencyKey);
  }

  @Override
  public ApplicationFormRecordMutationView unlink(
      String applicationId,
      String pageId,
      FormInverseViewIdentity view,
      String recordId,
      UpdateApplicationFormRecordCommand command,
      String sourceRowId) {
    return commands.unlink(resolve(applicationId, pageId, view), recordId, command, sourceRowId);
  }

  private ResolvedView resolve(String applicationId, String pageId, FormInverseViewIdentity view) {
    if (view == null) {
      throw new org.simplepoint.plugin.app.form.api.error.ApplicationFormException(
          org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode.RECORD_INVALID,
          "A published inverse view identity is required");
    }
    return views.resolve(applicationId, pageId, view.runtime(), view.viewId(), view.recordId());
  }
}
