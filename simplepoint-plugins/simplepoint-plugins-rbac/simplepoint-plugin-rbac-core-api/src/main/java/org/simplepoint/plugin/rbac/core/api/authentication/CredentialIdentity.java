package org.simplepoint.plugin.rbac.core.api.authentication;

import java.time.Instant;

/** Verified, bounded-lifetime identity without password hashes, MFA seeds or persistence state. */
public record CredentialIdentity(
    String userId, long authorizationVersion, boolean administrator, Instant expiresAt) {
  /** Rejects malformed identity responses without echoing their contents. */
  public CredentialIdentity {
    if (userId == null
        || userId.isBlank()
        || userId.length() > 64
        || authorizationVersion < 0
        || expiresAt == null) {
      throw new IllegalArgumentException("Invalid credential identity");
    }
  }
}
