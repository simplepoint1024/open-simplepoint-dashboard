package org.simplepoint.plugin.rbac.authentication;

import java.time.Instant;
import java.util.Objects;

/**
 * Local first-factor proof, independent of browser sessions and transport authentication tokens.
 */
public record MfaChallenge(
    String userId, long authorizationVersion, Instant expiresAt, String challengeId) {

  /** Rejects incomplete proofs; only the authentication owner creates challenges. */
  public MfaChallenge {
    if (userId == null
        || userId.isBlank()
        || authorizationVersion < 0
        || challengeId == null
        || challengeId.isBlank()) {
      throw new IllegalArgumentException("Invalid MFA challenge");
    }
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  /** Checks the fixed challenge deadline, irrespective of caller activity. */
  public boolean expired() {
    return !Instant.now().isBefore(expiresAt);
  }
}
