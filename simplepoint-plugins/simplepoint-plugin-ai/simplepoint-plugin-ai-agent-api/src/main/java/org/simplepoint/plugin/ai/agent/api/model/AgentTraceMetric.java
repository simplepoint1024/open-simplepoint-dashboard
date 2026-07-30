package org.simplepoint.plugin.ai.agent.api.model;

import java.math.BigDecimal;

/**
 * Persistent Trace aggregation for one type and status.
 */
public record AgentTraceMetric(
    AgentTraceType type,
    AgentTraceStatus status,
    long traceCount,
    Long inputTokens,
    Long outputTokens,
    BigDecimal cost
) {
}
