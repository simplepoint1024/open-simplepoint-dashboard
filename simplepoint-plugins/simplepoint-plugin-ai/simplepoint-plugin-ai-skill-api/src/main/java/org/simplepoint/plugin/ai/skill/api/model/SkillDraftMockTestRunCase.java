package org.simplepoint.plugin.ai.skill.api.model;

import java.time.Instant;

/** One execution row participating in a Mock regression run. */
public record SkillDraftMockTestRunCase(
    String executionId,
    String testCaseId,
    String testCaseName,
    int order,
    SkillExecutionStatus status,
    Boolean assertionsPassed,
    String errorCode,
    String errorMessage,
    Instant startedAt,
    Instant completedAt
) {
}
