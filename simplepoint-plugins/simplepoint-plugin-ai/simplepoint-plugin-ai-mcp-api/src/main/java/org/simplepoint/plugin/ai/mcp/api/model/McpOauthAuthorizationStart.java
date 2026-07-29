package org.simplepoint.plugin.ai.mcp.api.model;

import java.time.Instant;

/**
 * Browser authorization handoff for a remote MCP connection.
 *
 * @param authorizationUrl URL that the browser must open
 * @param expiresAt expiry of the one-time state and PKCE verifier
 */
public record McpOauthAuthorizationStart(
    String authorizationUrl,
    Instant expiresAt
) {
}
