package org.simplepoint.plugin.oidc.api.model;

/** Result returned after validating an external identity-provider configuration. */
public record ExternalIdentityProviderConnectionTestResult(
    boolean success,
    String message,
    String authorizationUri,
    String tokenUri,
    String userInfoUri,
    String jwkSetUri
) {
}
