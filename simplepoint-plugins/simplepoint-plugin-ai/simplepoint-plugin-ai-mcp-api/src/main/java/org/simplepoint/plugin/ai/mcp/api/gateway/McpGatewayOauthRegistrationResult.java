package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Credentials returned by RFC 7591 dynamic client registration.
 *
 * @param clientId registered client identifier
 * @param clientSecret optional client secret
 * @param clientSecretExpiresAt epoch second or zero when it does not expire
 * @param tokenEndpointAuthMethod registered token endpoint authentication method
 */
public record McpGatewayOauthRegistrationResult(
    String clientId,
    String clientSecret,
    long clientSecretExpiresAt,
    String tokenEndpointAuthMethod
) {
}
