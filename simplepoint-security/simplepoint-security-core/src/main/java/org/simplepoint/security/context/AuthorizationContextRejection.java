package org.simplepoint.security.context;

import java.util.function.Supplier;
import org.simplepoint.remoting.RemoteBusinessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

/** Declared authentication/policy rejections; infrastructure exceptions are never reclassified. */
public final class AuthorizationContextRejection extends RemoteBusinessException {
  /** Stable reasons understood by both local and remote callers. */
  public enum Reason { AUTHENTICATION_REQUIRED, ACCESS_DENIED }

  private final Reason reason;

  /** Creates a rejection without exposing identity or policy values. */
  public AuthorizationContextRejection(final Reason reason) {
    super(reason == Reason.ACCESS_DENIED ? "Authorization context access denied" : "Authentication is required");
    this.reason = java.util.Objects.requireNonNull(reason);
  }

  /** Returns the contract-owned reason. */
  public Reason reason() {
    return reason;
  }

  @Override
  public String remoteErrorCode() {
    return "AUTHORIZATION_CONTEXT:" + reason.name();
  }

  static <T> T capture(final Supplier<T> operation) {
    try {
      return operation.get();
    } catch (AccessDeniedException denied) {
      throw new AuthorizationContextRejection(Reason.ACCESS_DENIED);
    } catch (AuthenticationException invalid) {
      throw new AuthorizationContextRejection(Reason.AUTHENTICATION_REQUIRED);
    }
  }
}
