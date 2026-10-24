package org.simplepoint.plugin.rbac.core.api.authentication;

/** Credentials are sent only to the identity owner and must never be logged or audited. */
public record CredentialRequest(String username, String password, String oneTimeCode) {
  @Override
  public String toString() {
    return "CredentialRequest[redacted]";
  }
}
