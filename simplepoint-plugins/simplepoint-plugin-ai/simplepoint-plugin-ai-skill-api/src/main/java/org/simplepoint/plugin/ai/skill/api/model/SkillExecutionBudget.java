package org.simplepoint.plugin.ai.skill.api.model;

/**
 * Immutable execution budget fixed by one Skill version.
 *
 * @param maximumToolCalls total MCP Tool calls, including failed attempts
 * @param maximumDurationSeconds wall-clock time from submission to completion
 * @param maximumPayloadBytes cumulative serialized input, arguments, Tool
 *                            results and workflow output bytes
 */
public record SkillExecutionBudget(
    int maximumToolCalls,
    int maximumDurationSeconds,
    long maximumPayloadBytes
) {
}
