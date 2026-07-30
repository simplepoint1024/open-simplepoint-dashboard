package org.simplepoint.plugin.ai.agent.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cooperative pause request for one durable Agent execution.
 *
 * @param reason optional operator reason
 */
@Schema(title = "Agent Execution Pause Request")
public record AgentExecutionPauseRequest(
    @Schema(maxLength = 1024)
    String reason
) {
}
