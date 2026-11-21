package org.simplepoint.plugin.app.form.service;

import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionView;
import org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand;
import org.springframework.stereotype.Service;

/** APP owns durable business submissions; DNA owns transactions and Workflow owns process facts. */
@Service
public class ApplicationFormSubmissionService {
  private final PublishedApplicationFormResolver resolver;
  private final ApplicationFormRecordServiceImpl records;
  private final DnaInvocationContextFactory contexts;
  private final FormWorkflowGateway workflow;
  private final FormSubmissionJournal journal;
  private final FormSubmissionProcessor processor;
  private final FormSubmissionCodec codec;
  private final FormRequestContext actors;

  /** Creates the coordinator with explicit persistence, policy and service dependencies. */
  public ApplicationFormSubmissionService(
      PublishedApplicationFormResolver resolver,
      ApplicationFormRecordServiceImpl records,
      DnaInvocationContextFactory contexts,
      FormWorkflowGateway workflow,
      FormSubmissionJournal journal,
      FormSubmissionProcessor processor,
      FormSubmissionCodec codec,
      FormRequestContext actors) {
    this.resolver = resolver;
    this.records = records;
    this.contexts = contexts;
    this.workflow = workflow;
    this.journal = journal;
    this.processor = processor;
    this.codec = codec;
    this.actors = actors;
  }

  /** Validates and persists one intent before starting recoverable data and process operations. */
  public ApplicationFormSubmissionView submit(
      String applicationId, String pageId, SubmitApplicationFormCommand command) {
    return submit(applicationId, pageId, command, null, null);
  }

  private ApplicationFormSubmissionView submit(
      String applicationId,
      String pageId,
      SubmitApplicationFormCommand command,
      PublishedApplicationFormResolver.PublishedApplicationForm resolved,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope relatedScope) {
    if (command == null) {
      throw invalid("Submission intent is required");
    }
    var actor = actors.requireActor();
    var previous =
        command.previousSubmissionId() == null
            ? null
            : journal.getOwned(command.previousSubmissionId(), actor.tenantId(), actor.actorId());
    var form =
        resolved != null
            ? resolved
            : previous == null
                ? resolver.resolve(applicationId, pageId)
                : resolver.resolveVersion(
                    applicationId, pageId, previous.formId(), previous.formVersion());
    resolver.requireAction(form, "process.start");
    String id =
        codec.fingerprint(
            List.of(actor.tenantId(), applicationId, pageId, command.idempotencyKey()));
    String fingerprint =
        codec.fingerprint(
            relatedScope == null
                ? List.of(applicationId, pageId, command)
                : List.of(
                    "app.submission.related.v1", applicationId, pageId, command, relatedScope));
    var existing = journal.findExact(id, actor.tenantId(), actor.actorId(), fingerprint);
    if (existing == null) {
      ApplicationFormRuntimeIdentity.requireCurrent(
          command.runtime(), form.formId(), form.formVersion());
      var authority = contexts.forWrite(form);
      var workflowAuthority = FormSubmissionAuthorization.workflowContext();
      var runtime =
          workflow.startForm(
              workflowAuthority, command.definitionId(), command.definitionVersion());
      FormSubmissionMapping.requireBinding(form, runtime, authority);
      var prospective =
          relatedScope == null
              ? records.previewSubmission(form, command)
              : records.previewSubmission(form, command, relatedScope);
      var variables =
          FormSubmissionMapping.variables(form, runtime, prospective, command.variables());
      var validated =
          workflow.validateStartForm(
              workflowAuthority,
              command.definitionId(),
              command.definitionVersion(),
              variables,
              command.ccUserIds());
      String previousInstance = null;
      if (command.previousSubmissionId() != null) {
        if (!applicationId.equals(previous.applicationId())
            || !pageId.equals(previous.pageId())
            || !form.formId().equals(previous.formId())
            || !(command.recordId() == null && FormResubmissionMode.requiresCopy(form.document()))
                && !java.util.Objects.equals(command.recordId(), previous.recordId())
            || !command.definitionId().equals(previous.definitionId())
            || command.definitionVersion() != previous.definitionVersion()
            || previous.processInstanceId() == null) {
          throw invalid(
              "Resubmission must retain its process version and an authorized record revision");
        }
        var source =
            workflow.resubmissionForm(workflowAuthority, previous.processInstanceId()).source();
        if (!Set.of("REJECTED", "WITHDRAWN", "CANCELED").contains(source.status())) {
          throw invalid("Only a rejected, withdrawn or canceled submission can be resubmitted");
        }
        previousInstance = source.id();
      }
      journal.accept(
          id,
          fingerprint,
          new AcceptedFormSubmission(
              form,
              command,
              authority,
              workflowAuthority,
              validated,
              previousInstance,
              FormSubmissionMapping.captureRequirements(form, validated),
              relatedScope));
    }
    processor.advance(id);
    return journal.getOwned(id, actor.tenantId(), actor.actorId());
  }

