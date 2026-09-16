package org.simplepoint.plugin.ai.skill.api.model;

/** Optional operator reason for cooperatively cancelling an execution. */
public record SkillExecutionCancelRequest(String reason) {
}
