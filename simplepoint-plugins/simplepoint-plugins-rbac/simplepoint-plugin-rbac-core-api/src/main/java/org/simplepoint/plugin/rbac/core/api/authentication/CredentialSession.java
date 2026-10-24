package org.simplepoint.plugin.rbac.core.api.authentication;

/** Opaque service-bound proof; the handle is not a user-facing bearer token or a log field. */
public record CredentialSession(String handle, CredentialIdentity identity) {
  /** Validates the opaque proof before it can enter a consuming service. */
  public CredentialSession {
    if (handle == null || !handle.matches("[A-Za-z0-9_-]{43}") || identity == null) {
      throw new IllegalArgumentException("Invalid credential session");
    }
  }

  @Override
  public String toString() {
    return "CredentialSession[redacted]";
  }
}
