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
    String previousInstanceId,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    java.util.List<org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement> snapshotRequirements) {

  /** Legacy accepted operations retain the exact snapshot request and durable receipt identity. */
  AcceptedFormSubmission(PublishedApplicationForm form, SubmitApplicationFormCommand command, DnaInvocationContext dataAuthority,
      WorkflowInvocationContext workflowAuthority, RuntimeForm workflow, String previousInstanceId) {
    this(form, command, dataAuthority, workflowAuthority, workflow, previousInstanceId, null);
  }

  AcceptedFormSubmission {
    snapshotRequirements = new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSnapshotRequest(java.util.List.of(),
        snapshotRequirements).requirements();
  }
}
