package org.simplepoint.plugin.ai.skill.api.model;

/**
 * Starts an idempotent publication from one immutable Draft Revision.
 *
 * @param draftRevision exact source revision
 * @param version target semantic version
 * @param activate whether the created version should become active
 * @param idempotencyKey caller-owned retry key
 */
public record SkillDraftPublishRequest(
    long draftRevision,
    String version,
    boolean activate,
    String idempotencyKey
) {
}
