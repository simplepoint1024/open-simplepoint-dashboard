package org.simplepoint.plugin.ai.skill.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Starts all enabled Mock test cases from one immutable Draft Revision.
 *
 * @param revision immutable Draft Revision to test
 * @param idempotencyKey caller-owned key used to deduplicate retries
 */
@Schema(title = "Skill Draft Mock Test Run Start Request")
public record SkillDraftMockTestRunStartRequest(
    Long revision,
    String idempotencyKey
) {
}
