package org.simplepoint.plugin.app.form.api.service;

import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorDecoder;
import org.simplepoint.plugin.app.form.api.model.FormWorkflowBindingCheck;
import org.simplepoint.plugin.app.form.api.model.FormWorkflowSubmissionCheck;
import org.simplepoint.remoting.RemoteContract;

/**
 * Workflow may validate immutable forms and accepted submissions; it cannot mutate business records
 * here.
 */
@RemoteContract(
    name = "app.form-workflow",
    version = "1",
    errorDecoder = ApplicationFormErrorDecoder.class)
public interface ApplicationFormWorkflowPort {
  /** Validates a published form version, display scene and typed field mappings. */
  void validateBinding(FormWorkflowBindingCheck check);

  /** Checks the committed business snapshot against an engine-authenticated operation. */
  void validateSubmission(FormWorkflowSubmissionCheck check);
}
