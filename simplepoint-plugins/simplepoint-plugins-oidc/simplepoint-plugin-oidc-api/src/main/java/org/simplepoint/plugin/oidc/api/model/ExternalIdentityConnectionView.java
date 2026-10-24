package org.simplepoint.plugin.oidc.api.model;

import java.time.Instant;

/** Credential-free account-connection state rendered in personal security settings. */
public record ExternalIdentityConnectionView(
    String registrationId,
    String displayName,
    ExternalIdentityProviderPreset preset,
    boolean enabled,
    boolean linked,
    String email,
    Instant linkedAt,
    Instant lastLoginAt) {}
