package org.simplepoint.plugin.app.form.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeCapabilities;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionSnapshot;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.springframework.stereotype.Service;

/**
 * Workflow participation grants access to one frozen submission, intersected with current
 * page/field policy.
 */
@Service
public class FormSubmissionSnapshotService {
  private final FormWorkflowGateway workflow;
  private final FormSubmissionJournal journal;
  private final PublishedApplicationFormResolver resolver;
  private final FormRuntimeDocumentCompiler documents;
  private final DnaInvocationContextFactory contexts;
  private final FormRequestContext actors;
  private final FormRecordIdentityLabels identityLabels;
  private final FormSubmissionHistory history;
  private final org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordService records;

  /** Creates the coordinator with explicit persistence, policy and service dependencies. */
  public FormSubmissionSnapshotService(
      FormWorkflowGateway workflow,
      FormSubmissionJournal journal,
      PublishedApplicationFormResolver resolver,
      FormRuntimeDocumentCompiler documents,
      DnaInvocationContextFactory contexts,
      FormRequestContext actors,
      FormRecordIdentityLabels identityLabels,
      FormSubmissionHistory history,
      org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordService records) {
    this.workflow = workflow;
    this.journal = journal;
    this.resolver = resolver;
    this.documents = documents;
    this.contexts = contexts;
    this.actors = actors;
    this.identityLabels = identityLabels;
    this.history = history;
    this.records = records;
  }

  /** Loads a task-authorized snapshot and applies current page and field permissions. */
  public ApplicationFormSubmissionSnapshot task(String taskId) {
    var authority = FormSubmissionAuthorization.workflowContext();
    // Engine verifies the exact current assignee/candidate and tenant before APP loads any
    // snapshot.
    var task = workflow.taskForm(authority, taskId);
    var snapshot = load(task.businessKey(), authority.tenantId());
    if (!snapshot.view().definitionId().equals(task.definitionId())
        || !matchesBinding(task.formContract().startBinding(), snapshot)
        || task.binding() != null && !matchesBinding(task.binding(), snapshot)
        || task.definitionVersion() != snapshot.view().definitionVersion()
        || snapshot.view().processInstanceId() != null
            && !snapshot.view().processInstanceId().equals(task.processInstanceId())) {
      throw unavailable();
    }
    return project(
        snapshot,
        task.binding() == null
            ? task.formContract().startBinding().sceneId()
            : task.binding().sceneId());
  }

  /** Loads immutable business evidence after checking process participation. */
  public ApplicationFormSubmissionSnapshot instance(String instanceId) {
    var authority = FormSubmissionAuthorization.workflowContext();
    var instance = workflow.instance(authority, instanceId);
    var snapshot = load(instance.businessKey(), authority.tenantId());
    if (!instance.definitionId().equals(snapshot.view().definitionId())
        || instance.definitionVersion() != snapshot.view().definitionVersion()
        || snapshot.view().processInstanceId() != null
            && !snapshot.view().processInstanceId().equals(instance.id())) {
      throw unavailable();
    }
    return project(snapshot, snapshot.accepted().workflow().binding().sceneId());
  }

  /** Loads the original submission for its owner on the bound application page. */
  public ApplicationFormSubmissionSnapshot owned(String applicationId, String pageId, String id) {
    var actor = actors.requireActor();
    var submission = journal.getOwned(id, actor.tenantId(), actor.actorId());
    if (!applicationId.equals(submission.applicationId()) || !pageId.equals(submission.pageId())) {
      throw unavailable();
    }
    var snapshot = journal.snapshot(id, actor.tenantId());
    return project(snapshot, snapshot.accepted().workflow().binding().sceneId());
  }

  /** Record readers may inspect evidence, but gain no submission mutation/engine authority. */
  public ApplicationFormSubmissionSnapshot forRecord(
      String applicationId, String pageId, String recordId, String id) {
    var actor = actors.requireActor();
    records.get(applicationId, pageId, recordId);
    var snapshot = journal.snapshot(id, actor.tenantId());
    if (!applicationId.equals(snapshot.view().applicationId())
        || !pageId.equals(snapshot.view().pageId())
        || !recordId.equals(snapshot.record().id())) {
      throw unavailable();
    }
    return project(snapshot, snapshot.accepted().workflow().binding().sceneId());
  }

