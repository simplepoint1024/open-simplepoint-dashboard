package org.simplepoint.plugin.workflow.api.model;

/**
 * Server-derived process identity passed to a trusted form provider, never copied from user
 * variables.
 */
public record WorkflowFormContext(
    String tenantId,
    String actorId,
    String definitionId,
    int definitionVersion,
    String processInstanceId,
    String businessKey,
    WorkflowInvocationContext authority) {}
