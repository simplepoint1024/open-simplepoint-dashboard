package org.simplepoint.plugin.app.form.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionPreparation;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionPreparation.Variable;
import org.springframework.stereotype.Service;

/** Resolves current authority and original versions before the browser edits a new submission. */
@Service
public class FormSubmissionPreparationService {
  private final PublishedApplicationFormResolver resolver;
  private final ApplicationFormRuntimeServiceImpl runtime;
  private final ApplicationFormRecordServiceImpl records;
  private final FormWorkflowGateway workflow;
  private final FormSubmissionJournal journal;
  private final FormRequestContext actors;
  private final DnaInvocationContextFactory contexts;

  /** Creates the coordinator with explicit persistence, policy and service dependencies. */
  public FormSubmissionPreparationService(
      PublishedApplicationFormResolver resolver,
      ApplicationFormRuntimeServiceImpl runtime,
      ApplicationFormRecordServiceImpl records,
      FormWorkflowGateway workflow,
      FormSubmissionJournal journal,
      FormRequestContext actors,
      DnaInvocationContextFactory contexts) {
    this.resolver = resolver;
    this.runtime = runtime;
    this.records = records;
    this.workflow = workflow;
    this.journal = journal;
    this.actors = actors;
    this.contexts = contexts;
  }

  /** Pins submission versions and filters starter inputs through current authorization. */
  public ApplicationFormSubmissionPreparation prepare(
      String applicationId,
      String pageId,
      String definitionId,
      int definitionVersion,
      String previousSubmissionId) {
    var actor = actors.requireActor();
    var previous =
        previousSubmissionId == null
            ? null
            : journal.getOwned(previousSubmissionId, actor.tenantId(), actor.actorId());
    if (previous != null
        && (!applicationId.equals(previous.applicationId())
            || !pageId.equals(previous.pageId())
            || !definitionId.equals(previous.definitionId())
            || definitionVersion != previous.definitionVersion()
            || previous.processInstanceId() == null)) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RECORD_INVALID, "Resubmission identity does not match");
    }
    var form =
        previous == null
            ? resolver.resolve(applicationId, pageId)
            : resolver.resolveVersion(
                applicationId, pageId, previous.formId(), previous.formVersion());
    resolver.requireAction(form, "process.start");
    var authority = FormSubmissionAuthorization.workflowContext();
    var source =
        previous == null
            ? null
            : workflow.resubmissionForm(authority, previous.processInstanceId());
    if (source != null
        && !Set.of("REJECTED", "WITHDRAWN", "CANCELED").contains(source.source().status())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RECORD_CONFLICT, "This process cannot be resubmitted");
    }
    var processForm =
        source == null
            ? workflow.startForm(authority, definitionId, definitionVersion)
            : source.form();
    FormSubmissionMapping.requireBinding(
        form, processForm, contexts.forResource(form.resourceCode()));
    var mapped = Set.copyOf(processForm.binding().variableMappings().values());
    var variables =
        processForm.formContract().variables().stream()
            .filter(
                item ->
                    !mapped.contains(item.name()) && Set.of("start", "both").contains(item.scope()))
            .map(
                item ->
                    new Variable(
                        item.name(),
                        item.label(),
                        item.type(),
                        item.scope(),
                        item.required(),
                        item.defaultValue()))
            .toList();
    Map<String, Object> values = new LinkedHashMap<>();
    variables.forEach(
        item -> {
          Object value = processForm.values().getOrDefault(item.name(), item.defaultValue());
          if (value != null) {
            values.put(item.name(), value);
          }
        });
    return new ApplicationFormSubmissionPreparation(
        definitionId,
        definitionVersion,
        previousSubmissionId,
        source == null ? form.formName() : source.source().title(),
        runtime.describe(form, processForm.binding().sceneId()),
        previous == null ? null : records.get(form, previous.recordId()),
        variables,
        values,
        source == null ? java.util.List.of() : source.ccUserIds(),
        previous != null && FormResubmissionMode.requiresCopy(form.document()));
  }
}
