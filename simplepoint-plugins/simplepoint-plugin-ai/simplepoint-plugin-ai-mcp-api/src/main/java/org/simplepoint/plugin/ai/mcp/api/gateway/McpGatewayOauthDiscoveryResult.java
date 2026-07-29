package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;
import java.util.Map;

/**
 * Validated OAuth metadata for one protected remote MCP resource.
 *
 * @param resource canonical protected resource identifier
 * @param authorizationServer selected authorization server issuer
 * @param authorizationEndpoint authorization endpoint
 * @param tokenEndpoint token endpoint
 * @param registrationEndpoint optional dynamic client registration endpoint
 * @param clientIdMetadataDocumentSupported whether the authorization server supports CIMD
 * @param clientIdMetadataDocumentUri configured Gateway client metadata URL
 * @param clientIdMetadataRedirectUris redirect URIs published by the Gateway metadata document
 * @param scopesSupported advertised authorization scopes
 * @param codeChallengeMethodsSupported advertised PKCE methods
 * @param protectedResourceMetadata complete RFC 9728 metadata
 * @param authorizationServerMetadata complete authorization-server metadata
 */
public record McpGatewayOauthDiscoveryResult(
    String resource,
    String authorizationServer,
    String authorizationEndpoint,
    String tokenEndpoint,
    String registrationEndpoint,
    boolean clientIdMetadataDocumentSupported,
    String clientIdMetadataDocumentUri,
    List<String> clientIdMetadataRedirectUris,
    List<String> scopesSupported,
    List<String> codeChallengeMethodsSupported,
    Map<String, Object> protectedResourceMetadata,
    Map<String, Object> authorizationServerMetadata
) {
}
