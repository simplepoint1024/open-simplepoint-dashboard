package org.simplepoint.plugin.ai.agent.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Approval or rejection comment for an Agent execution.
 *
 * @param comment optional audit comment
 */
@Schema(title = "Agent Execution Decision Request")
public record AgentExecutionDecisionRequest(String comment) {
}
