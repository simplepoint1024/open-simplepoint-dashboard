package org.simplepoint.plugin.ai.agent.api.model;

import java.util.Map;

/**
 * Resolves one waiting Agent human-intervention task.
 *
 * @param outcome deterministic continue or cancel decision
 * @param input bounded structured operator input
 * @param comment optional audit comment
 */
public record AgentHumanInterventionResponseRequest(
    AgentHumanInterventionOutcome outcome,
    Map<String, Object> input,
    String comment
) {
}
