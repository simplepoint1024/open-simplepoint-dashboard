package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.workflow.api.model.WorkflowInvocationContext;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;

/** Frozen server-resolved command; replay can finish only this previously authorized operation. */
record AcceptedFormSubmission(
    PublishedApplicationForm form,
    SubmitApplicationFormCommand command,
    DnaInvocationContext dataAuthority,
    WorkflowInvocationContext workflowAuthority,
    RuntimeForm workflow,
    String previousInstanceId) {}
