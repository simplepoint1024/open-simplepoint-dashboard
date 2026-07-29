package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;
import java.util.Map;

/**
 * SDK-neutral prompts/get result.
 */
public record McpGatewayPromptGetResult(
    String description,
    List<Map<String, Object>> messages,
    Map<String, Object> meta
) {
}