  /** Confirms a saved source record under the server-resolved inverse entry. */
  ApplicationFormSubmissionView submitRelated(
      PublishedApplicationFormResolver.PublishedApplicationForm form,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope scope,
      SubmitApplicationFormCommand command) {
    if (command == null
        || command.recordId() == null
        || command.previousSubmissionId() != null
        || !command.values().isEmpty()
        || scope == null
        || scope.mode()
            != org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope.Mode.MAINTAIN) {
      throw invalid("Related submissions require a saved read-only confirmation");
    }
    return submit(form.applicationId(), form.pageId(), command, form, scope);
  }

  /** Lists published processes bound to the current authorized form version. */
  public org.simplepoint.plugin.app.form.api.model.ApplicationFormWorkflowDefinitions definitions(
      String applicationId, String pageId, String keyword, int page, int size) {
    return definitions(resolver.resolve(applicationId, pageId), keyword, page, size);
  }

  org.simplepoint.plugin.app.form.api.model.ApplicationFormWorkflowDefinitions definitions(
      PublishedApplicationFormResolver.PublishedApplicationForm form,
      String keyword,
      int page,
      int size) {
    resolver.requireAction(form, "process.start");
    var result =
        workflow.definitions(
            FormSubmissionAuthorization.workflowContext(),
            form.formId(),
            form.formVersion(),
            keyword,
            page,
            size);
    return new org.simplepoint.plugin.app.form.api.model.ApplicationFormWorkflowDefinitions(
        result.content().stream()
            .map(
                item ->
                    new org.simplepoint.plugin.app.form.api.model.ApplicationFormWorkflowDefinitions
                        .Definition(item.id(), item.name(), item.version(), item.description()))
            .toList(),
        result.page(),
        result.size(),
        result.totalElements());
  }

  /** Resumes the original owned intent without accepting replacement business values. */
  public ApplicationFormSubmissionView retry(String applicationId, String pageId, String id) {
    var current = get(applicationId, pageId, id);
    var form = resolver.resolve(applicationId, pageId);
    resolver.requireAction(form, "process.start");
    var actor = actors.requireActor();
    journal.retry(current.id(), actor.tenantId(), actor.actorId());
    processor.advance(id);
    return journal.getOwned(id, actor.tenantId(), actor.actorId());
  }

  /** Returns submission metadata only after checking the page and original submitter. */
  public ApplicationFormSubmissionView get(String applicationId, String pageId, String id) {
    var actor = actors.requireActor();
    var form = resolver.resolve(applicationId, pageId);
    resolver.requireAction(form, "process.start");
    var result = journal.getOwned(id, actor.tenantId(), actor.actorId());
    if (!applicationId.equals(result.applicationId()) || !pageId.equals(result.pageId())) {
      throw invalid("Submission belongs to another page");
    }
    return result;
  }

  ApplicationFormSubmissionView getRelated(
      PublishedApplicationFormResolver.PublishedApplicationForm form,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope scope,
      String id) {
    resolver.requireAction(form, "process.start");
    var actor = actors.requireActor();
    var result = journal.getOwnedRelated(id, actor.tenantId(), actor.actorId(), scope);
    if (!form.applicationId().equals(result.applicationId())
        || !form.pageId().equals(result.pageId())) {
      throw invalid("Submission belongs to another page");
    }
    return result;
  }

  private static ApplicationFormException invalid(String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.RECORD_INVALID, message);
  }
}
