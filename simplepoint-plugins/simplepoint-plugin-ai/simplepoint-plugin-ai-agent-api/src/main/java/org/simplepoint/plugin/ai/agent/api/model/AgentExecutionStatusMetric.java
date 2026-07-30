package org.simplepoint.plugin.ai.agent.api.model;

import java.math.BigDecimal;

/**
 * Persistent execution aggregation for one status.
 */
public record AgentExecutionStatusMetric(
    AgentExecutionStatus status,
    long executionCount,
    Long inputTokens,
    Long outputTokens,
    BigDecimal cost,
    Long steps,
    Long humanInterventions
) {
}
