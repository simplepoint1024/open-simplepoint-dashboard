package org.simplepoint.cloud.oauth.server.provider;

import java.time.Instant;
import java.util.List;
import org.simplepoint.plugin.rbac.authentication.MfaChallenge;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** Contains no credentials or grants and is never accepted as an authenticated user. */
public final class PendingMfaAuthentication extends AbstractAuthenticationToken {
  private final String userId;
  private final long authorizationVersion;
  private final Instant expiresAt;
  private final String challengeId = java.util.UUID.randomUUID().toString();

  /** Creates an expiring challenge without storing a password, seed or authority. */
  public PendingMfaAuthentication(String userId, long authorizationVersion, Instant expiresAt) {
    super(List.of());
    this.userId = userId;
    this.authorizationVersion = authorizationVersion;
    this.expiresAt = expiresAt;
    super.setAuthenticated(false);
  }

  @Override
  public Object getPrincipal() {
    return userId;
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public void setAuthenticated(boolean authenticated) {
    if (authenticated) {
      throw new IllegalArgumentException("Pending MFA cannot be authenticated");
    }
    super.setAuthenticated(false);
  }

  /** Returns the account version that passed the first factor. */
  public long authorizationVersion() {
    return authorizationVersion;
  }

  /** Returns the single-use identity of this challenge. */
  public String challengeId() {
    return challengeId;
  }

  /** Checks the challenge deadline independently of session activity. */
  public boolean expired() {
    return !Instant.now().isBefore(expiresAt);
  }

  /** Exposes the local proof to the shared verifier without transporting a browser token. */
  public MfaChallenge challenge() {
    return new MfaChallenge(userId, authorizationVersion, expiresAt, challengeId);
  }
}
