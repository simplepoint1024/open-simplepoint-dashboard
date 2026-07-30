package org.simplepoint.plugin.ai.agent.api.model;

import java.util.List;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionEvent;

/**
 * Cursor-based incremental event response.
 *
 * @param afterSequence requested exclusive cursor
 * @param nextSequence next exclusive cursor
 * @param hasMore whether another bounded page is available
 * @param executionStatus current execution status
 * @param events ordered durable events
 */
public record AgentExecutionEventFeed(
    long afterSequence,
    long nextSequence,
    boolean hasMore,
    AgentExecutionStatus executionStatus,
    List<AiAgentExecutionEvent> events
) {
}
