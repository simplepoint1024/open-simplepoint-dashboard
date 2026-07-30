package org.simplepoint.plugin.ai.agent.api.model;

import java.util.Map;

/**
 * Creates one immutable declarative Agent version.
 *
 * @param version semantic version
 * @param manifest complete SimplePoint Agent Manifest
 */
public record AgentVersionCreateRequest(
    String version,
    Map<String, Object> manifest
) {
}