  private FormSubmissionJournal.Snapshot load(String businessKey, String tenant) {
    if (businessKey == null || !businessKey.matches("app-form:[a-f0-9]{64}")) {
      throw unavailable();
    }
    return journal.snapshot(businessKey.substring("app-form:".length()), tenant);
  }

  private static boolean matchesBinding(
      WorkflowFormContract.Binding binding, FormSubmissionJournal.Snapshot snapshot) {
    return binding != null
        && FormSubmissionMapping.PROVIDER.equals(binding.provider())
        && snapshot.view().formId().equals(binding.formId())
        && snapshot.view().formVersion() == binding.version()
        && (binding.sceneId() == null
            || snapshot.accepted().form().document().scenes().stream()
                .anyMatch(scene -> scene.sceneId().equals(binding.sceneId())));
  }

  private ApplicationFormSubmissionSnapshot project(
      FormSubmissionJournal.Snapshot snapshot, String sceneId) {
    var form = snapshot.accepted().form();
    resolver.requireRead(form);
    var captured = history.read(snapshot);
    var authority = contexts.forForm(form);
    var current = resolver.currentCalculationScope(form);
    var policy = FormCalculationPermissions.restrict(current, authority.fieldPermissions());
    Map<String, Object> values =
        projectFields(form.document().fields(), captured.values(), form.physicalModelId(), policy);
    for (var detail : form.document().details()) {
      var binding =
          form.detailBindings().stream()
              .filter(item -> item.detailId().equals(detail.detailId()))
              .findFirst()
              .orElseThrow();
      var rows =
          FormDetailRecordCoordinator.parseRows(
              captured.values().getOrDefault(detail.detailId(), List.of()));
      values.put(
          detail.detailId(),
          rows.stream()
              .map(
                  row ->
                      Map.of(
                          "id",
                          row.id(),
                          "version",
                          row.version(),
                          "values",
                          projectFields(
                              detail.fields(), row.values(), binding.physicalModelId(), policy),
                          "systemValues",
                          FormRecordSystemFields.project(
                              row.systemValues(), binding.physicalModelId(), policy)))
              .toList());
    }
    var descriptor =
        new ApplicationFormRuntimeView(
            form.applicationId(),
            form.pageId(),
            form.pageName(),
            form.formId(),
            form.formName(),
            form.formVersion(),
            sceneId == null
                ? form.document().scenes().stream()
                    .filter(scene -> scene.type() == FormSceneType.MAIN)
                    .findFirst()
                    .orElseThrow()
                    .sceneId()
                : sceneId,
            form.resourceCode(),
            new ApplicationFormRuntimeCapabilities(false, false, false, false),
            documents.compile(form, policy),
            form.writePolicyVersion());
    var projected =
        new ApplicationFormRecordView(
            captured.id(),
            captured.version(),
            captured.state(),
            values,
            FormRecordSystemFields.project(
                captured.systemValues(), form.physicalModelId(), policy));
    var labels = FormReferenceSnapshots.visible(form, projected, captured.referenceSnapshots());
    var authorized =
        new ApplicationFormRecordView(
            projected.id(),
            projected.version(),
            projected.state(),
            projected.values(),
            projected.systemValues(),
            projected.systemLabels(),
            labels);
    return new ApplicationFormSubmissionSnapshot(
        snapshot.view(), descriptor, identityLabels.decorate(form.tenantId(), authorized));
  }

  private static Map<String, Object> projectFields(
      List<FormFieldDefinition> fields,
      Map<String, Object> captured,
      String model,
      Map<String, String> permissions) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var field : fields) {
      String policy =
          RecordPermissionModel.access(permissions, model, field.fieldId(), field.storageCode());
      if ("HIDDEN".equals(policy) || !captured.containsKey(field.fieldId())) {
        continue;
      }
      Object value = captured.get(field.fieldId());
      result.put(field.fieldId(), "MASKED".equals(policy) && value != null ? "***" : value);
    }
    return result;
  }

  private static ApplicationFormException unavailable() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RECORD_NOT_FOUND, "Business form snapshot is unavailable");
  }
}
