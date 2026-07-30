package org.simplepoint.plugin.ai.skill.api.model;

/**
 * Immutable approval policy attached to one Skill version.
 *
 * @param required whether execution requires an explicit approval
 * @param allowSelfApproval whether the requester may approve the execution
 * @param instructions optional instructions shown to an approver
 */
public record SkillExecutionApprovalPolicy(
    boolean required,
    boolean allowSelfApproval,
    String instructions
) {
}
