package org.simplepoint.plugin.ai.agent.api.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Restart-safe Agent metrics aggregated from durable execution data.
 */
public record AgentExecutionMetrics(
    Instant windowFrom,
    Instant windowTo,
    long totalExecutions,
    long activeExecutions,
    long succeededExecutions,
    long failedExecutions,
    long cancelledExecutions,
    long rejectedExecutions,
    long inputTokens,
    long outputTokens,
    BigDecimal cost,
    long steps,
    long humanInterventions,
    List<AgentExecutionStatusMetric> statuses,
    List<AgentTraceMetric> traces
) {
}
