package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Authorization-code or refresh-token exchange through the isolated Gateway.
 *
 * @param tokenEndpoint discovered token endpoint
 * @param grantType authorization_code or refresh_token
 * @param clientId OAuth client identifier
 * @param clientSecret optional pre-registered or dynamically issued client secret
 * @param tokenEndpointAuthMethod none, client_secret_post, or client_secret_basic
 * @param code authorization code for an authorization-code exchange
 * @param codeVerifier PKCE verifier for an authorization-code exchange
 * @param redirectUri exact redirect URI used in the authorization request
 * @param refreshToken refresh token for a refresh exchange
 * @param resource RFC 8707 target resource
 * @param scopes scopes requested during refresh
 * @param allowPrivateNetwork whether private destinations may be accessed
 */
public record McpGatewayOauthTokenRequest(
    String tokenEndpoint,
    String grantType,
    String clientId,
    String clientSecret,
    String tokenEndpointAuthMethod,
    String code,
    String codeVerifier,
    String redirectUri,
    String refreshToken,
    String resource,
    String scopes,
    boolean allowPrivateNetwork
) {
}
