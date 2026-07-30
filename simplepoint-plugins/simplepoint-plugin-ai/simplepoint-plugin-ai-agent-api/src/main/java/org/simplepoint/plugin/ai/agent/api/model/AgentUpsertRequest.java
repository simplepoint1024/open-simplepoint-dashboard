package org.simplepoint.plugin.ai.agent.api.model;

/**
 * Mutable metadata accepted when creating or editing an Agent.
 *
 * @param code stable scope-local code; ignored during metadata updates
 * @param name display name
 * @param description optional description
 * @param enabled whether the Agent can publish and execute active versions
 */
public record AgentUpsertRequest(
    String code,
    String name,
    String description,
    Boolean enabled
) {
}
