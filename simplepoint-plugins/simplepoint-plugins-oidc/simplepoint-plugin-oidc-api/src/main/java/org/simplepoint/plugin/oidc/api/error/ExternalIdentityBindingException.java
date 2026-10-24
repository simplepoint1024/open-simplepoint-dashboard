package org.simplepoint.plugin.oidc.api.error;

/**
 * Stable local failure; transport adapters translate the reason without exposing submitted data.
 */
public final class ExternalIdentityBindingException extends IllegalStateException {
  private final Reason reason;

  /** Creates a failure containing no user/provider supplied values. */
  public ExternalIdentityBindingException(Reason reason) {
    super("external-idp.binding." + reason.name());
    this.reason = reason;
  }

  public Reason getReason() {
    return reason;
  }

  public String getMessageKey() {
    return getMessage();
  }

  /** Binding failures that can be safely mapped to a browser or API response. */
  public enum Reason {
    INVALID_INPUT,
    PROVIDER_UNAVAILABLE,
    SUBJECT_IN_USE,
    ACCOUNT_IN_USE,
    BINDING_REVOKED,
    NOT_LINKED
  }
}
