package org.simplepoint.cloud.oauth.server.client;

import java.time.Instant;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderPreset;

/** Credential-free account-connection state rendered in personal security settings. */
public record ExternalIdentityConnectionView(
    String registrationId,
    String displayName,
    ExternalIdentityProviderPreset preset,
    boolean enabled,
    boolean linked,
    String email,
    Instant linkedAt,
    Instant lastLoginAt
) {
}
