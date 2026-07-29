package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;
import java.util.Map;

/**
 * SDK-neutral resources/read result.
 */
public record McpGatewayResourceReadResult(
    List<Map<String, Object>> contents,
    Map<String, Object> meta
) {
}
