package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;

/**
 * Dynamic client registration request sent through the isolated Gateway.
 *
 * @param registrationEndpoint discovered registration endpoint
 * @param redirectUris exact redirect URIs
 * @param clientName human-readable client name
 * @param scopes requested scopes
 * @param allowPrivateNetwork whether private destinations may be accessed
 */
public record McpGatewayOauthRegistrationRequest(
    String registrationEndpoint,
    List<String> redirectUris,
    String clientName,
    List<String> scopes,
    boolean allowPrivateNetwork
) {
}
