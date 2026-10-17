package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.springframework.stereotype.Service;

/**
 * Resumes the exact accepted intent through independently committed, idempotent remote boundaries.
 */
@Service
public class FormSubmissionProcessor {
  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(FormSubmissionProcessor.class);
  private final FormSubmissionJournal journal;
  private final ApplicationFormRecordServiceImpl records;
  private final FormWorkflowGateway workflow;

  /** Creates the coordinator with explicit persistence, policy and service dependencies. */
  public FormSubmissionProcessor(
      FormSubmissionJournal journal,
      ApplicationFormRecordServiceImpl records,
      FormWorkflowGateway workflow) {
    this.journal = journal;
    this.records = records;
    this.workflow = workflow;
  }

  /**
   * Bounded immediate progress; the same entry point is used after restart and by the recovery
   * worker.
   */
  public void advance(String id) {
    for (int phase = 0; phase < 2; phase++) {
      var work = journal.claim(id);
      if (work == null) {
        return;
      }
      try (var ignored = new FormSubmissionAuthorization(work.accepted())) {
        var accepted = work.accepted();
        var command = accepted.command();
        if ("PREPARED".equals(work.step())) {
          String operationKey = "submission:" + id;
          var snapshot =
              command.recordId() == null
                  ? records.captureCreate(
                      accepted.form(),
                      new CreateApplicationFormRecordCommand(
                          command.runtime(),
                          operationKey,
                          FormRecordState.SUBMITTED,
                          command.values()))
                  : records.captureUpdate(
                      accepted.form(),
                      command.recordId(),
                      new UpdateApplicationFormRecordCommand(
                          command.runtime(),
                          command.expectedVersion(),
                          operationKey,
                          FormRecordState.SUBMITTED,
                          command.values()));
          journal.captured(id, work.token(), snapshot);
        } else if ("DATA_COMMITTED".equals(work.step())) {
          var variables =
              FormSubmissionMapping.variables(
                  accepted.form(),
                  accepted.workflow(),
                  work.snapshot().values(),
                  command.variables());
          var start =
              new StartProcessCommand(
                  command.definitionVersion(),
                  "app-form:" + id,
                  command.title(),
                  variables,
                  command.ccUserIds(),
                  "submission:" + id);
          var result =
              accepted.previousInstanceId() == null
                  ? workflow.start(accepted.workflowAuthority(), command.definitionId(), start)
                  : workflow.resubmit(
                      accepted.workflowAuthority(), accepted.previousInstanceId(), start);
          journal.started(id, work.token(), result);
        } else if ("STARTED".equals(work.step())) {
          journal.started(
              id,
              work.token(),
              workflow.instance(accepted.workflowAuthority(), work.processInstanceId()));
        } else {
          throw new IllegalStateException("Unknown submission step");
        }
      } catch (RuntimeException failure) {
        String code =
            failure instanceof ApplicationFormException domain
                ? domain.errorCode().name()
                : failure instanceof WorkflowException domain
                    ? "WORKFLOW_" + domain.getCode().name()
                    : "REMOTE_UNAVAILABLE";
        // Even a validation error may follow a lost successful response; retry uses the same
        // receipts.
        LOGGER.warn(
            "Form submission phase failed: submission={}, step={}, code={}, cause={}",
            id,
            work.step(),
            code,
            failure.getClass().getSimpleName());
        journal.failed(id, work.token(), code, false);
        return;
      }
    }
  }
}
