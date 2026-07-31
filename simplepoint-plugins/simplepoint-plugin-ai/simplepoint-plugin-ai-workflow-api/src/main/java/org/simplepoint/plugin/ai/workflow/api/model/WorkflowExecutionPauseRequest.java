package org.simplepoint.plugin.ai.workflow.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Requests a cooperative Workflow pause.
 *
 * @param reason operator reason
 */
@Schema(title = "AI Workflow Execution Pause Request")
public record WorkflowExecutionPauseRequest(String reason) {
}
