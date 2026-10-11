package org.simplepoint.plugin.oidc.api.model;

import org.simplepoint.core.annotation.PermissionFields;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityProvider;

/** Result returned after validating an external identity-provider configuration. */
public record ExternalIdentityProviderConnectionTestResult(
    boolean success,
    String message,
    @PermissionFields(resource = ExternalIdentityProvider.class, value = {"authorizationUri", "issuerUri"}) String authorizationUri,
    @PermissionFields(resource = ExternalIdentityProvider.class, value = {"tokenUri", "issuerUri"}) String tokenUri,
    @PermissionFields(resource = ExternalIdentityProvider.class, value = {"userInfoUri", "issuerUri"}) String userInfoUri,
    @PermissionFields(resource = ExternalIdentityProvider.class, value = {"jwkSetUri", "issuerUri"}) String jwkSetUri,
    @PermissionFields(resource = ExternalIdentityProvider.class, value = "registrationId") String callbackUri
) {
}
