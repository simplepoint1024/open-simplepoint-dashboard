package org.simplepoint.plugin.oidc.api.model;

/** Public, credential-free identity-provider summary rendered on the login page. */
public record ExternalIdentityProviderView(
    String registrationId,
    String displayName,
    ExternalIdentityProviderPreset preset,
    int sortOrder
) {
}
