package org.simplepoint.plugin.ai.workflow.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Creates an immutable declarative Workflow version.
 *
 * @param version semantic version
 * @param manifest complete AgentWorkflow manifest
 */
@Schema(title = "AI Workflow Version Create Request")
public record WorkflowVersionCreateRequest(
    String version,
    Map<String, Object> manifest
) {
}
