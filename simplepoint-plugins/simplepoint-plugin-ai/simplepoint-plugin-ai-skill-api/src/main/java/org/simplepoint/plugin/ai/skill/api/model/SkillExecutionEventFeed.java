package org.simplepoint.plugin.ai.skill.api.model;

import java.util.List;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionEvent;

/**
 * Cursor-based incremental Skill event response.
 *
 * @param afterSequence requested exclusive cursor
 * @param nextSequence next exclusive cursor
 * @param hasMore whether another bounded page is available
 * @param executionStatus current durable execution status
 * @param events ordered append-only events
 */
public record SkillExecutionEventFeed(
    long afterSequence,
    long nextSequence,
    boolean hasMore,
    SkillExecutionStatus executionStatus,
    List<AiSkillExecutionEvent> events
) {
}
