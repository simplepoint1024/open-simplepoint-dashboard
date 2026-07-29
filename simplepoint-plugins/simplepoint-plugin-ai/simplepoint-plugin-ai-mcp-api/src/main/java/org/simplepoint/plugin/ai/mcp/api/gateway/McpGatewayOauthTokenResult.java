package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * OAuth token response returned only across the private control-plane boundary.
 *
 * @param accessToken access token
 * @param tokenType token type, which must be Bearer for MCP HTTP authorization
 * @param expiresIn token lifetime in seconds
 * @param refreshToken optional rotated refresh token
 * @param scope granted scope string
 */
public record McpGatewayOauthTokenResult(
    String accessToken,
    String tokenType,
    long expiresIn,
    String refreshToken,
    String scope
) {
}
