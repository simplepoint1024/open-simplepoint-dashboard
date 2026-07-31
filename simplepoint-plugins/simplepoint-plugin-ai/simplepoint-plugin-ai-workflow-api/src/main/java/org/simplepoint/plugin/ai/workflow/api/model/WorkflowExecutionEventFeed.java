package org.simplepoint.plugin.ai.workflow.api.model;

import java.util.List;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecutionEvent;

/**
 * Bounded append-only Workflow event page.
 *
 * @param afterSequence exclusive input cursor
 * @param nextSequence last returned sequence
 * @param hasMore whether another page exists
 * @param executionStatus current durable execution status
 * @param events ordered events
 */
public record WorkflowExecutionEventFeed(
    long afterSequence,
    long nextSequence,
    boolean hasMore,
    WorkflowExecutionStatus executionStatus,
    List<AiWorkflowExecutionEvent> events
) {
}
