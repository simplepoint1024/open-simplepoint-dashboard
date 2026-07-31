package org.simplepoint.plugin.ai.workflow.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Completes a durable human task.
 *
 * @param output structured response validated against the task schema
 */
@Schema(title = "AI Workflow Human Task Response")
public record WorkflowHumanTaskResponseRequest(
    Map<String, Object> output
) {
}
