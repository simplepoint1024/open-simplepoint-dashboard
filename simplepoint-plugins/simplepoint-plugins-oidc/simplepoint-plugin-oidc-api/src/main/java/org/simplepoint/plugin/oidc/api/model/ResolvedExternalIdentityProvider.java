package org.simplepoint.plugin.oidc.api.model;

import java.time.Instant;
import java.util.Set;

/** Decrypted provider configuration used only inside trusted authentication services. */
public record ResolvedExternalIdentityProvider(
    String id,
    String registrationId,
    String displayName,
    ExternalIdentityProviderPreset preset,
    ExternalIdentityProviderProtocol protocol,
    String issuerUri,
    String authorizationUri,
    String tokenUri,
    String userInfoUri,
    String jwkSetUri,
    String clientId,
    String clientSecret,
    String clientAuthenticationMethod,
    Set<String> scopes,
    String userNameAttribute,
    String subjectClaim,
    String emailClaim,
    String emailVerifiedClaim,
    ExternalIdentityMatchStrategy matchStrategy,
    boolean requireVerifiedEmail,
    boolean allowPrivateNetwork,
    Instant updatedAt
) {
}
