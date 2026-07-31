package org.simplepoint.plugin.ai.workflow.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Starts an idempotent execution of the active Workflow version.
 *
 * @param idempotencyKey caller-owned retry deduplication key
 * @param input input validated against the immutable Workflow schema
 */
@Schema(title = "AI Workflow Execution Start Request")
public record WorkflowExecutionStartRequest(
    String idempotencyKey,
    Map<String, Object> input
) {
}
