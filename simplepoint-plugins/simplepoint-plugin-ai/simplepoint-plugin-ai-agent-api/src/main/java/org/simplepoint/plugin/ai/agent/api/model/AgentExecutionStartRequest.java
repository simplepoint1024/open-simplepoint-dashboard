package org.simplepoint.plugin.ai.agent.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Starts one idempotent execution of the active Agent version.
 *
 * @param idempotencyKey caller-owned retry deduplication key
 * @param input input object validated against the Agent version schema
 */
@Schema(title = "Agent Execution Start Request")
public record AgentExecutionStartRequest(
    String idempotencyKey,
    Map<String, Object> input
) {
}
